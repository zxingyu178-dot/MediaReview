"""阶段 4/5/6 API 集成测试(经统一 Envelope)。"""

from __future__ import annotations

from pathlib import Path

from app.db.models import LibrarySelection, MediaCacheIndex, SpriteManifest
from app.db.session import Database
from app.services import review


def _seed_media(app, media_id: str, *, media_path: str = "D:\\Media\\x.mp4") -> None:
    db: Database = app.state.database
    with db.session() as s:
        s.merge(
            LibrarySelection(
                jellyfin_id="lib-movies", name="电影", collection_type="movies", selected=True
            )
        )
        s.commit()
    p = Path(media_path)
    size = p.stat().st_size if p.is_file() else 100
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
                media_path=media_path,
            )
        )
        s.commit()


def test_review_api_flow_source_based(jellyfin_api_client) -> None:
    """批阅会话按 source 从 SQLite 已选库索引构建队列,并支持分页。"""
    client, _transport = jellyfin_api_client
    client.get("/api/v1/libraries")
    client.put("/api/v1/libraries/selection", json={"selected": ["lib-movies"]})
    db: Database = client.app.state.database
    with db.session() as session:
        session.add_all(
            [
                MediaCacheIndex(
                    media_id=f"mv-{index}",
                    jellyfin_id=f"jf-mv-{index}",
                    library_id="lib-movies",
                    name=name,
                    media_type="video",
                    fingerprint=f"fp-mv-{index}",
                    is_available=True,
                )
                for index, name in enumerate(["B.mp4", "a.mp4", "C.mp4", "d.mp4", "E.mp4"], start=1)
            ]
        )
        session.commit()

    resp = client.post(
        "/api/v1/review/sessions",
        json={
            "source": {"filter": {"media_type": "video"}, "sort": {"sort_by": "name"}},
        },
    )
    assert resp.status_code == 200
    body = resp.json()["data"]
    session_id = body["session_id"]
    # 电影库 5 个视频,服务端按名称升序构建队列
    assert body["total_count"] == 5
    assert body["status"] == "active"
    assert body["source"]["filter"]["media_type"] == "video"

    latest = client.get("/api/v1/review/sessions/latest").json()["data"]
    assert latest["session_id"] == session_id

    # 队列分页:第 1 页 2 条,总数 5
    queue = client.get(
        f"/api/v1/review/sessions/{session_id}/queue", params={"page": 1, "page_size": 2}
    )
    assert queue.status_code == 200
    qdata = queue.json()["data"]
    assert qdata["total"] == 5
    assert [item["index"] for item in qdata["items"]] == [0, 1]
    assert qdata["page"] == 1
    assert qdata["page_size"] == 2
    # 第 3 页(2 条/页)取到最后一页
    last = client.get(
        f"/api/v1/review/sessions/{session_id}/queue", params={"page": 3, "page_size": 2}
    )
    assert [item["index"] for item in last.json()["data"]["items"]] == [4]

    # 标记已看并前进(Stage 8B.1 §19:完成必须 fail-closed —— 仍有未批阅时保持 active)
    seen_id = qdata["items"][0]["media"]["media_id"]
    seen = client.post(
        f"/api/v1/review/sessions/{session_id}/seen",
        json={"media_id": seen_id, "seen": True},
    ).json()["data"]
    assert seen["seen"] is True
    assert seen["seen_count"] == 1
    assert seen["total_count"] == 5
    for _ in range(5):
        client.post(f"/api/v1/review/sessions/{session_id}/advance")
    waiting = client.get(f"/api/v1/review/sessions/{session_id}").json()["data"]
    assert waiting["status"] == "active", "还有未批阅内容时不得自动完成会话"

    # 批阅剩余条目后再次 advance 到末尾:允许完成
    all_items = client.get(
        f"/api/v1/review/sessions/{session_id}/queue", params={"page": 1, "page_size": 5}
    ).json()["data"]["items"]
    for item in all_items:
        client.post(
            f"/api/v1/review/sessions/{session_id}/seen",
            json={"media_id": item["media"]["media_id"], "seen": True},
        )
    client.post(f"/api/v1/review/sessions/{session_id}/advance")
    done = client.get(f"/api/v1/review/sessions/{session_id}").json()["data"]
    assert done["status"] == "completed"


