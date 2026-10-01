"""Stage 8C.1: 重复扫描正确性 / 原子替换 / 分页合同(服务端)。

覆盖任务书 §4~§20 与 §28~§33 的服务端部分:

- eligible scope: 只扫描「已勾选媒体库 + 仍可用」媒体;未勾选库/失效媒体绝不进入结果;
- 0 个已选媒体库 -> 明确空结果(绝不偷偷扫描全库);
- 检测阶段不再有 200 组截断: >200 组 exact / similar 必须全部发现;
- 扫描结果**原子替换**: pause / cancel / failed 保留上一份 succeeded 结果,
  计算期间旧结果一直可读,只有完整 succeeded 才一次性替换;
- GET /duplicates/exact|similar 分页合同(items/total/page/page_size);
- /duplicates/summary 失败重扫后保留上次成功计数 + last_successful_scan_at;
- 分组详情 available 标记 stale media(§20);
- /delete-queue failed 项下发 error 原因(§23)。
"""

from __future__ import annotations

import hashlib
from pathlib import Path

from app.db.models import BackgroundTask, DeleteQueue, LibrarySelection, MediaCacheIndex
from app.db.session import Database
from app.services import delete_queue, duplicate_scanner
from app.services import duplicate_scanner as ds


def _make_db(data_root: Path) -> Database:
    db = Database(data_root / "database" / "mediareview.db")
    db.create_all()
    return db


def _seed_library(db: Database, library_id: str = "lib-movies", *, selected: bool = True) -> None:
    with db.session() as s:
        s.merge(
            LibrarySelection(
                jellyfin_id=library_id,
                name=library_id,
                collection_type="movies",
                selected=selected,
            )
        )
        s.commit()


def _seed_media(
    db: Database,
    media_id: str,
    *,
    library_id: str = "lib-movies",
    size: int,
    duration: int | None = 60_000,
    quick_hash: str | None = None,
    sha256: str | None = None,
    available: bool = True,
    width: int | None = 1920,
    height: int | None = 1080,
    media_path: str | None = None,
) -> None:
    with db.session() as s:
        s.add(
            MediaCacheIndex(
                media_id=media_id,
                jellyfin_id="j-" + media_id,
                library_id=library_id,
                name=media_id + ".mp4",
                media_type="video",
                fingerprint="fp-" + media_id,
                size_bytes=size,
                duration_ms=duration,
                quick_hash=quick_hash,
                sha256=sha256,
                is_available=available,
                width=width,
                height=height,
                media_path=media_path,
            )
        )
        s.commit()


def _sha(tag: str) -> str:
    """标签派生的 64 位十六进制 sha256(满足 is_full_sha256 合同)。"""
    return hashlib.sha256(tag.encode("utf-8")).hexdigest()


def _seed_exact_pair(db: Database, tag: str) -> None:
    sha = _sha(tag)
    for suffix in ("x", "y"):
        _seed_media(
            db,
            f"{tag}{suffix}",
            size=1000,
            duration=60_000,
            quick_hash="q" * 64,
            sha256=sha,
            media_path=f"D:\\Media\\{tag}{suffix}.mp4",
        )


def _start_scan(db: Database) -> str:
    with db.session() as s:
        task = duplicate_scanner.schedule_duplicate_scan(s)
        s.commit()
        task_id = task.task_id
    with db.session() as s:
        s.get(BackgroundTask, task_id).status = "running"
        s.commit()
    return task_id


def _run_scan(db: Database) -> str:
    task_id = _start_scan(db)
    duplicate_scanner.run_duplicate_scan(db, task_id)
    return task_id


def _persisted_ids(db: Database, group_type: str = "exact") -> set[str]:
    with db.session() as s:
        groups = duplicate_scanner.persisted_groups(s, group_type)
    return {g.group_id for g in groups}


# ---- §5~§7 eligible scope ----


