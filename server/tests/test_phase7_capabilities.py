"""Server 收口能力测试: Playback API、Duplicate Scanner、Pairing。"""

from __future__ import annotations

from pathlib import Path

from app.db.models import MediaCacheIndex
from app.db.session import Database
from app.services import duplicate_scanner, pairing


def _make_db(data_root: Path) -> Database:
    db = Database(data_root / "database" / "mediareview.db")
    db.create_all()
    return db


def _seed_media(
    db: Database,
    media_id: str,
    size: int,
    duration: int,
    *,
    quick_hash: str | None = None,
    sha256: str | None = None,
    media_path: str | None = "D:\\Media\\x.mp4",
) -> None:
    with db.session() as s:
        s.add(
            MediaCacheIndex(
                media_id=media_id,
                jellyfin_id="j-" + media_id,
                library_id="lib-movies",
                name=media_id + ".mp4",
                media_type="video",
                fingerprint="fp-" + media_id,
                size_bytes=size,
                duration_ms=duration,
                quick_hash=quick_hash,
                sha256=sha256,
                media_path=media_path,
            )
        )
        s.commit()


# ---- Duplicate Scanner ----


def test_exact_duplicates_group_by_size_and_duration_same_sha256(tmp_path: Path) -> None:
    db = _make_db(tmp_path)
    _seed_media(db, "exact-a", size=500, duration=3000, sha256="a" * 64)
    _seed_media(db, "exact-b", size=500, duration=3000, sha256="a" * 64)
    _seed_media(db, "unique-c", size=999, duration=3000, sha256="b" * 64)

    with db.session() as s:
        groups = duplicate_scanner.scan_exact_duplicates(s)
    assert len(groups) == 1
    assert groups[0].type == "exact"
    assert set(groups[0].media_ids) == {"exact-a", "exact-b"}
    assert groups[0].count == 2
    db.dispose()


def test_exact_requires_byte_identical_sha256_not_size_duration_only(tmp_path: Path) -> None:
    """反例: 两个 size+duration 完全相同、但内容(sha256)不同的视频,不得判定为 exact。"""
    db = _make_db(tmp_path)
    _seed_media(db, "same-size-1", size=500, duration=3000, sha256="1" * 64)
    _seed_media(db, "same-size-2", size=500, duration=3000, sha256="2" * 64)

    with db.session() as s:
        exact = duplicate_scanner.scan_exact_duplicates(s)
    assert exact == []
    db.dispose()


def test_exact_requires_computed_sha256(tmp_path: Path) -> None:
    """未计算完整哈希的候选绝不能被当作 exact(即使 size+duration 一致)。"""
    db = _make_db(tmp_path)
    _seed_media(db, "no-sha-1", size=500, duration=3000, quick_hash="q" * 64)
    _seed_media(db, "no-sha-2", size=500, duration=3000, quick_hash="q" * 64)

    with db.session() as s:
        exact = duplicate_scanner.scan_exact_duplicates(s)
        high = duplicate_scanner.scan_high_confidence(s)
        candidates = duplicate_scanner.scan_candidates(s)
    assert exact == []
    assert len(high) == 1
    assert high[0].type == "high"
    assert set(high[0].media_ids) == {"no-sha-1", "no-sha-2"}
    # quick_hash 已计算,不再属于待哈希候选
    assert candidates == []
    db.dispose()


def test_exact_scanner_rejects_invalid_full_sha256_values(tmp_path: Path) -> None:
    db = _make_db(tmp_path)
    pairs = (
        (500, "partial-hash"),
        (600, "g" * 64),
        (700, "unreadable-io"),
        (800, "a" * 64),
        (810, "a" * 64 + "\0suffix"),
        (820, b"a" * 64),
        (830, "a" * 31 + "\0" + "a" * 32),
        (840, "é" * 64),
    )
    for size, sha256 in pairs:
        _seed_media(db, f"{size}-a", size=size, duration=3000, sha256=sha256)
        _seed_media(db, f"{size}-b", size=size, duration=3000, sha256=sha256)

    with db.session() as session:
        exact = duplicate_scanner.scan_exact_duplicates(session)
    assert len(exact) == 1
    assert set(exact[0].media_ids) == {"800-a", "800-b"}
    db.dispose()


def test_candidates_are_pending_hash_media(tmp_path: Path) -> None:
    """仅 size+duration 一致、quick_hash 尚未计算 → 进入候选(等待后台哈希)。"""
    db = _make_db(tmp_path)
    _seed_media(db, "pend-1", size=500, duration=3000)
    _seed_media(db, "pend-2", size=500, duration=3000)

    with db.session() as s:
        assert duplicate_scanner.has_pending_hashes(s) is True
        candidates = duplicate_scanner.scan_candidates(s)
    assert len(candidates) == 1
    assert candidates[0].type == "candidate"
    assert set(candidates[0].media_ids) == {"pend-1", "pend-2"}
    db.dispose()


def test_similar_candidates_by_same_size_different_duration(tmp_path: Path) -> None:
    db = _make_db(tmp_path)
    _seed_media(db, "sim-a", size=1000, duration=1000)
    _seed_media(db, "sim-b", size=1000, duration=10000)  # 时长差异远超容忍值

    with db.session() as s:
        groups = duplicate_scanner.scan_similar_candidates(s)
    assert len(groups) == 1
    assert groups[0].type == "similar"
    assert set(groups[0].media_ids) == {"sim-a", "sim-b"}
    db.dispose()


