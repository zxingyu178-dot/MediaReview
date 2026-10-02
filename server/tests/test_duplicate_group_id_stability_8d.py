"""Stage 8D §3/§4: 重复分组 group_id 的**身份稳定性**回归(第一 Gate)。

旧实现把枚举序号 ``idx`` 当业务身份(``exact:xxx:1`` / ``:2``): 只要旁边新增一个
排序更靠前的重复组,旧组的序号就会漂移,导致:

- group_id 变化 → 客户端以为是"新分组";
- 以 (group_id, media_id) 为键的人工 Keep 无法恢复,静默丢失。

本文件锁死修复后的合同: exact / high 的 group_id 由
``(type, size, duration, 内容哈希)`` 确定,与"同组合下其它无关分组是否存在"无关。
"""

from __future__ import annotations

import hashlib
from pathlib import Path

from app.db.models import BackgroundTask, LibrarySelection, MediaCacheIndex
from app.db.session import Database
from app.services import duplicate_scanner as ds


def _make_db(data_root: Path) -> Database:
    db = Database(data_root / "database" / "mediareview.db")
    db.create_all()
    return db


def _seed_library(db: Database, library_id: str = "lib-movies") -> None:
    with db.session() as s:
        s.merge(
            LibrarySelection(
                jellyfin_id=library_id,
                name=library_id,
                collection_type="movies",
                selected=True,
            )
        )
        s.commit()


def _seed_media(
    db: Database,
    media_id: str,
    *,
    size: int,
    duration: int,
    sha256: str | None,
    quick_hash: str | None,
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
                is_available=True,
                media_path=f"D:\\Media\\{media_id}.mp4",
            )
        )
        s.commit()


def _sha(tag: str) -> str:
    return hashlib.sha256(tag.encode("utf-8")).hexdigest()


def _run_scan(db: Database) -> None:
    with db.session() as s:
        task = ds.schedule_duplicate_scan(s)
        s.commit()
        task_id = task.task_id
    with db.session() as s:
        s.get(BackgroundTask, task_id).status = "running"
        s.commit()
    ds.run_duplicate_scan(db, task_id)


def _groups(db: Database, group_type: str) -> list[ds.DuplicateGroup]:
    with db.session() as s:
        return ds.persisted_groups(s, group_type)


def _group_with(groups: list[ds.DuplicateGroup], media_id: str) -> ds.DuplicateGroup:
    for g in groups:
        if media_id in g.media_ids:
            return g
    raise AssertionError(f"未找到包含 {media_id} 的分组: {[g.group_id for g in groups]}")


def _set_keep(db: Database, group_id: str, media_id: str) -> None:
    with db.session() as s:
        assert ds.set_keep(s, group_id, media_id, True) is True
        s.commit()


def _assert_stable_across_sibling_insert(db: Database, group_type: str) -> None:
    """先有 B/C 两组 → 新增排序更靠前的 A → B/C 身份与 Keep 均不得漂移。"""
    size, duration = 5000, 60_000
    if group_type == "exact":
        # byte-identical: 每组共享一个完整 sha256
        seed = lambda mid, tag: _seed_media(  # noqa: E731
            db, mid, size=size, duration=duration, sha256=_sha(tag), quick_hash="q" * 64
        )
    else:
        # high: 大小/时长一致 + quick_hash 一致(无完整 sha256)
        seed = lambda mid, tag: _seed_media(  # noqa: E731
            db, mid, size=size, duration=duration, sha256=None, quick_hash=_sha(tag)
        )

    # 顺序刻意让 A(排序在前)后加入,以复现"序号漂移"缺陷
    seed("b1", "B")
    seed("b2", "B")
    seed("c1", "C")
    seed("c2", "C")

    _run_scan(db)
    b_before = _group_with(_groups(db, group_type), "b1")
    c_before = _group_with(_groups(db, group_type), "c1")
    assert b_before.group_id != c_before.group_id

    _set_keep(db, b_before.group_id, "b1")
    _set_keep(db, c_before.group_id, "c1")

    # 新增一组排序更靠前的同名大小/时长分组 A
    seed("a1", "A")
    seed("a2", "A")

    _run_scan(db)
    groups = _groups(db, group_type)
    b_after = _group_with(groups, "b1")
    c_after = _group_with(groups, "c1")
    a_after = _group_with(groups, "a1")

    assert a_after.group_id not in {b_before.group_id, c_before.group_id}
    assert b_after.group_id == b_before.group_id, (
        f"{group_type} 组 B 的 group_id 因无关 sibling 新增而漂移: "
        f"{b_before.group_id} -> {b_after.group_id}"
    )
    assert c_after.group_id == c_before.group_id, (
        f"{group_type} 组 C 的 group_id 因无关 sibling 新增而漂移: "
        f"{c_before.group_id} -> {c_after.group_id}"
    )
    assert b_after.keep["b1"] is True, f"{group_type} 组 B 的人工 Keep 丢失"
    assert c_after.keep["c1"] is True, f"{group_type} 组 C 的人工 Keep 丢失"


def test_exact_group_id_stable_when_sibling_inserted(tmp_path: Path) -> None:
    db = _make_db(tmp_path)
    _seed_library(db)
    _assert_stable_across_sibling_insert(db, "exact")
    db.dispose()


def test_high_group_id_stable_when_sibling_inserted(tmp_path: Path) -> None:
    db = _make_db(tmp_path)
    _seed_library(db)
    _assert_stable_across_sibling_insert(db, "high")
    db.dispose()


def test_group_id_has_no_ordinal_suffix(tmp_path: Path) -> None:
    """防御性合同: group_id 不得再以 ``:<序号>`` 结尾。"""
    db = _make_db(tmp_path)
    _seed_library(db)
    _seed_media(db, "e1", size=7000, duration=60_000, sha256=_sha("E"), quick_hash="q" * 64)
    _seed_media(db, "e2", size=7000, duration=60_000, sha256=_sha("E"), quick_hash="q" * 64)

    _run_scan(db)
    group_id = _groups(db, "exact")[0].group_id
    assert group_id.startswith("exact:")
    assert not group_id.rsplit(":", 1)[-1].isdigit(), f"group_id 仍含枚举序号: {group_id}"
    assert len(group_id) <= 64, "group_id 必须适配 DuplicateGroup.group_id 列宽(64)"
    db.dispose()


if __name__ == "__main__":  # pragma: no cover
    import pytest

    raise SystemExit(pytest.main([__file__, "-q"]))
