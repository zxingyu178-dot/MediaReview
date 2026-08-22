"""重复检测服务: 多级哈希分组(候选筛选 -> 高度可信 -> byte-identical)。

依据 PRODUCT_SPEC 与 Stage-07 验收修正:size + duration **只能作为候选筛选**,
绝不直接等同于 exact。只有经过完整文件校验(sha256)才可标记为真正的完全重复。

分组语义:
- type="candidate":  size+duration 一致,但 quick_hash 尚未计算(等待后台哈希任务)。
- type="high":       size+duration 一致 且 quick_hash 一致(高度可信重复,仍需人工复核)。
- type="exact":      size+duration 一致 且整文件 sha256 一致(byte-identical 完全重复)。
- type="similar":    大小一致但时长差异超容忍(疑似重复基础能力)。

本模块只读已被后台任务写好的哈希字段,**不做任何磁盘 I/O**;真实文件读写在
hash_tasks 后台任务线程中完成,避免阻塞 FastAPI 请求与 SQLite 写锁(AGENTS.md)。
"""

from __future__ import annotations

from dataclasses import dataclass

import sqlalchemy as sa
from sqlalchemy.orm import Session

from app.db.models import MediaCacheIndex
from app.services.hash_tasks import HASH_UNREADABLE

# 疑似重复: 批量查询单次上限,避免一次返回过大
_MAX_GROUPS = 200
# 疑似重复的时长差异容忍(秒): 相同大小但时长相差超过该值的视为"疑似需人工确认"
_DURATION_TOLERANCE_MS = 3_000


@dataclass(frozen=True)
class DuplicateGroup:
    """一组重复媒体。type: candidate / high / exact / similar。"""

    group_id: str
    type: str
    count: int
    media_ids: list[str]
    names: list[str]
    size_bytes: int
    duration_ms: int | None = None
    detail: str = ""


def _candidate_pairs(session: Session, *, limit: int = _MAX_GROUPS) -> list[tuple[int, int]]:
    """候选筛选: 大小 + 时长 均一致且不止一份的组合(size_bytes, duration_ms)。"""
    rows = session.execute(
        sa.select(
            MediaCacheIndex.size_bytes,
            MediaCacheIndex.duration_ms,
            sa.func.count(MediaCacheIndex.media_id).label("cnt"),
        )
        .where(
            MediaCacheIndex.size_bytes.is_not(None),
            MediaCacheIndex.duration_ms.is_not(None),
        )
        .group_by(MediaCacheIndex.size_bytes, MediaCacheIndex.duration_ms)
        .having(sa.func.count(MediaCacheIndex.media_id) > 1)
        .order_by(MediaCacheIndex.size_bytes.desc())
        .limit(limit)
    ).all()
    return [(int(sz), int(dur)) for sz, dur, _cnt in rows]


def _is_valid_hash(h: str | None) -> bool:
    """哈希字段有效: 非空且不是"读取失败"哨兵(unreadable-io)。"""
    return bool(h) and h != HASH_UNREADABLE


def _pair_members(
    session: Session, size_bytes: int, duration_ms: int
) -> list[tuple[str, str, str | None, str | None]]:
    """返回某候选组合内的成员 (media_id, name, quick_hash, sha256)。"""
    rows = session.execute(
        sa.select(
            MediaCacheIndex.media_id,
            MediaCacheIndex.name,
            MediaCacheIndex.quick_hash,
            MediaCacheIndex.sha256,
        )
        .where(
            MediaCacheIndex.size_bytes == size_bytes,
            MediaCacheIndex.duration_ms == duration_ms,
        )
        .order_by(MediaCacheIndex.media_id)
    ).all()
    return [(rid, name, qh, sh) for rid, name, qh, sh in rows]


def _bucket_groups(
    type_hint: str,
    size_bytes: int,
    duration_ms: int,
    buckets: list[list[tuple[str, str]]],
) -> list[DuplicateGroup]:
    """把同一候选组合下按哈希进一步切分后的成员桶转为分组对象。"""
    groups: list[DuplicateGroup] = []
    for idx, members in enumerate(buckets, start=1):
        if len(members) < 2:
            continue
        media_ids = [m[0] for m in members]
        names = [m[1] for m in members]
        groups.append(
            DuplicateGroup(
                group_id=f"{type_hint}:{size_bytes}:{duration_ms}:{idx}",
                type=type_hint,
                count=len(members),
                media_ids=media_ids,
                names=names,
                size_bytes=size_bytes,
                duration_ms=duration_ms,
                detail=_detail_text(type_hint, len(members)),
            )
        )
    return groups


def _detail_text(type_hint: str, count: int) -> str:
    if type_hint == "exact":
        return f"整文件一对一校验一致(byte-identical),共 {count} 份,可安全保留其一"
    if type_hint == "high":
        return f"文件大小/时长与采样哈希均一致(高度可信),共 {count} 份,删除前建议先完整校验"
    if type_hint == "candidate":
        return f"文件大小与时长一致,尚未完成内容校验,共 {count} 份(后台正在计算哈希)"
    return f"文件大小一致但时长存在差异,疑似重复,共 {count} 份"