def test_scan_scope_is_selected_and_available_only(tmp_path: Path) -> None:
    """只扫描已勾选且可用媒体: 未勾选库、失效媒体都不得进入新结果。"""
    db = _make_db(tmp_path)
    _seed_library(db, "lib-movies", selected=True)
    _seed_library(db, "lib-tv", selected=False)
    # 勾选库内一对正常重复
    _seed_exact_pair(db, "included")
    # 未勾选库内 sha/size/duration 完全一致的成员: 不得混入
    _seed_media(
        db, "tv-a", library_id="lib-tv", size=1000, duration=60_000,
        quick_hash="q" * 64, sha256=_sha("included"), media_path="D:\\Media\\tv-a.mp4",
    )
    _seed_media(
        db, "tv-b", library_id="lib-tv", size=1000, duration=60_000,
        quick_hash="q" * 64, sha256=_sha("included"), media_path="D:\\Media\\tv-b.mp4",
    )
    # 勾选库内失效媒体对: 不得混入
    _seed_media(
        db, "gone-a", size=2222, duration=60_000, quick_hash="q" * 64,
        sha256="f" * 64, available=False, media_path="D:\\Media\\gone-a.mp4",
    )
    _seed_media(
        db, "gone-b", size=2222, duration=60_000, quick_hash="q" * 64,
        sha256="f" * 64, available=False, media_path="D:\\Media\\gone-b.mp4",
    )

    with db.session() as s:
        groups = duplicate_scanner.scan_exact_duplicates(s)
    assert len(groups) == 1
    assert set(groups[0].media_ids) == {"includedx", "includedy"}
    db.dispose()


def test_scan_without_selected_library_is_explicitly_empty(tmp_path: Path) -> None:
    """0 个已选媒体库 -> 明确空结果,绝不扫描全库(且扫描任务正常 succeeded)。"""
    db = _make_db(tmp_path)
    _seed_library(db, "lib-movies", selected=False)
    _seed_exact_pair(db, "nomatch")

    with db.session() as s:
        assert duplicate_scanner.scan_all(s) == []
        assert duplicate_scanner.has_pending_hashes(s) is False

    task_id = _run_scan(db)
    with db.session() as s:
        status = s.get(BackgroundTask, task_id).status
        groups = duplicate_scanner.persisted_groups(s)
    assert status == "succeeded"
    assert groups == []
    db.dispose()


# ---- §8~§9 检测阶段禁止 200 组截断 ----


def test_more_than_200_exact_groups_are_all_found(tmp_path: Path) -> None:
    """>200 组完全重复必须全部发现(旧 _MAX_GROUPS=200 会丢掉第 201+ 组)。"""
    db = _make_db(tmp_path)
    _seed_library(db)
    for i in range(250):
        sha = f"{i:064d}"
        for suffix in ("x", "y"):
            _seed_media(
                db,
                f"p{i:03d}{suffix}",
                size=10_000 + i,
                duration=60_000,
                quick_hash="q" * 64,
                sha256=sha,
                media_path=f"D:\\Media\\p{i:03d}{suffix}.mp4",
            )

    task_id = _run_scan(db)
    with db.session() as s:
        status = s.get(BackgroundTask, task_id).status
        groups = duplicate_scanner.persisted_groups(s, "exact")
    assert status == "succeeded"
    assert len(groups) == 250
    db.dispose()


def test_more_than_200_similar_groups_are_all_found(tmp_path: Path) -> None:
    """>200 组疑似重复(同大小/时长差异)必须全部发现,不得静默截断。"""
    db = _make_db(tmp_path)
    _seed_library(db)
    for i in range(250):
        _seed_media(
            db, f"s{i:03d}a", size=20_000 + i, duration=1_000,
            quick_hash="q" * 64, media_path=f"D:\\Media\\s{i:03d}a.mp4",
        )
        _seed_media(
            db, f"s{i:03d}b", size=20_000 + i, duration=30_000,
            quick_hash="q" * 64, media_path=f"D:\\Media\\s{i:03d}b.mp4",
        )

    task_id = _run_scan(db)
    with db.session() as s:
        status = s.get(BackgroundTask, task_id).status
        groups = duplicate_scanner.persisted_groups(s, "similar")
    assert status == "succeeded"
    assert len(groups) == 250
    db.dispose()


# ---- §13~§17 原子替换: pause / cancel / failed 保留上一份结果 ----