# ---- Pairing ----


def test_pairing_one_time_code_lifecycle(tmp_path: Path) -> None:
    db = _make_db(tmp_path)
    with db.session() as s:
        code = pairing.generate_pairing_code(s)
        assert len(code) == 6 and code.isdigit()
        # 成功配对返回可持久化 token(非空)
        token1 = pairing.verify_and_pair(s, code, "device-1")
        assert token1.startswith("mr_")
        # 同一码不能配对第二台设备(一次性)
        assert pairing.verify_and_pair(s, code, "device-2") == ""
        s.commit()
        assert pairing.is_paired(s, "device-1") is True
        assert pairing.is_paired(s, "device-2") is False
        # 服务端只存 token 哈希,不存明文
        from app.db.models import PairedDevice

        dev = s.get(PairedDevice, "device-1")
        import hashlib

        assert dev.token_hash == hashlib.sha256(token1.encode("utf-8")).hexdigest()
        assert dev.token_hash != token1
    db.dispose()


def test_pairing_rejects_empty_or_unknown_code(tmp_path: Path) -> None:
    db = _make_db(tmp_path)
    with db.session() as s:
        assert pairing.verify_and_pair(s, "", "device-x") == ""
        assert pairing.verify_and_pair(s, "000000", "device-x") == ""
        assert pairing.is_paired(s, "device-x") is False
    db.dispose()


def test_pairing_authenticate_and_revoke(tmp_path: Path) -> None:
    db = _make_db(tmp_path)
    with db.session() as s:
        code = pairing.generate_pairing_code(s)
        token = pairing.verify_and_pair(s, code, "phone")
        s.commit()
        # 合法 token 可认证
        assert pairing.authenticate(s, token) is not None
        # 伪造 token 不可认证
        assert pairing.authenticate(s, "mr_forged_aaaa...") is None
        # revoke 后立即失效
        assert pairing.revoke_device(s, "phone") is True
        s.commit()
        assert pairing.authenticate(s, token) is None
        assert not pairing.is_paired(s, "phone")
    db.dispose()


# ---- Playback API(带 Jellyfin mock) ----


def test_playback_api_returns_stream_url(jellyfin_api_client) -> None:
    client, _ = jellyfin_api_client
    db: Database = client.app.state.database
    with db.session() as s:
        s.add(
            MediaCacheIndex(
                media_id="play1",
                jellyfin_id="jf-video-1",
                library_id="lib-movies",
                name="Clip.mp4",
                media_type="video",
                fingerprint="fp-play1",
                duration_ms=60_000,
                width=1920,
                height=1080,
                container="mp4",
            )
        )
        s.commit()

    resp = client.get("/api/v1/media/play1/playback")
    assert resp.status_code == 200
    data = resp.json()["data"]
    assert data["media_id"] == "play1"
    assert data["stream_url"].startswith("http://127.0.0.1:8096/Videos/jf-video-1/stream")
    assert "api_key" not in data["stream_url"].casefold()
    assert data["requires_jellyfin_auth"] is True
    assert "认证" in data["message"]


def test_playback_api_rejects_unknown_or_non_video(jellyfin_api_client) -> None:
    client, _ = jellyfin_api_client
    db: Database = client.app.state.database
    with db.session() as s:
        s.add(
            MediaCacheIndex(
                media_id="img1",
                jellyfin_id="jf-img-1",
                library_id="lib-photos",
                name="P1.jpg",
                media_type="image",
                fingerprint="fp-img1",
                container="jpg",
            )
        )
        s.commit()

    assert client.get("/api/v1/media/ghost/playback").status_code == 404
    assert client.get("/api/v1/media/img1/playback").status_code == 422


# ---- Pairing API ----


def test_pairing_api_flow(client) -> None:
    resp = client.get("/api/v1/pairing/status")
    assert resp.status_code == 200
    # 读取实际配置(default fixture 关闭配对),而非写死 True
    assert resp.json()["data"]["pairing_required"] is False

    code = client.post("/api/v1/pairing/code").json()["data"]["code"]
    assert len(code) == 6 and code.isdigit()

    ok_resp = client.post(
        "/api/v1/pairing/verify", json={"device_id": "phone-1", "code": code}
    ).json()["data"]
    assert ok_resp["paired"] is True

    devices = client.get("/api/v1/pairing/devices").json()["data"]
    assert any(d["device_id"] == "phone-1" for d in devices)

    # 同一码不可二次使用
    again = client.post(
        "/api/v1/pairing/verify", json={"device_id": "phone-2", "code": code}
    ).json()["data"]
    assert again["paired"] is False


def test_pairing_api_generate_and_clean(client) -> None:
    assert client.post("/api/v1/pairing/code").status_code == 200
    code = client.post("/api/v1/pairing/code").json()["data"]["code"]
    # 用掉该码后,clean 应能清除已使用码
    client.post("/api/v1/pairing/verify", json={"device_id": "clean-phone", "code": code})
    cleaned = client.post("/api/v1/pairing/codes/clean").json()["data"]
    assert cleaned["removed"] >= 1