def scan_exact_duplicates(session: Session) -> list[DuplicateGroup]:
    """完全重复(byte-identical): 大小+时长一致 且 整文件 sha256 一致。

    只有已由后台任务计算 full sha256 的媒体才会进入;未计算的一律不得判定为 exact。
    这保证"两个大小与时长相同但内容不同的文件"绝不会被标记为完全重复。
    """
    groups: list[DuplicateGroup] = []
    for size_bytes, duration_ms in _candidate_pairs(session):
        members = _pair_members(session, size_bytes, duration_ms)
        # 按 sha256 进一步切分;sha256 无效(None 或读取失败哨兵)的成员排除在外
        buckets: dict[str, list[tuple[str, str]]] = {}
        for media_id, name, _qh, sha in members:
            if not _is_valid_hash(sha):
                continue
            buckets.setdefault(sha, []).append((media_id, name))
        groups.extend(_bucket_groups("exact", size_bytes, duration_ms, list(buckets.values())))
    return groups


def scan_high_confidence(session: Session) -> list[DuplicateGroup]:
    """高度可信重复: 大小+时长一致 且 quick_hash 一致。

    排除已进入 exact 的成员,避免同一对既报 exact 又报 high。
    """
    return _dedup_orders(_high_groups(session))


def _high_groups(session: Session) -> list[DuplicateGroup]:
    """scan_high_confidence 的内部实现(返回未去重顺序的 high 分组)。"""
    groups: list[DuplicateGroup] = []
    exact_assigned = {mid for grp in scan_exact_duplicates(session) for mid in grp.media_ids}
    for size_bytes, duration_ms in _candidate_pairs(session):
        members = _pair_members(session, size_bytes, duration_ms)
        buckets: dict[str, list[tuple[str, str]]] = {}
        for media_id, name, qh, _sha in members:
            if not _is_valid_hash(qh) or media_id in exact_assigned:
                continue
            buckets.setdefault(qh, []).append((media_id, name))
        groups.extend(_bucket_groups("high", size_bytes, duration_ms, list(buckets.values())))
        for grp in groups:
            exact_assigned.update(grp.media_ids)
    return groups


def _dedup_orders(groups: list[DuplicateGroup]) -> list[DuplicateGroup]:
    """按首次出现顺序保留,去掉重复对象(防御相同组合被多次追加)。"""
    seen: set[str] = set()
    result: list[DuplicateGroup] = []
    for grp in groups:
        if grp.group_id in seen:
            continue
        seen.add(grp.group_id)
        result.append(grp)
    return result


def scan_candidates(session: Session) -> list[DuplicateGroup]:
    """候选筛选结果: 大小+时长一致 但 quick_hash 尚未计算(等待后台哈希)。"""
    groups: list[DuplicateGroup] = []
    for size_bytes, duration_ms in _candidate_pairs(session):
        members = _pair_members(session, size_bytes, duration_ms)
        pending: list[tuple[str, str]] = [
            (media_id, name) for media_id, name, qh, _sha in members if qh is None
        ]
        if len(pending) < 2:
            continue
        groups.append(
            DuplicateGroup(
                group_id=f"candidate:{size_bytes}:{duration_ms}",
                type="candidate",
                count=len(pending),
                media_ids=[m[0] for m in pending],
                names=[m[1] for m in pending],
                size_bytes=size_bytes,
                duration_ms=duration_ms,
                detail=_detail_text("candidate", len(pending)),
            )
        )
    return groups


def scan_similar_candidates(session: Session) -> list[DuplicateGroup]:
    """疑似重复: 大小一致但时长差异超容忍值(可能为转码/裁剪等)。

    仅作为"疑似基础能力",不自动删除(AGENTS.md),供独立页面提示用户。
    """
    groups: list[DuplicateGroup] = []
    size_rows = session.execute(
        sa.select(
            MediaCacheIndex.size_bytes,
            sa.func.count(MediaCacheIndex.media_id).label("cnt"),
        )
        .where(MediaCacheIndex.size_bytes.is_not(None))
        .group_by(MediaCacheIndex.size_bytes)
        .having(sa.func.count(MediaCacheIndex.media_id) > 1)
        .order_by(MediaCacheIndex.size_bytes.desc())
        .limit(_MAX_GROUPS)
    ).all()

    for size_bytes, cnt in size_rows:
        members = session.execute(
            sa.select(
                MediaCacheIndex.media_id,
                MediaCacheIndex.name,
                MediaCacheIndex.duration_ms,
            ).where(MediaCacheIndex.size_bytes == size_bytes)
        ).all()
        if len(members) < 2:
            continue
        durations = [m.duration_ms for m in members if m.duration_ms is not None]
        if durations and max(durations) - min(durations) <= _DURATION_TOLERANCE_MS:
            continue
        media_ids = [m.media_id for m in members]
        names = [m.name for m in members]
        groups.append(
            DuplicateGroup(
                group_id=f"similar:{size_bytes}",
                type="similar",
                count=len(members),
                media_ids=media_ids,
                names=names,
                size_bytes=size_bytes,
                detail=_detail_text("similar", cnt),
            )
        )
    return groups


def scan_all(session: Session) -> list[DuplicateGroup]:
    """返回 完全重复 + 高度可信 + 候选 + 疑似重复(exact 优先)。"""
    return (
        scan_exact_duplicates(session)
        + scan_high_confidence(session)
        + scan_candidates(session)
        + scan_similar_candidates(session)
    )


def has_pending_hashes(session: Session) -> bool:
    """是否存在缺少 quick_hash(需要后台计算)的媒体。用于触发哈希任务。"""
    row = session.execute(
        sa.select(MediaCacheIndex.media_id)
        .where(
            MediaCacheIndex.media_path.is_not(None),
            MediaCacheIndex.quick_hash.is_(None),
        )
        .limit(1)
    ).first()
    return row is not None


__all__ = [
    "DuplicateGroup",
    "has_pending_hashes",
    "scan_all",
    "scan_candidates",
    "scan_exact_duplicates",
    "scan_high_confidence",
    "scan_similar_candidates",
]