def test_old_results_readable_while_new_scan_computes(tmp_path: Path, monkeypatch) -> None:
    """计算期间旧结果必须一直可读(不得先删旧组再慢慢算)。"""
    db = _make_db(tmp_path)
    _seed_library(db)
    _seed_exact_pair(db, "oldpair")
    _run_scan(db)
    old_ids = _persisted_ids(db)
    assert len(old_ids) == 1

    # 新增第二对,第二次扫描在最后一个阶段前"窥视"当前持久化结果
    _seed_exact_pair(db, "newpair")
    task_id = _start_scan(db)
    peeked: list[set[str]] = []
    original = ds.scan_similar_candidates

    def peek_then_scan(session, library_ids=None):
        with db.session() as s2:
            peeked.append({g.group_id for g in duplicate_scanner.persisted_groups(s2, "exact")})
        return original(session, library_ids)

    monkeypatch.setattr(ds, "scan_similar_candidates", peek_then_scan)
    duplicate_scanner.run_duplicate_scan(db, task_id)

    assert peeked and peeked[0] == old_ids, "计算期间旧结果必须保持可读"
    assert len(_persisted_ids(db)) == 2, "succeeded 后应替换为完整新结果"
    db.dispose()


def _pause_or_cancel_during_first_phase(
    tmp_path: Path, monkeypatch, status: str
) -> None:
    db = _make_db(tmp_path)
    _seed_library(db)
    _seed_exact_pair(db, "oldpair")
    _run_scan(db)
    old_ids = _persisted_ids(db)

    _seed_exact_pair(db, "newpair")
    task_id = _start_scan(db)
    original = ds.scan_exact_duplicates

    def stop_then_scan(session, library_ids=None):
        with db.session() as s:
            t = s.get(BackgroundTask, task_id)
            t.status = status
            s.commit()
        return original(session, library_ids)

    monkeypatch.setattr(ds, "scan_exact_duplicates", stop_then_scan)
    duplicate_scanner.run_duplicate_scan(db, task_id)

    with db.session() as s:
        task_status = s.get(BackgroundTask, task_id).status
    assert task_status == status, "外部协作状态不得被扫描任务覆盖"
    assert _persisted_ids(db) == old_ids, "pause/cancel 必须保留上一份成功结果"
    db.dispose()


def test_pause_during_scan_keeps_previous_results(tmp_path: Path, monkeypatch) -> None:
    _pause_or_cancel_during_first_phase(tmp_path, monkeypatch, "paused")


def test_cancel_during_scan_keeps_previous_results(tmp_path: Path, monkeypatch) -> None:
    _pause_or_cancel_during_first_phase(tmp_path, monkeypatch, "cancelled")


def test_failed_scan_keeps_previous_results(tmp_path: Path, monkeypatch) -> None:
    db = _make_db(tmp_path)
    _seed_library(db)
    _seed_exact_pair(db, "oldpair")
    _run_scan(db)
    old_ids = _persisted_ids(db)

    _seed_exact_pair(db, "newpair")
    task_id = _start_scan(db)
    original = ds.scan_similar_candidates

    def boom(session, library_ids=None):
        raise RuntimeError("scan exploded")

    monkeypatch.setattr(ds, "scan_similar_candidates", boom)
    duplicate_scanner.run_duplicate_scan(db, task_id)
    monkeypatch.setattr(ds, "scan_similar_candidates", original)

    with db.session() as s:
        task = s.get(BackgroundTask, task_id)
    assert task.status == "failed"
    assert _persisted_ids(db) == old_ids, "failed 必须保留上一份成功结果"
    db.dispose()


def test_succeeded_scan_atomically_replaces_previous_results(tmp_path: Path) -> None:
    """新扫描完整 succeeded 后一次性替换: 已消失的旧组不得残留。"""
    db = _make_db(tmp_path)
    _seed_library(db)
    _seed_exact_pair(db, "oldpair")
    _run_scan(db)
    assert len(_persisted_ids(db)) == 1

    # 旧媒体整对删除,新增另一对 -> 替换后只应剩新对
    with db.session() as s:
        s.query(MediaCacheIndex).filter(MediaCacheIndex.media_id.in_(["oldpairx", "oldpairy"])).delete(
            synchronize_session=False
        )
        s.commit()
    _seed_exact_pair(db, "newpair")
    _run_scan(db)

    with db.session() as s:
        groups = duplicate_scanner.persisted_groups(s, "exact")
    assert len(groups) == 1
    assert set(groups[0].media_ids) == {"newpairx", "newpairy"}
    db.dispose()


# ---- §11 分页合同 ----


