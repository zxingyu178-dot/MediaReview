"""Stage 8C: 整理中心(Organize)服务端合同。

覆盖任务书 §51 / §52 的服务端部分:
- GET /delete-queue/summary 只统计 pending(count/total_bytes),不返回列表;
- GET /delete-queue 队列项带 cover_url/original_url(与 /media 缓存版本语义一致);
- 最终删除 success / missing / failed 三种结果与队列状态;
- GET /duplicates/summary 计数 + 最近扫描任务状态;
- GET /duplicates/{group_id} 分组详情(成员媒体摘要 + cover_url,未知分组 404);
- keep true/false 通过详情可见。
"""

from __future__ import annotations

from pathlib import Path

from conftest import client as _client_fixture  # noqa: F401  (仅确保夹具可导入)

from app.db.models import BackgroundTask, LibrarySelection, MediaCacheIndex
from app.db.session import Database
from app.services import delete_queue, duplicate_scanner


def _seed_media(
    db: Database,
    media_id: str,
    media_path: str,
    *,
    size: int | None = None,
    media_type: str = "video",
    duration_ms: int | None = None,
    quick_hash: str | None = None,
    sha256: str | None = None,
    width: int | None = None,
    height: int | None = None,
) -> None:
    with db.session() as s:
        s.merge(
            LibrarySelection(
                jellyfin_id="lib-movies", name="电影", collection_type="movies", selected=True
            )
        )
        s.commit()
    if size is None:
        p = Path(media_path)
        size = p.stat().st_size if p.is_file() else 0
    with db.session() as s:
        s.add(
            MediaCacheIndex(
                media_id=media_id,
                jellyfin_id="j-" + media_id,
                library_id="lib-movies",
                name=media_id + (".jpg" if media_type == "image" else ".mp4"),
                media_type=media_type,
                fingerprint="fp-" + media_id,
                size_bytes=size,
                media_path=media_path,
                duration_ms=duration_ms,
                quick_hash=quick_hash,
                sha256=sha256,
                width=width,
                height=height,
            )
        )
        s.commit()


def _enqueue(db: Database, media_id: str, size: int) -> None:
    with db.session() as s:
        assert delete_queue.enqueue(s, media_id, size_bytes=size) is True
        s.commit()


# ---- §51 Delete Queue ----


def test_delete_queue_summary_counts_pending_only(tmp_path: Path, client) -> None:
    """摘要只统计 pending: failed 项(删除失败留在队列)不计入预计释放。"""
    db = client.app.state.database
    ok_file = tmp_path / "ok.mp4"
    ok_file.write_bytes(b"12345")
    _seed_media(db, "aaa" * 8, str(ok_file), size=5)
    _enqueue(db, "aaa" * 8, 5)
    # 一条 failed(文件大小与索引不一致 -> guard 拒绝)
    bad_file = tmp_path / "bad.mp4"
    bad_file.write_bytes(b"123")
    _seed_media(db, "bbb" * 8, str(bad_file), size=999)
    _enqueue(db, "bbb" * 8, 999)

    r = client.get("/api/v1/delete-queue/summary")
    assert r.status_code == 200, r.text
    assert r.json()["data"] == {"count": 2, "total_bytes": 1004}

    prep = client.post("/api/v1/delete-queue/commit/prepare").json()["data"]
    commit = client.post("/api/v1/delete-queue/commit", json={"nonce": prep["nonce"]})
    assert commit.status_code == 200, commit.text
    assert commit.json()["data"]["outcome"]["aaa" * 8] == "success"
    assert commit.json()["data"]["outcome"]["bbb" * 8] == "failed"

    # 成功后: pending 只剩 failed 的 0 条(不计入) —— 摘要与列表口径一致
    summary = client.get("/api/v1/delete-queue/summary").json()["data"]
    assert summary == {"count": 0, "total_bytes": 0}
    rows = client.get("/api/v1/delete-queue").json()["data"]
    assert [row["status"] for row in rows] == ["failed"]


def test_delete_queue_list_includes_cover_url(tmp_path: Path, client) -> None:
    """队列项媒体摘要必须带 cover_url(与 /media 相同的带版本号语义);图片带 original_url。"""
    db = client.app.state.database
    video = tmp_path / "v.mp4"
    video.write_bytes(b"vv")
    photo = tmp_path / "p.jpg"
    photo.write_bytes(b"ppp")
    _seed_media(db, "vvv" * 8, str(video), size=2)
    _seed_media(db, "ppp" * 8, str(photo), size=3, media_type="image")
    _enqueue(db, "vvv" * 8, 2)
    _enqueue(db, "ppp" * 8, 3)

    rows = client.get("/api/v1/delete-queue").json()["data"]
    by_id = {row["media_id"]: row for row in rows}
    video_media = by_id["vvv" * 8]["media"]
    photo_media = by_id["ppp" * 8]["media"]
    assert video_media["cover_url"].startswith(f"/api/v1/media/{'vvv' * 8}/thumbnail?v=")
    assert video_media["original_url"] is None
    assert photo_media["original_url"] == f"/api/v1/media/{'ppp' * 8}/original"

    # 与 GET /media/{id} 的封面版本完全一致(同一 source_version 派生)
    detail = client.get(f"/api/v1/media/{'vvv' * 8}").json()["data"]
    assert detail["cover_url"] == video_media["cover_url"]


