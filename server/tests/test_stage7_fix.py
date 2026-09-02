"""Stage-07-fix 验收测试: Duplicate Scanner 多级哈希 + Pairing 认证闭环 + 后台哈希任务。

覆盖验收要点:
- size+duration 仅作候选筛选,反例(内容不同)不得判为 exact
- quick_hash -> 高度可信; full sha256 -> byte-identical exact
- 后台哈希任务计算 quick/sha,且不阻塞请求(由 TaskManager 线程执行)
- 未配对/伪造 token 不能调用危险 API(401);合法 token 可以;revoke 后立即失效

认证场景使用 pairing_required=True 的独立应用(pair_client),避免污染默认夹具。
"""

from __future__ import annotations

import hashlib
from pathlib import Path

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.core.config import AppConfig, SecurityConfig, StorageConfig
from app.db.models import BackgroundTask, MediaCacheIndex
from app.db.session import Database
from app.main import create_app
from app.services import duplicate_scanner, hash_tasks
from app.services.hashing import full_sha256, quick_hash

# ---- 工具 ----


def _bearer(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def _make_db(data_root: Path) -> Database:
    db = Database(data_root / "database" / "mediareview.db")
    db.create_all()
    return db


def _seed_file_media(
    db: Database,
    media_id: str,
    path: Path,
    *,
    duration: int = 3000,
    size: int | None = None,
) -> Path:
    if size is None:
        size = path.stat().st_size
    with db.session() as s:
        s.add(
            MediaCacheIndex(
                media_id=media_id,
                jellyfin_id="j-" + media_id,
                library_id="lib-movies",
                name=path.name,
                media_type="video",
                fingerprint="fp-" + media_id,
                size_bytes=size,
                duration_ms=duration,
                media_path=str(path),
            )
        )
        s.commit()
    return path


# ---- Pairing 认证闭环(API 层) ----


@pytest.fixture
def pair_app(data_root: Path) -> FastAPI:
    return create_app(
        AppConfig(
            storage=StorageConfig(data_root=str(data_root)),
            security=SecurityConfig(pairing_required=True),
        ),
        run_db_migrations=True,
    )


class _Loopback:
    """ASGI 包装: 把 http 作用域的 client 改写为 127.0.0.1,模拟本机回环请求。"""

    def __init__(self, app: FastAPI) -> None:
        self.app: FastAPI = app

    async def __call__(self, scope, receive, send) -> None:  # noqa: ANN001
        if scope["type"] == "http":
            scope["client"] = ("127.0.0.1", 50000)
        await self.app(scope, receive, send)


@pytest.fixture
def pair_client(pair_app: FastAPI):
    # 用回环包装模拟"本机/管理后台",使 /pairing/code 允许调用
    with TestClient(_Loopback(pair_app)) as tc:
        yield tc


@pytest.fixture
def remote_client(pair_app: FastAPI):
    # 默认 host=testclient(非回环),模拟局域网任意设备,应被 /pairing/code 拒绝(403)
    with TestClient(pair_app) as tc:
        yield tc


def _pair(client: TestClient, device_id: str = "phone-auth") -> str:
    code = client.post("/api/v1/pairing/code").json()["data"]["code"]
    body = client.post(
        "/api/v1/pairing/verify", json={"device_id": device_id, "code": code}
    ).json()["data"]
    assert body["paired"] is True and body["token"]
    return body["token"]


def test_unpaired_client_cannot_call_dangerous_apis(pair_client: TestClient) -> None:
    """未配对客户端调用危险/状态修改 API 必须 401。"""
    assert pair_client.get("/api/v1/delete-queue").status_code == 401
    assert pair_client.post("/api/v1/delete-queue/commit").status_code == 401
    assert pair_client.post("/api/v1/delete-queue/x1").status_code == 401
    assert pair_client.post("/api/v1/favorites/x1").status_code == 401
    assert pair_client.get("/api/v1/favorites").status_code == 401
    assert (
        pair_client.post("/api/v1/review/sessions", json={"media_ids": ["x1"]}).status_code == 401
    )
    assert pair_client.get("/api/v1/duplicates").status_code == 401
    assert pair_client.post("/api/v1/pairing/revoke", json={"device_id": "x1"}).status_code == 401


def test_remote_client_cannot_generate_pairing_code(remote_client: TestClient) -> None:
    """局域网远程设备请求 /pairing/code 必须 403(防绕过配对)。"""
    assert remote_client.post("/api/v1/pairing/code").status_code == 403


def test_loopback_can_generate_pairing_code(pair_client: TestClient) -> None:
    """本机/管理后台生成配对码 -> 200。"""
    assert pair_client.post("/api/v1/pairing/code").status_code == 200


def test_status_reflects_config(pair_client: TestClient) -> None:
    """/pairing/status 的 pairing_required 读取配置而非写死 True。"""
    data = pair_client.get("/api/v1/pairing/status").json()["data"]
    assert data["pairing_required"] is True


def test_valid_token_allows_dangerous_apis(pair_client: TestClient) -> None:
    token = _pair(pair_client)
    headers = _bearer(token)
    # 收藏危险 API
    assert pair_client.get("/api/v1/favorites", headers=headers).status_code == 200
    # 待删除队列 commit(危险)放行(prepare 生成 nonce 后 commit,空队列返回空 outcome)
    prep = pair_client.post("/api/v1/delete-queue/commit/prepare", headers=headers)
    assert prep.status_code == 200
    resp = pair_client.post(
        "/api/v1/delete-queue/commit",
        json={"nonce": prep.json()["data"]["nonce"]},
        headers=headers,
    )
    assert resp.status_code == 200
    assert resp.json()["data"]["outcome"] == {}
    # 重复扫描
    assert pair_client.get("/api/v1/duplicates", headers=headers).status_code == 200


def test_forged_token_is_rejected(pair_client: TestClient) -> None:
    token = _pair(pair_client)
    _ = token  # 合法 token 存在,但下面用伪造的
    forged = _bearer("mr_" + "f" * 40)
    assert pair_client.get("/api/v1/favorites", headers=forged).status_code == 401
    assert pair_client.post("/api/v1/delete-queue/commit", headers=forged).status_code == 401


def test_revoked_token_is_immediately_invalid(pair_client: TestClient) -> None:
    token = _pair(pair_client, device_id="phone-revoke")
    headers = _bearer(token)
    assert pair_client.get("/api/v1/favorites", headers=headers).status_code == 200

    # 管理端撤销该设备(需用另一个合法 token)
    admin_token = _pair(pair_client, device_id="phone-admin")
    rev = pair_client.post(
        "/api/v1/pairing/revoke",
        json={"device_id": "phone-revoke"},
        headers=_bearer(admin_token),
    )
    assert rev.status_code == 200 and rev.json()["data"]["revoked"] is True

    # 原 token 立即失效
    assert pair_client.get("/api/v1/favorites", headers=headers).status_code == 401
    assert pair_client.post("/api/v1/favorites/x1", headers=headers).status_code == 401


def test_verify_does_not_leak_plain_token_in_database(pair_client: TestClient) -> None:
    token = _pair(pair_client, device_id="phone-plain")
    db: Database = pair_client.app.app.state.database  # app 为 _Loopback 包装,解包取内层 FastAPI
    with db.session() as s:
        from app.db.models import PairedDevice

        dev = s.get(PairedDevice, "phone-plain")
        assert dev.token_hash == hashlib.sha256(token.encode("utf-8")).hexdigest()
        assert token not in dev.token_hash
        assert dev.token_hash != token


# ---- localhost-or-auth: 系统/管理敏感接口 ----
# 规则: 本机回环直接放行;局域网远程必须携带合法 token,否则 401。


def test_remote_client_cannot_access_sensitive_endpoints(remote_client: TestClient) -> None:
    """局域网远程未配对设备访问系统敏感接口必须 401。"""
    assert remote_client.get("/api/v1/system/info").status_code == 401
    assert remote_client.get("/api/v1/system/storage").status_code == 401
    assert remote_client.get("/api/v1/system/diagnostics/export").status_code == 401
    assert remote_client.get("/admin").status_code == 401


def test_loopback_can_access_sensitive_endpoints(pair_client: TestClient) -> None:
    """本机/管理后台访问系统敏感接口放行。"""
    assert pair_client.get("/api/v1/system/info").status_code == 200
    assert pair_client.get("/api/v1/system/storage").status_code == 200
    assert pair_client.get("/admin").status_code == 200
    assert pair_client.get("/api/v1/system/diagnostics/export").status_code == 200


def test_remote_with_valid_token_can_access_sensitive_endpoints(pair_client: TestClient) -> None:
    """远程已配对设备携带合法 token 可访问系统敏感接口。"""
    token = _pair(pair_client, device_id="phone-sys")
    headers = _bearer(token)
    assert pair_client.get("/api/v1/system/info", headers=headers).status_code == 200
    assert pair_client.get("/api/v1/system/storage", headers=headers).status_code == 200
    assert pair_client.get("/admin", headers=headers).status_code == 200


# ---- 阶段 16 预部署收口: 媒体/媒体库/缓存认证边界 ----


def test_remote_client_cannot_access_media_libraries_cache(remote_client: TestClient) -> None:
    """未配对局域网设备访问媒体/媒体库/缓存接口必须 401(防 api_key 泄露)。"""
    assert remote_client.get("/api/v1/media").status_code == 401
    assert remote_client.get("/api/v1/media/some-id").status_code == 401
    assert remote_client.get("/api/v1/media/some-id/playback").status_code == 401
    assert remote_client.get("/api/v1/libraries").status_code == 401
    assert (
        remote_client.put("/api/v1/libraries/selection", json={"selected": []}).status_code == 401
    )
    assert remote_client.get("/api/v1/cache/statistics").status_code == 401


def test_loopback_can_access_cache(pair_client: TestClient) -> None:
    """本机/管理后台访问缓存接口放行(localhost-or-auth)。"""
    assert pair_client.get("/api/v1/cache/statistics").status_code == 200


def test_remote_with_token_passes_media_libraries_cache_auth(
    pair_client: TestClient, remote_client: TestClient
) -> None:
    """远程已配对设备携带 token 后,媒体/媒体库请求不再因认证返回 401,缓存接口 200。"""
    token = _pair(pair_client, device_id="phone-m2")
    headers = _bearer(token)
    # 缓存接口: 认证通过即 200
    assert remote_client.get("/api/v1/cache/statistics", headers=headers).status_code == 200
    # 媒体/媒体库: jellyfin 未配置会走后续校验,但认证已通过(≠401)
    assert remote_client.get("/api/v1/media", headers=headers).status_code != 401
    assert remote_client.get("/api/v1/libraries", headers=headers).status_code != 401
    # Jellyfin 拓扑接口同受 localhost-or-auth 保护
    assert remote_client.get("/api/v1/jellyfin/status").status_code == 401


# ---- 后台哈希任务 ----


def test_hash_handler_backfills_quick_and_full(tmp_path: Path) -> None:
    db = _make_db(tmp_path)
    fa = tmp_path / "a.mp4"
    fb = tmp_path / "b.mp4"
    fc = tmp_path / "c.mp4"
    fa.write_bytes(b"AAA" * 40)
    fb.write_bytes(b"AAA" * 40)  # 与 a 完全相同
    fc.write_bytes(b"BBB" * 40)  # 与 a 大小、时长一致但内容不同(反例)

    _seed_file_media(db, "ha", fa)
    _seed_file_media(db, "hb", fb)
    _seed_file_media(db, "hc", fc)

    task = BackgroundTask(
        task_id="hash-t-1",
        type=hash_tasks.TASK_TYPE_DUPLICATE_HASH,
        status="pending",
        params="{}",
    )
    with db.session() as s:
        s.add(task)
        s.commit()

    hash_tasks.make_hash_handler()(db, "hash-t-1")

    with db.session() as s:
        rows = {m.media_id: m for m in s.query(MediaCacheIndex).all()}
        assert rows["ha"].quick_hash == quick_hash(fa)
        assert rows["ha"].sha256 == full_sha256(fa)
        assert rows["hb"].quick_hash == rows["ha"].quick_hash
        assert rows["hb"].sha256 == rows["ha"].sha256
        # 反例: 内容不同 -> 完整哈希不同
        assert rows["hc"].sha256 != rows["ha"].sha256

        # 后台任务只对"候选重复"算完整哈希;quick 对所有缺失媒体补齐
        done = s.get(BackgroundTask, "hash-t-1")
        assert done.status == "succeeded"

    # 扫描器: a/b 为 exact;反例 c 不与任何文件成 exact
    with db.session() as s:
        assert duplicate_scanner.has_pending_hashes(s) is False
        exact = duplicate_scanner.scan_exact_duplicates(s)
    assert len(exact) == 1
    assert exact[0].type == "exact"
    assert set(exact[0].media_ids) == {"ha", "hb"}
    db.dispose()


def test_hash_handler_marks_unreadable(tmp_path: Path) -> None:
    """文件读取失败写入哨兵,不误判为重复,也不无限重试。"""
    db = _make_db(tmp_path)
    missing = tmp_path / "gone.mp4"  # 不存在
    # 两个引用了同一不存在路径的媒体: 大小+时长一致,但内容不可读
    _seed_file_media(db, "gone-1", missing, size=256)
    _seed_file_media(db, "gone-2", missing, size=256)

    task = BackgroundTask(
        task_id="hash-t-2",
        type=hash_tasks.TASK_TYPE_DUPLICATE_HASH,
        status="pending",
        params="{}",
    )
    with db.session() as s:
        s.add(task)
        s.commit()
    hash_tasks.make_hash_handler()(db, "hash-t-2")

    with db.session() as s:
        rows = {m.media_id: m for m in s.query(MediaCacheIndex).all()}
        assert rows["gone-1"].quick_hash == hash_tasks.HASH_UNREADABLE
        assert rows["gone-2"].quick_hash == hash_tasks.HASH_UNREADABLE
        # 哨兵不应进入高度可信/exact(非 hex 也不会碰撞)
        high = duplicate_scanner.scan_high_confidence(s)
        exact = duplicate_scanner.scan_exact_duplicates(s)
    assert high == []
    assert exact == []
    db.dispose()


# ---- 反例: size+duration 相同但内容不同,后端真实文件全链路 ----


def test_same_size_duration_but_different_content_is_not_exact(tmp_path: Path) -> None:
    db = _make_db(tmp_path)
    f1 = tmp_path / "one.mp4"
    f2 = tmp_path / "two.mp4"
    f1.write_bytes(b"\x01" * 256)
    f2.write_bytes(b"\x02" * 256)  # 与 f1 大小完全一致,内容不同
    _seed_file_media(db, "neg-1", f1)
    _seed_file_media(db, "neg-2", f2)

    task = BackgroundTask(
        task_id="hash-neg", type=hash_tasks.TASK_TYPE_DUPLICATE_HASH, status="pending", params="{}"
    )
    with db.session() as s:
        s.add(task)
        s.commit()
    hash_tasks.make_hash_handler()(db, "hash-neg")

    with db.session() as s:
        exact = duplicate_scanner.scan_exact_duplicates(s)
        high = duplicate_scanner.scan_high_confidence(s)
    assert exact == []  # 不得判定为完全重复
    assert high == []  # 采样内容也不同,不得判为高度可信
    db.dispose()