def test_review_resume_deep_index(app, client) -> None:
    """批阅断点恢复:current_index 随 position 更新,能恢复到数千条里的第 637 条。"""
    db: Database = app.state.database
    media_ids = [f"m{i:04d}" for i in range(1000)]
    with db.session() as s:
        for i in range(1000):
            s.add(
                MediaCacheIndex(
                    media_id=media_ids[i],
                    jellyfin_id="j-" + media_ids[i],
                    library_id="lib-movies",
                    name=media_ids[i] + ".mp4",
                    media_type="video",
                    fingerprint="fp-" + media_ids[i],
                    size_bytes=100,
                    media_path="D:\\Media\\x.mp4",
                )
            )
        s.flush()
        sess = review.create_session(s, media_ids)
        s.commit()
        session_id = sess.session_id

    # 批阅移动到位 637
    resp = client.post(f"/api/v1/review/sessions/{session_id}/position", json={"index": 637})
    assert resp.status_code == 200
    assert resp.json()["data"]["current_index"] == 637

    # 恢复:最近会话 current_index = 637
    latest = client.get("/api/v1/review/sessions/latest").json()["data"]
    assert latest["session_id"] == session_id
    assert latest["current_index"] == 637

    # 含绝对 637 的分页(第 13 页,50/页)应返回该条
    page = 637 // 50 + 1  # 13
    q = client.get(
        f"/api/v1/review/sessions/{session_id}/queue", params={"page": page, "page_size": 50}
    )
    data = q.json()["data"]
    assert data["total"] == 1000
    indexes = [item["index"] for item in data["items"]]
    assert 637 in indexes
    target = next(item for item in data["items"] if item["index"] == 637)
    assert target["media"]["media_id"] == "m0637"
    assert data["page"] == page

    # 新建会话会完成旧 active,避免累计多个 active
    with db.session() as s:
        review.complete_all_active(s)
        s.commit()
    done = client.get(f"/api/v1/review/sessions/{session_id}").json()["data"]
    assert done["status"] == "completed"


def test_favorites_api_flow(app, client) -> None:
    _seed_media(app, "fav1")
    resp = client.post("/api/v1/favorites/fav1")
    assert resp.json()["data"]["favorited"] is True
    listed = client.get("/api/v1/favorites").json()["data"]
    assert any(item["media_id"] == "fav1" for item in listed)
    removed = client.delete("/api/v1/favorites/fav1").json()["data"]
    assert removed["favorited"] is False
    assert client.get("/api/v1/favorites").json()["data"] == []


def test_delete_queue_api_deletes_real_file(app, client, tmp_path) -> None:
    real_file: Path = tmp_path / "victim.mp4"
    real_file.write_bytes(b"to-be-deleted")
    _seed_media(app, "del1", media_path=str(real_file))

    queued = client.post("/api/v1/delete-queue/del1").json()["data"]
    assert queued["queued"] is True
    assert len(client.get("/api/v1/delete-queue").json()["data"]) == 1

    prep = client.post("/api/v1/delete-queue/commit/prepare").json()["data"]
    committed = client.post("/api/v1/delete-queue/commit", json={"nonce": prep["nonce"]}).json()[
        "data"
    ]
    assert committed["outcome"] == {"del1": "success"}
    assert not real_file.exists()
    assert client.get("/api/v1/delete-queue").json()["data"] == []


def test_sprite_ready_manifest_and_file(app, client, tmp_path) -> None:
    _seed_media(app, "sp1")
    sprites_dir: Path = tmp_path / "MediaReviewData" / "cache" / "sprites"
    sprites_dir.mkdir(parents=True, exist_ok=True)
    (sprites_dir / "sp1.jpg").write_bytes(b"JPEGDATA")
    db: Database = app.state.database
    with db.session() as s:
        s.add(
            SpriteManifest(
                media_id="sp1",
                status="ready",
                columns=10,
                rows=1,
                count=10,
                tile_width=320,
                tile_height=180,
                interval_ms=2000,
                total_duration_ms=20000,
                sprite_file="sp1.jpg",
                fingerprint="fp-sp1",
            )
        )
        s.commit()

    ensure_resp = client.post("/api/v1/cache/sprites/sp1")
    assert ensure_resp.status_code == 202, ensure_resp.text
    resp = ensure_resp.json()["data"]
    assert resp["status"] == "ready"
    assert resp["url"].endswith("/api/v1/cache/sprites/sp1/file")

    file_resp = client.get("/api/v1/cache/sprites/sp1/file")
    assert file_resp.status_code == 200
    assert file_resp.content == b"JPEGDATA"

    stats = client.get("/api/v1/cache/statistics").json()["data"]
    assert stats["sprite_count"] >= 1

    invalidated = client.delete("/api/v1/cache/sprites/sp1").json()["data"]
    assert invalidated["removed"] is True


def test_sprite_ensures_task_when_missing(app, client) -> None:
    _seed_media(app, "sp2")
    resp = client.post("/api/v1/cache/sprites/sp2").json()["data"]
    assert resp["task_id"]
    assert resp["status"] == "pending"


def test_unknown_media_errors(app, client) -> None:
    assert client.get("/api/v1/cache/sprites/ghost").status_code == 404
    assert client.post("/api/v1/favorites/ghost").status_code == 404
    assert client.post("/api/v1/delete-queue/ghost").status_code == 404
    assert client.get("/api/v1/review/sessions/latest").status_code == 404