def test_delete_queue_commit_missing_file(tmp_path: Path, client) -> None:
    """文件已不存在 -> missing,且索引/队列被清理,不误报 success。"""
    db = client.app.state.database
    _seed_media(db, "mmm" * 8, str(tmp_path / "gone.mp4"), size=7)
    _enqueue(db, "mmm" * 8, 7)

    prep = client.post("/api/v1/delete-queue/commit/prepare").json()["data"]
    r = client.post("/api/v1/delete-queue/commit", json={"nonce": prep["nonce"]})
    assert r.json()["data"]["outcome"]["mmm" * 8] == "missing"
    assert client.get("/api/v1/delete-queue").json()["data"] == []
    assert client.get(f"/api/v1/media/{'mmm' * 8}").status_code == 404


# ---- §52 Duplicates ----


def _run_scan(db: Database) -> str:
    """同步执行一次重复扫描(确定性,不依赖后台调度器)。"""
    with db.session() as s:
        task = duplicate_scanner.schedule_duplicate_scan(s)
        s.commit()
        task_id = task.task_id
    with db.session() as s:
        s.get(BackgroundTask, task_id).status = "running"
        s.commit()
    duplicate_scanner.run_duplicate_scan(db, task_id)
    return task_id


def _seed_exact_pair(db: Database) -> None:
    sha = "a" * 64
    for mid in ("aaa" * 8, "bbb" * 8):
        _seed_media(
            db,
            mid,
            "",
            size=1000,
            duration_ms=60_000,
            quick_hash="q" * 64,
            sha256=sha,
            width=1920,
            height=1080,
        )


def test_duplicates_summary_counts_and_scan_status(tmp_path: Path, client) -> None:
    """摘要: 完全/疑似计数来自持久化分组;扫描任务状态/进度随最近任务。"""
    db = client.app.state.database
    _seed_exact_pair(db)

    before = client.get("/api/v1/duplicates/summary").json()["data"]
    assert before["exact_groups"] == 0
    assert before["similar_groups"] == 0

    task_id = _run_scan(db)
    data = client.get("/api/v1/duplicates/summary").json()["data"]
    assert data["exact_groups"] == 1
    assert data["similar_groups"] == 0
    assert data["scan_task_id"] == task_id
    assert data["scan_status"] == "succeeded"
    assert data["scan_progress"] == 100


def test_duplicate_group_detail_members_and_cover(tmp_path: Path, client) -> None:
    """分组详情: 一次请求返回成员媒体摘要(size/duration/分辨率/封面),未知分组 404。"""
    db = client.app.state.database
    _seed_exact_pair(db)
    _run_scan(db)

    groups = client.get("/api/v1/duplicates/exact").json()["data"]
    group_id = groups[0]["group_id"]
    r = client.get(f"/api/v1/duplicates/{group_id}")
    assert r.status_code == 200, r.text
    detail = r.json()["data"]
    assert detail["type"] == "exact"
    assert detail["count"] == 2
    assert detail["size_bytes"] == 1000
    assert len(detail["members"]) == 2
    member = detail["members"][0]
    assert member["media_id"] in {"aaa" * 8, "bbb" * 8}
    assert member["keep"] is False
    assert member["size_bytes"] == 1000
    assert member["duration_ms"] == 60_000
    assert member["width"] == 1920 and member["height"] == 1080
    assert member["cover_url"].startswith(f"/api/v1/media/{member['media_id']}/thumbnail?v=")

    unknown = client.get("/api/v1/duplicates/no-such-group")
    assert unknown.status_code == 404


def test_duplicate_keep_true_false_reflected_in_detail(tmp_path: Path, client) -> None:
    db = client.app.state.database
    _seed_exact_pair(db)
    _run_scan(db)
    groups = client.get("/api/v1/duplicates/exact").json()["data"]
    group_id = groups[0]["group_id"]
    media_id = groups[0]["media_ids"][0]

    r = client.post(
        f"/api/v1/duplicates/{group_id}/keep", json={"media_id": media_id, "keep": True}
    )
    assert r.status_code == 200, r.text
    kept = client.get(f"/api/v1/duplicates/{group_id}").json()["data"]["members"]
    assert [m["keep"] for m in kept if m["media_id"] == media_id] == [True]

    client.post(f"/api/v1/duplicates/{group_id}/keep", json={"media_id": media_id, "keep": False})
    released = client.get(f"/api/v1/duplicates/{group_id}").json()["data"]["members"]
    assert [m["keep"] for m in released if m["media_id"] == media_id] == [False]