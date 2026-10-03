"""Stage 8D.1 §18~§24: 旧"序号身份"分组的人工 Keep 首次安全迁移。

背景:8D 起 group_id 由内容哈希派生(``exact:<digest>`` / ``high:<digest>``),
但升级前 DB 里可能仍是旧序号 ID(``exact:size:duration:1``)。第一次重扫时旧 ID ≠ 新 ID,
如果只按 ``(group_id, media_id)`` 恢复就会丢 Keep。

本文件锁死安全迁移合同:

- 仅当 **type 相同 且 成员集合完全相同** 时,才把旧序号分组的 Keep 迁到新的稳定分组;
- 成员增减 / type 改变 / 拆分合并 → **不迁移**(keep=False),绝不猜;
- 稳定 ID 之间仍严格按 ``(group_id, media_id)`` 恢复;
- 升级完成后 DB 自然全部变为稳定 ID(运行时业务数据迁移,无 DB schema 变更)。
"""

from __future__ import annotations

import hashlib
from pathlib import Path

from app.db.models import (
    BackgroundTask,
    DuplicateGroup,
    DuplicateGroupMember,
    LibrarySelection,
    MediaCacheIndex,
)
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
    size: int = 1000,
    duration: int = 60_000,
    sha256: str | None = None,
    quick_hash: str | None = "q" * 64,
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


def _insert_legacy_group(
    db: Database,
    group_id: str,
    group_type: str,
    members: list[str],
    *,
    keep_ids: set[str],
    size: int = 1000,
    duration: int = 60_000,
) -> None:
    """直接写入一条**旧序号 ID** 的分组,模拟升级前的历史数据。"""
    with db.session() as s:
        s.add(
            DuplicateGroup(
                group_id=group_id,
                type=group_type,
                size_bytes=size,
                duration_ms=duration,
                count=len(members),
                detail="legacy",
            )
        )
        for media_id in members:
            s.add(
                DuplicateGroupMember(
                    group_id=group_id,
                    media_id=media_id,
                    name=media_id + ".mp4",
                    fingerprint=f"{group_type}:{media_id}",
                    keep=media_id in keep_ids,
                )
            )
        s.commit()


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


# ---- §24 必须覆盖的场景 ----


def test_legacy_exact_same_members_preserves_keep(tmp_path: Path) -> None:
    db = _make_db(tmp_path)
    _seed_library(db)
    sha = _sha("pair")
    for mid in ("x", "y"):
        _seed_media(db, mid, sha256=sha)
    _insert_legacy_group(db, "exact:1000:60000:1", "exact", ["x", "y"], keep_ids={"x"})

    _run_scan(db)

    group = _group_with(_groups(db, "exact"), "x")
    assert group.group_id.startswith("exact:") and group.group_id != "exact:1000:60000:1"
    assert group.keep["x"] is True, "同类型同成员的旧 exact Keep 必须迁移"
    assert group.keep["y"] is False
    db.dispose()


def test_legacy_high_same_members_preserves_keep(tmp_path: Path) -> None:
    db = _make_db(tmp_path)
    _seed_library(db)
    qh = _sha("quick")
    for mid in ("m", "n"):
        _seed_media(db, mid, sha256=None, quick_hash=qh)
    _insert_legacy_group(db, "high:1000:60000:1", "high", ["m", "n"], keep_ids={"n"})

    _run_scan(db)

    group = _group_with(_groups(db, "high"), "m")
    assert group.keep["n"] is True, "同类型同成员的旧 high Keep 必须迁移"
    db.dispose()