def test_duplicates_pagination_contract(client) -> None:
    """GET /duplicates/exact|similar 返回 items/total/page/page_size,跨页不重不漏。"""
    db = client.app.state.database
    _seed_library(db)
    for i in range(60):
        _seed_exact_pair(db, f"pg{i:03d}")
    _run_scan(db)

    p1 = client.get("/api/v1/duplicates/exact?page=1&page_size=50").json()["data"]
    assert p1["total"] == 60
    assert p1["page"] == 1 and p1["page_size"] == 50
    assert len(p1["items"]) == 50

    p2 = client.get("/api/v1/duplicates/exact?page=2&page_size=50").json()["data"]
    assert len(p2["items"]) == 10
    ids1 = {item["group_id"] for item in p1["items"]}
    ids2 = {item["group_id"] for item in p2["items"]}
    assert not ids1 & ids2, "分页排序必须稳定,跨页不得重复"

    sim = client.get("/api/v1/duplicates/similar?page=1&page_size=50").json()["data"]
    assert sim["total"] == 0 and sim["items"] == []


def test_summary_keeps_last_success_after_failed_rescan(client, monkeypatch) -> None:
    """§18/§19: 重扫失败后计数保持上次成功值,并携带 last_successful_scan_at。"""
    db = client.app.state.database
    _seed_library(db)
    _seed_exact_pair(db, "oldpair")
    _run_scan(db)

    _seed_exact_pair(db, "newpair")
    task_id = _start_scan(db)
    original = ds.scan_similar_candidates

    def boom(session, library_ids=None):
        raise RuntimeError("scan exploded")

    monkeypatch.setattr(ds, "scan_similar_candidates", boom)
    duplicate_scanner.run_duplicate_scan(db, task_id)
    monkeypatch.setattr(ds, "scan_similar_candidates", original)

    data = client.get("/api/v1/duplicates/summary").json()["data"]
    assert data["exact_groups"] == 1, "失败重扫不得把上次成功结果清零"
    assert data["scan_status"] == "failed"
    assert data["last_successful_scan_at"] is not None


# ---- §20 stale media 详情标记 ----


def test_group_detail_marks_stale_member_unavailable(client) -> None:
    """扫描后失效的成员必须标记 available=false(不得展示假可用状态)。"""
    db = client.app.state.database
    _seed_library(db)
    _seed_exact_pair(db, "stale")
    _run_scan(db)

    with db.session() as s:
        s.query(MediaCacheIndex).filter(MediaCacheIndex.media_id == "stalex").update(
            {"is_available": False}, synchronize_session=False
        )
        s.commit()

    groups = client.get("/api/v1/duplicates/exact").json()["data"]["items"]
    group_id = groups[0]["group_id"]
    detail = client.get(f"/api/v1/duplicates/{group_id}").json()["data"]
    by_id = {m["media_id"]: m for m in detail["members"]}
    assert by_id["stalex"]["available"] is False
    assert by_id["staley"]["available"] is True


# ---- §23 failed 删除项下发失败原因 ----


def test_delete_queue_failed_item_exposes_error(tmp_path: Path, client) -> None:
    """failed 项必须在列表下发 error(guard 代码),客户端映射为简短中文。"""
    db = client.app.state.database
    bad_file = tmp_path / "bad.mp4"
    bad_file.write_bytes(b"123")
    _seed_library(db)
    _seed_media(db, "baddd" * 4, size=999, media_path=str(bad_file))
    with db.session() as s:
        assert delete_queue.enqueue(s, "baddd" * 4, size_bytes=999) is True
        s.commit()

    prep = client.post("/api/v1/delete-queue/commit/prepare").json()["data"]
    commit = client.post("/api/v1/delete-queue/commit", json={"nonce": prep["nonce"]})
    assert commit.json()["data"]["outcome"]["baddd" * 4] == "failed"

    rows = client.get("/api/v1/delete-queue").json()["data"]
    failed = [row for row in rows if row["media_id"] == "baddd" * 4]
    assert len(failed) == 1
    assert failed[0]["status"] == "failed"
    assert failed[0]["error"], "failed 项必须下发失败原因"
    assert "file_size_changed" in failed[0]["error"]

    # failed 仍可恢复(撤销) —— 与 §21/§22 文案"请恢复后重新标记"一致
    assert client.delete("/api/v1/delete-queue/" + "baddd" * 4).status_code == 200
    with db.session() as s:
        assert s.get(DeleteQueue, "baddd" * 4) is None