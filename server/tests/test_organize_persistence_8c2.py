"""Stage 8C.2: 重复扫描的人工保留持久化 + 扫描范围冻结(服务端合同)。

覆盖任务书 §4~§22 与 §43:

- Keep 持久化: 成功重扫后,完全相同的 `(group_id, media_id)` 恢复上一份人工选择;
  分组语义变化(拆并/改类型)或成员消失时**不继承**(keep=False),不产生幽灵记录;
- Keep 与替换在**同一事务**内完成(不存在"先存 keep 再替换"的竞争窗口);
- 扫描 Scope 冻结: 开始时把已选媒体库快照写入 `BackgroundTask.params`,
  exact / high / candidate / similar 四阶段共用同一快照;
- 扫描期间媒体库勾选发生变化 → 拒绝发布(不替换),任务置
  `failed: library_selection_changed`,上一份完整结果保持;
- Pause → Resume 继续使用 `params` 里的原始 Scope,绝不改用当前 Library Selection。
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

import pytest

from app.db.models import BackgroundTask, DuplicateGroupMember, LibrarySelection, MediaCacheIndex
from app.db.session import Database
from app.services import duplicate_scanner as ds


def _make_db(data_root: Path) -> Database:
    db = Database(data_root / "database" / "mediareview.db")
    db.create_all()
    return db


def _seed_library(db: Database, library_id: str, *, selected: bool = True) -> None:
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
    quick_hash: str | None = "q" * 64,
    sha256: str | None = None,
    available: bool = True,
    width: int | None = 1920,
    height: int | None = 1080,
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
                media_path=f"D:\\Media\\{media_id}.mp4",
            )
        )
        s.commit()


def _sha(tag: str) -> str:
    return hashlib.sha256(tag.encode("utf-8")).hexdigest()


def _seed_exact_pair(db: Database, tag: str, *, library_id: str = "lib-movies", size: int = 1000):
    for suffix in ("x", "y"):
        _seed_media(
            db,
            f"{tag}{suffix}",
            library_id=library_id,
            size=size,
            duration=60_000,
            sha256=_sha(tag),
        )


def _run_scan(db: Database) -> str:
    with db.session() as s:
        task = ds.schedule_duplicate_scan(s)
        s.commit()
        task_id = task.task_id
    with db.session() as s:
        s.get(BackgroundTask, task_id).status = "running"
        s.commit()
    ds.run_duplicate_scan(db, task_id)
    return task_id


def _task(db: Database, task_id: str) -> BackgroundTask:
    with db.session() as s:
        return s.get(BackgroundTask, task_id)


def _groups(db: Database, group_type: str = "exact") -> list[ds.DuplicateGroup]:
    with db.session() as s:
        return ds.persisted_groups(s, group_type)


def _set_keep(db: Database, group_id: str, media_id: str, keep: bool) -> None:
    with db.session() as s:
        assert ds.set_keep(s, group_id, media_id, keep) is True
        s.commit()


# ---- §4~§12 Keep 持久化 ----


def test_keep_survives_successful_rescan(tmp_path: Path) -> None:
    """相同 (group_id, media_id) 的重扫必须恢复人工保留选择。"""
    db = _make_db(tmp_path)
    _seed_library(db, "lib-movies")
    _seed_exact_pair(db, "keepme")

    _run_scan(db)
    group_id = _groups(db)[0].group_id
    _set_keep(db, group_id, "keepmex", True)

    # 完全相同的结果再扫一次(模拟用户点"重新扫描")
    _run_scan(db)

    groups = _groups(db)
    assert len(groups) == 1
    assert groups[0].group_id == group_id, "相同结果必须得到相同 group_id"
    assert groups[0].keep["keepmex"] is True, "重扫不得丢失人工保留选择"
    assert groups[0].keep["keepmey"] is False
    db.dispose()


def test_keep_false_survives_rescan(tmp_path: Path) -> None:
    """keep=False 也按原值恢复(不是"只在 true 时才处理")。"""
    db = _make_db(tmp_path)
    _seed_library(db, "lib-movies")
    _seed_exact_pair(db, "falsy")

    _run_scan(db)
    group_id = _groups(db)[0].group_id
    _set_keep(db, group_id, "falsyx", True)
    _set_keep(db, group_id, "falsyx", False)

    _run_scan(db)

    assert _groups(db)[0].keep["falsyx"] is False
    db.dispose()


def test_changed_group_does_not_inherit_keep(tmp_path: Path) -> None:
    """分组语义变化(旧 exact → 新 similar)不得继承人工选择(§8/§9)。"""
    db = _make_db(tmp_path)
    _seed_library(db, "lib-movies")
    _seed_exact_pair(db, "shift")

    _run_scan(db)
    old_group_id = _groups(db, "exact")[0].group_id
    _set_keep(db, old_group_id, "shiftx", True)

    # 让这一对从 exact 变成 similar: sha 失效 + 时长差异超容忍值
    with db.session() as s:
        s.query(MediaCacheIndex).filter(MediaCacheIndex.media_id == "shiftx").update(
            {"sha256": None, "duration_ms": 10_000}, synchronize_session=False
        )
        s.query(MediaCacheIndex).filter(MediaCacheIndex.media_id == "shifty").update(
            {"sha256": None}, synchronize_session=False
        )
        s.commit()

    _run_scan(db)

    assert _groups(db, "exact") == []
    similar = _groups(db, "similar")
    assert len(similar) == 1
    assert similar[0].group_id != old_group_id
    assert similar[0].keep.get("shiftx") is False, "新分组不得继承旧分组的人工选择"
    db.dispose()


def test_removed_media_leaves_no_ghost_keep(tmp_path: Path) -> None:
    """成员消失后不得留下幽灵 keep 记录。"""
    db = _make_db(tmp_path)
    _seed_library(db, "lib-movies")
    _seed_exact_pair(db, "ghost")

    _run_scan(db)
    group_id = _groups(db)[0].group_id
    _set_keep(db, group_id, "ghostx", True)

    # 其中一个成员从索引消失
    with db.session() as s:
        s.query(MediaCacheIndex).filter(MediaCacheIndex.media_id == "ghostx").delete(
            synchronize_session=False
        )
        s.commit()

    _run_scan(db)

    with db.session() as s:
        members = [
            (m.group_id, m.media_id, m.keep)
            for m in s.query(DuplicateGroupMember).all()
        ]
    assert all(media_id != "ghostx" for _gid, media_id, _keep in members), "不得保留幽灵成员"
    assert all(keep is False for _gid, _mid, keep in members)
    db.dispose()


# ---- §13~§16 Scope 冻结 ----


def test_scan_freezes_library_scope_into_task_params(tmp_path: Path, monkeypatch) -> None:
    """扫描开始时把已选媒体库快照写入 params,且四个阶段共用同一快照。"""
    db = _make_db(tmp_path)
    _seed_library(db, "lib-movies")
    _seed_library(db, "lib-tv")
    _seed_exact_pair(db, "aa", library_id="lib-movies", size=1000)
    _seed_exact_pair(db, "bb", library_id="lib-tv", size=2000)

    seen: list[tuple[str, ...]] = []
    for name in (
        "scan_exact_duplicates",
        "scan_high_confidence",
        "scan_candidates",
        "scan_similar_candidates",
    ):
        original = getattr(ds, name)

        def spy(session, library_ids=None, _original=original):  # noqa: ANN001
            seen.append(tuple(library_ids or ()))
            return _original(session, library_ids)

        monkeypatch.setattr(ds, name, spy)

    task_id = _run_scan(db)

    # exact 会被 high 内部复用,因此调用次数 >= 4;关键是没有一次使用不同范围
    assert len(seen) >= 4, f"四个阶段都必须执行: {seen}"
    assert set(seen) == {("lib-movies", "lib-tv")}, f"四阶段不得使用不同范围: {seen}"
    params = json.loads(_task(db, task_id).params)
    assert params["library_ids"] == ["lib-movies", "lib-tv"]
    db.dispose()


def _start_scan(db: Database) -> str:
    with db.session() as s:
        task = ds.schedule_duplicate_scan(s)
        s.commit()
        task_id = task.task_id
    with db.session() as s:
        s.get(BackgroundTask, task_id).status = "running"
        s.commit()
    return task_id


def _deselect_during_scan(db: Database, monkeypatch, library_id: str) -> None:
    """模拟"扫描开始后、结束前"用户取消勾选某个媒体库(§17)。"""
    original = ds.scan_exact_duplicates

    def spy(session, library_ids=None):  # noqa: ANN001
        with db.session() as s:
            s.merge(
                LibrarySelection(
                    jellyfin_id=library_id,
                    name=library_id,
                    collection_type="movies",
                    selected=False,
                )
            )
            s.commit()
        return original(session, library_ids)

    monkeypatch.setattr(ds, "scan_exact_duplicates", spy)


def test_library_selection_changed_rejects_replacement(tmp_path: Path, monkeypatch) -> None:
    """扫描期间媒体库勾选变化 → 不替换、任务 failed: library_selection_changed(§17~§19)。"""
    db = _make_db(tmp_path)
    _seed_library(db, "lib-movies")
    _seed_library(db, "lib-tv")
    _seed_exact_pair(db, "aa", library_id="lib-movies", size=1000)
    _seed_exact_pair(db, "bb", library_id="lib-tv", size=2000)

    _run_scan(db)
    before = {(g.group_id, tuple(g.media_ids)) for g in _groups(db)}
    assert len(before) == 2

    # 第二次扫描: 开始时快照 = A+B,扫描过程中取消勾选 lib-tv
    task_id = _start_scan(db)
    _deselect_during_scan(db, monkeypatch, "lib-tv")
    ds.run_duplicate_scan(db, task_id)

    after_task = _task(db, task_id)
    assert after_task.status == "failed"
    assert after_task.error == ds.ERROR_LIBRARY_SELECTION_CHANGED
    after = {(g.group_id, tuple(g.media_ids)) for g in _groups(db)}
    assert after == before, "拒绝替换后上一份完整结果必须原样保留"
    db.dispose()


def test_scope_change_keeps_previous_keep(tmp_path: Path, monkeypatch) -> None:
    """拒绝替换时人工保留选择同样不能丢(旧结果整体保留)。"""
    db = _make_db(tmp_path)
    _seed_library(db, "lib-movies")
    _seed_library(db, "lib-tv")
    _seed_exact_pair(db, "aa", library_id="lib-movies", size=1000)
    _seed_exact_pair(db, "bb", library_id="lib-tv", size=2000)

    _run_scan(db)
    group = _groups(db, "exact")[0]
    _set_keep(db, group.group_id, group.media_ids[0], True)

    task_id = _start_scan(db)
    _deselect_during_scan(db, monkeypatch, "lib-tv")
    ds.run_duplicate_scan(db, task_id)

    kept = {g.group_id: g.keep for g in _groups(db, "exact")}
    assert kept[group.group_id][group.media_ids[0]] is True
    db.dispose()


def test_pause_resume_keeps_original_scope(tmp_path: Path, monkeypatch) -> None:
    """暂停恢复必须继续使用 params 里的原始 Scope,不能改用当前 Library Selection(§22)。"""
    db = _make_db(tmp_path)
    _seed_library(db, "lib-movies")
    _seed_library(db, "lib-tv")
    _seed_exact_pair(db, "aa", library_id="lib-movies", size=1000)
    _seed_exact_pair(db, "bb", library_id="lib-tv", size=2000)

    with db.session() as s:
        task = ds.schedule_duplicate_scan(s)
        s.commit()
        task_id = task.task_id
    with db.session() as s:
        s.get(BackgroundTask, task_id).status = "running"
        s.commit()

    # 第一阶段执行后"被暂停": 后续阶段整体放弃(§17 保留上一份结果)
    original_exact = ds.scan_exact_duplicates

    def pause_after_first(session, library_ids=None):  # noqa: ANN001
        result = original_exact(session, library_ids)
        with db.session() as s:
            s.get(BackgroundTask, task_id).status = "paused"
            s.commit()
        return result

    monkeypatch.setattr(ds, "scan_exact_duplicates", pause_after_first)
    ds.run_duplicate_scan(db, task_id)

    paused = _task(db, task_id)
    assert paused.status == "paused", "暂停不得被扫描任务覆盖"
    assert json.loads(paused.params)["library_ids"] == ["lib-movies", "lib-tv"]

    # 用户此时取消勾选 lib-tv,然后恢复扫描
    with db.session() as s:
        s.merge(LibrarySelection(jellyfin_id="lib-tv", name="lib-tv", collection_type="movies", selected=False))
        s.get(BackgroundTask, task_id).status = "running"
        s.commit()

    seen: list[tuple[str, ...]] = []

    def spy(session, library_ids=None):  # noqa: ANN001
        seen.append(tuple(library_ids or ()))
        return original_exact(session, library_ids)

    monkeypatch.setattr(ds, "scan_exact_duplicates", spy)
    ds.run_duplicate_scan(db, task_id)

    assert seen and set(seen) == {("lib-movies", "lib-tv")}, (
        f"恢复扫描必须沿用原始 Scope 快照,而不是当前选择: {seen}"
    )
    resumed = _task(db, task_id)
    assert resumed.status == "failed"
    assert resumed.error == ds.ERROR_LIBRARY_SELECTION_CHANGED
    db.dispose()


# ---- §21 允许: 媒体可用性变化由详情标记,不冻结媒体索引 ----


def test_media_availability_change_marks_member_unavailable(tmp_path: Path) -> None:
    """扫描后失效的媒体: 结果允许保留,但详情必须标记 available=false(§21/§20)。"""
    db = _make_db(tmp_path)
    _seed_library(db, "lib-movies")
    _seed_exact_pair(db, "gone")

    _run_scan(db)
    group_id = _groups(db)[0].group_id

    with db.session() as s:
        s.query(MediaCacheIndex).filter(MediaCacheIndex.media_id == "gonex").update(
            {"is_available": False}, synchronize_session=False
        )
        s.commit()

    with db.session() as s:
        detail = ds.group_detail(s, group_id)
    assert detail is not None
    by_id = {m.media_id: m.available for m in detail.members}
    assert by_id["gonex"] is False
    assert by_id["goney"] is True
    db.dispose()


# ---- 兼容: 交互式路径仍可省略 library_ids(读取当前选择) ----


def test_interactive_path_still_uses_current_selection(tmp_path: Path) -> None:
    """未显式传 Scope 的调用(如 has_pending_hashes)继续读取当前选择。"""
    db = _make_db(tmp_path)
    _seed_library(db, "lib-movies", selected=False)
    _seed_media(db, "pending-a", size=500, duration=3000, quick_hash=None)
    _seed_media(db, "pending-b", size=500, duration=3000, quick_hash=None)

    with db.session() as s:
        assert ds.has_pending_hashes(s) is False, "未勾选媒体库不得触发扫描侧哈希编排"
        assert ds.scan_all(s) == []

    with db.session() as s:
        s.merge(
            LibrarySelection(
                jellyfin_id="lib-movies", name="lib-movies", collection_type="movies", selected=True
            )
        )
        s.commit()
    with db.session() as s:
        assert ds.has_pending_hashes(s) is True
    db.dispose()


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(pytest.main([__file__, "-q"]))