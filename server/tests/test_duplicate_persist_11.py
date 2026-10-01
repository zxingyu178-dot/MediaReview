"""Task D: 重复分组持久化 + 后台扫描任务(进度/暂停/取消/恢复) + 疑似分辨率判定。

合同:
- ``schedule_duplicate_scan`` 幂等编排 ``duplicate_scan`` 后台任务;
- ``run_duplicate_scan(database, task_id)`` 计算分组并写入 duplicate_group/member,
  支持进度与协作取消/暂停;
- ``persisted_groups`` 从 DB 读取最近扫描结果(含 keep);``set_keep`` 记录人工保留;
- 疑似(similar)判定加入分辨率差异(时长/大小/分辨率);
- 任务 pause/resume 状态转换。
"""

from __future__ import annotations

from pathlib import Path

from app.db.models import BackgroundTask, LibrarySelection, MediaCacheIndex
from app.db.session import Database
from app.services import duplicate_scanner
from app.services import tasks as task_service


def _make_db(data_root: Path) -> Database:
    db = Database(data_root / "database" / "mediareview.db")
    db.create_all()
    return db


def _seed_media(
    db: Database,
    media_id: str,
    *,
    size: int,
    duration: int,
    quick_hash: str | None,
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
                width=width,
                height=height,
            )
        )
        s.commit()


def _seed_exact_pair(db: Database) -> None:
    """两文件大小/时长/sha256 一致 -> exact 分组。"""
    sha = "a" * 64
    for mid in ("aaa" * 8, "bbb" * 8):
        _seed_media(
            db,
            mid,
            size=1000,
            duration=60_000,
            quick_hash="q" * 64,
            sha256=sha,
        )


def test_schedule_duplicate_scan_is_idempotent(tmp_path: Path) -> None:
    db = _make_db(tmp_path)
    with db.session() as s:
        t1 = duplicate_scanner.schedule_duplicate_scan(s)
        t2 = duplicate_scanner.schedule_duplicate_scan(s)
        s.commit()
        assert t1.task_id == t2.task_id
    db.dispose()


def test_run_duplicate_scan_persists_exact_groups(tmp_path: Path) -> None:
    db = _make_db(tmp_path)
    _seed_exact_pair(db)
    with db.session() as s:
        task = duplicate_scanner.schedule_duplicate_scan(s)
        s.commit()
        task_id = task.task_id
    with db.session() as s:
        s.get(BackgroundTask, task_id).status = "running"
        s.commit()

    duplicate_scanner.run_duplicate_scan(db, task_id)

    with db.session() as s:
        groups = duplicate_scanner.persisted_groups(s, "exact")
        status = s.get(BackgroundTask, task_id).status
    assert status == "succeeded"
    assert len(groups) == 1
    assert set(groups[0].media_ids) == {"aaa" * 8, "bbb" * 8}
    assert groups[0].count == 2
    db.dispose()


def test_run_duplicate_scan_clears_previous_groups(tmp_path: Path) -> None:
    """重跑扫描会覆盖旧分组(保持最近一次扫描一致)。"""
    db = _make_db(tmp_path)
    _seed_exact_pair(db)
    with db.session() as s:
        t1 = duplicate_scanner.schedule_duplicate_scan(s)
        s.commit()
        s.get(BackgroundTask, t1.task_id).status = "running"
        s.commit()
    duplicate_scanner.run_duplicate_scan(db, t1.task_id)

    # 第二次扫描前清空哈希,重跑后旧 exact 分组应消失
    with db.session() as s:
        from app.db.models import MediaCacheIndex as M

        s.query(M).update({"sha256": None})
        t2 = duplicate_scanner.schedule_duplicate_scan(s)
        s.commit()
        s.get(BackgroundTask, t2.task_id).status = "running"
        s.commit()
    duplicate_scanner.run_duplicate_scan(db, t2.task_id)

    with db.session() as s:
        groups = duplicate_scanner.persisted_groups(s, "exact")
    assert groups == []
    db.dispose()


def test_set_keep_updates_persisted_keep(tmp_path: Path) -> None:
    db = _make_db(tmp_path)
    _seed_exact_pair(db)
    with db.session() as s:
        task = duplicate_scanner.schedule_duplicate_scan(s)
        s.commit()
        s.get(BackgroundTask, task.task_id).status = "running"
        s.commit()
    duplicate_scanner.run_duplicate_scan(db, task.task_id)

    with db.session() as s:
        groups = duplicate_scanner.persisted_groups(s, "exact")
        group_id = groups[0].group_id
        mid = groups[0].media_ids[0]
        assert duplicate_scanner.set_keep(s, group_id, mid, True) is True
        assert duplicate_scanner.set_keep(s, "no-such-group", mid, True) is False
        s.commit()
    with db.session() as s:
        groups = duplicate_scanner.persisted_groups(s, "exact")
        assert groups[0].keep[mid] is True
    db.dispose()


def test_similar_detects_resolution_difference(tmp_path: Path) -> None:
    """同大小但分辨率不同 -> similar(疑似);时长相近时不因分辨率判为 exact。"""
    db = _make_db(tmp_path)
    _seed_media(db, "aaa" * 8, size=2000, duration=60_000, quick_hash=None, width=1920, height=1080)
    _seed_media(db, "bbb" * 8, size=2000, duration=60_000, quick_hash=None, width=1280, height=720)
    with db.session() as s:
        groups = duplicate_scanner.scan_similar_candidates(s)
    assert len(groups) == 1
    assert groups[0].type == "similar"
    db.dispose()


def test_task_pause_and_resume(tmp_path: Path) -> None:
    db = _make_db(tmp_path)
    with db.session() as s:
        t = duplicate_scanner.schedule_duplicate_scan(s)
        s.get(BackgroundTask, t.task_id).status = "running"
        s.commit()
        task = s.get(BackgroundTask, t.task_id)
        task_service.pause_task(s, task)
        assert task.status == "paused"
        task_service.resume_task(s, task)
        assert task.status == "pending"
        s.commit()
    db.dispose()


def test_duplicates_api_scan_status_and_exact(client) -> None:
    """API: POST /duplicates/scan 编排任务;GET /duplicates/status 读状态;GET /duplicates/exact 读持久化。"""
    db = client.app.state.database
    _seed_exact_pair(db)

    r = client.post("/api/v1/duplicates/scan")
    assert r.status_code == 200, r.text
    task_id = r.json()["data"]["task_id"]

    st = client.get("/api/v1/duplicates/status")
    assert st.status_code == 200
    assert st.json()["data"]["task_id"] == task_id

    # 尚未运行 -> 无持久化分组(分页合同: items 空 / total 0)
    empty = client.get("/api/v1/duplicates/exact").json()["data"]
    assert empty["items"] == []
    assert empty["total"] == 0