def test_legacy_same_type_different_members_not_preserved(tmp_path: Path) -> None:
    """成员集合不完全相同(这里是子集)→ 不迁移(§22)。"""
    db = _make_db(tmp_path)
    _seed_library(db)
    sha = _sha("triple")
    for mid in ("a", "b", "c"):
        _seed_media(db, mid, sha256=sha)
    # 旧分组只含 a,b;实际 exact 分组为 a,b,c → 签名不同
    _insert_legacy_group(db, "exact:1000:60000:1", "exact", ["a", "b"], keep_ids={"a"})

    _run_scan(db)

    group = _group_with(_groups(db, "exact"), "a")
    assert set(group.media_ids) == {"a", "b", "c"}
    assert all(v is False for v in group.keep.values()), "成员集合变化不得继承 Keep"
    db.dispose()


def test_legacy_same_members_different_type_not_preserved(tmp_path: Path) -> None:
    """成员相同但类型不同(high vs exact)→ 不迁移(§22)。"""
    db = _make_db(tmp_path)
    _seed_library(db)
    sha = _sha("typecheck")
    for mid in ("u", "v"):
        _seed_media(db, mid, sha256=sha)
    # 旧分组标成 high,但实际内容相同 → 会形成 exact 分组
    _insert_legacy_group(db, "high:1000:60000:2", "high", ["u", "v"], keep_ids={"u"})

    _run_scan(db)

    group = _group_with(_groups(db, "exact"), "u")
    assert group.keep["u"] is False, "类型变化不得继承 Keep"
    db.dispose()


def test_legacy_sibling_ordinal_change_still_preserves_keep(tmp_path: Path) -> None:
    """两组旧序号分组,重扫后序号会变,但成员不变 → 两组 Keep 都必须保留。"""
    db = _make_db(tmp_path)
    _seed_library(db)
    _seed_media(db, "b1", sha256=_sha("B"))
    _seed_media(db, "b2", sha256=_sha("B"))
    _seed_media(db, "c1", sha256=_sha("C"))
    _seed_media(db, "c2", sha256=_sha("C"))
    _insert_legacy_group(db, "exact:1000:60000:1", "exact", ["b1", "b2"], keep_ids={"b1"})
    _insert_legacy_group(db, "exact:1000:60000:2", "exact", ["c1", "c2"], keep_ids={"c2"})

    _run_scan(db)

    groups = _groups(db, "exact")
    assert _group_with(groups, "b1").keep["b1"] is True
    assert _group_with(groups, "c1").keep["c2"] is True
    db.dispose()


def test_stable_id_rescan_uses_strict_match(tmp_path: Path) -> None:
    """已经是稳定 ID 的重扫:正常按 (group_id, media_id) 恢复(§21/§25)。"""
    db = _make_db(tmp_path)
    _seed_library(db)
    _seed_media(db, "s1", sha256=_sha("S"))
    _seed_media(db, "s2", sha256=_sha("S"))

    _run_scan(db)
    first = _groups(db, "exact")[0]
    with db.session() as s:
        assert ds.set_keep(s, first.group_id, "s1", True) is True
        s.commit()

    _run_scan(db)
    second = _groups(db, "exact")[0]
    assert second.group_id == first.group_id
    assert second.keep["s1"] is True
    db.dispose()


def test_migration_persists_only_stable_ids(tmp_path: Path) -> None:
    """迁移完成后 DB 里只剩稳定 ID(§25),不留旧序号 ID。"""
    db = _make_db(tmp_path)
    _seed_library(db)
    _seed_media(db, "k1", sha256=_sha("K"))
    _seed_media(db, "k2", sha256=_sha("K"))
    _insert_legacy_group(db, "exact:1000:60000:7", "exact", ["k1", "k2"], keep_ids={"k1"})

    _run_scan(db)

    with db.session() as s:
        ids = [row.group_id for row in s.query(DuplicateGroup).all()]
    assert ids, "迁移后必须存在稳定分组"
    assert all(not ds._is_legacy_ordinal_group_id(gid) for gid in ids), f"仍残留旧序号 ID: {ids}"
    db.dispose()


if __name__ == "__main__":  # pragma: no cover
    import pytest

    raise SystemExit(pytest.main([__file__, "-q"]))
