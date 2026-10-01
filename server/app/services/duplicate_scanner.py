"""重复检测服务: 多级哈希分组(候选筛选 -> 高度可信 -> byte-identical)。

依据 PRODUCT_SPEC 与 Stage-07 验收修正:size + duration **只能作为候选筛选**,
绝不直接等同于 exact。只有经过完整文件校验(sha256)才可标记为真正的完全重复。

分组语义:
- type="candidate":  size+duration 一致,但 quick_hash 尚未计算(等待后台哈希任务)。
- type="high":       size+duration 一致 且 quick_hash 一致(高度可信重复,仍需人工复核)。
- type="exact":      size+duration 一致 且整文件 sha256 一致(byte-identical 完全重复)。
- type="similar":    大小一致但时长/分辨率差异(疑似重复基础能力)。

Task D 起重复分组持久化到 ``duplicate_group`` / ``duplicate_group_member``:
- ``run_duplicate_scan`` 是注册到 TaskManager 的后台处理器(进度 + 协作取消/暂停);
- ``persisted_groups`` 从 DB 读取最近一次扫描结果(Android 只读展示);
- ``set_keep`` 记录双栏对比页的人工"保留"选择(完全/疑似都不自动删除)。

本模块只读已被后台任务写好的哈希字段,**不做任何磁盘 I/O**;真实文件读写在
hash_tasks 后台任务线程中完成,避免阻塞 FastAPI 请求与 SQLite 写锁(AGENTS.md)。
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass, field

import sqlalchemy as sa
from sqlalchemy.orm import Session

from app.core.logging import get_logger
from app.db.models import (
    BackgroundTask,
    DuplicateGroupMember,
    MediaCacheIndex,
    utc_now,
)
from app.db.models import (
    DuplicateGroup as DuplicateGroupRow,
)
from app.db.session import Database
from app.services.hash_contract import is_full_sha256
from app.services.hash_tasks import HASH_UNREADABLE

logger = get_logger("duplicate_scan")

# TaskManager 注册的任务类型
TASK_TYPE_DUPLICATE_SCAN = "duplicate_scan"

# 疑似重复: 批量查询单次上限,避免一次返回过大
_MAX_GROUPS = 200
# 疑似重复的时长差异容忍(秒): 相同大小但时长相差超过该值的视为"疑似需人工确认"
_DURATION_TOLERANCE_MS = 3_000


@dataclass(frozen=True)
class DuplicateGroup:
    """一组重复媒体。type: candidate / high / exact / similar。

    keep 记录双栏对比页的人工"保留"选择(media_id -> bool),仅持久化读取时填充。
    """

    group_id: str
    type: str
    count: int
    media_ids: list[str]
    names: list[str]
    size_bytes: int
    duration_ms: int | None = None
    detail: str = ""
    keep: dict[str, bool] = field(default_factory=dict)


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


def _is_valid_quick_hash(h: str | None) -> bool:
    """采样哈希字段有效: 非空且不是"读取失败"哨兵。"""
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
            if not is_full_sha256(sha):
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
            if not _is_valid_quick_hash(qh) or media_id in exact_assigned:
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


def _resolution(media) -> int | None:
    """媒体的分辨率(width*height);缺任一维度返回 None。"""
    if media.width is None or media.height is None:
        return None
    return int(media.width) * int(media.height)


def scan_similar_candidates(session: Session) -> list[DuplicateGroup]:
    """疑似重复: 大小一致但时长差异超容忍值 或 分辨率不同(可能为转码/裁剪等)。

    仅作为"疑似基础能力",不自动删除(AGENTS.md),供独立页面提示用户。
    相似判定只使用时长/大小/分辨率(不做内容哈希)。
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
                MediaCacheIndex.width,
                MediaCacheIndex.height,
            ).where(MediaCacheIndex.size_bytes == size_bytes)
        ).all()
        if len(members) < 2:
            continue
        durations = [m.duration_ms for m in members if m.duration_ms is not None]
        resolutions = {r for m in members if (r := _resolution(m)) is not None}
        duration_differs = bool(durations) and (
            max(durations) - min(durations) > _DURATION_TOLERANCE_MS
        )
        # 时长差异超容忍 或 分辨率不同 都算疑似(需人工确认)
        if not duration_differs and len(resolutions) <= 1:
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


# ---- Task D: 持久化分组 + 后台扫描任务 ----


def schedule_duplicate_scan(session: Session) -> BackgroundTask:
    """编排重复扫描后台任务;已有 pending/running 扫描则复用(幂等)。"""
    existing = session.scalars(
        sa.select(BackgroundTask).where(
            BackgroundTask.type == "duplicate_scan",
            BackgroundTask.status.in_(("pending", "running", "paused")),
        )
    ).first()
    if existing is not None:
        return existing
    task = BackgroundTask(
        task_id=uuid.uuid4().hex,
        type="duplicate_scan",
        status="pending",
        params="{}",
        progress=0,
    )
    session.add(task)
    session.flush()
    return task


def latest_duplicate_scan(session: Session) -> BackgroundTask | None:
    """最近一次重复扫描任务(用于前端展示进度/状态)。"""
    return session.scalars(
        sa.select(BackgroundTask)
        .where(BackgroundTask.type == "duplicate_scan")
        .order_by(BackgroundTask.created_at.desc())
        .limit(1)
    ).first()


def persisted_groups(session: Session, group_type: str | None = None) -> list[DuplicateGroup]:
    """从 DB 读取最近一次扫描的持久化分组(Android 只读展示)。"""
    stmt = sa.select(DuplicateGroupRow)
    if group_type:
        stmt = stmt.where(DuplicateGroupRow.type == group_type)
    groups: list[DuplicateGroup] = []
    for row in session.scalars(stmt.order_by(DuplicateGroupRow.size_bytes.desc())).all():
        members = list(
            session.scalars(
                sa.select(DuplicateGroupMember).where(DuplicateGroupMember.group_id == row.group_id)
            ).all()
        )
        groups.append(
            DuplicateGroup(
                group_id=row.group_id,
                type=row.type,
                count=row.count,
                media_ids=[m.media_id for m in members],
                names=[m.name for m in members],
                size_bytes=row.size_bytes or 0,
                duration_ms=row.duration_ms,
                detail=row.detail or "",
                keep={m.media_id: m.keep for m in members},
            )
        )
    return groups


def set_keep(session: Session, group_id: str, media_id: str, keep: bool) -> bool:
    """记录双栏对比页的人工"保留"选择;分组/成员不存在返回 False。"""
    row = session.get(DuplicateGroupMember, (group_id, media_id))
    if row is None:
        return False
    row.keep = keep
    session.flush()
    return True


def persisted_group_counts(session: Session) -> dict[str, int]:
    """已持久化分组计数 ``{type: count}``(单条 SQL,供 Organize 摘要使用)。

    Stage 8C §12: 首页只需计数,不得为此拉取全量分组与其成员。
    """
    rows = session.execute(
        sa.select(DuplicateGroupRow.type, sa.func.count(DuplicateGroupRow.group_id)).group_by(
            DuplicateGroupRow.type
        )
    ).all()
    return {str(group_type): int(count) for group_type, count in rows}


@dataclass(frozen=True)
class DuplicateMemberDetail:
    """分组详情内的单个成员: 持久化姓名/保留选择 + 可选的媒体索引行。"""

    media_id: str
    name: str
    keep: bool
    media: MediaCacheIndex | None


@dataclass(frozen=True)
class DuplicateGroupDetail:
    """分组详情(Stage 8C §33): 分组元数据 + 成员媒体摘要。"""

    group_id: str
    type: str
    detail: str
    count: int
    size_bytes: int
    duration_ms: int | None
    members: list[DuplicateMemberDetail]


def group_detail(session: Session, group_id: str) -> DuplicateGroupDetail | None:
    """读取分组详情;分组不存在返回 None。

    成员媒体摘要用**一次批量 SQL**(``media_id IN (...)``)取回,绝不逐条查询
    (Stage 8C §33: Android 侧禁止 N+1,Server 内部同样不做 N+1)。
    """
    row = session.get(DuplicateGroupRow, group_id)
    if row is None:
        return None
    members = list(
        session.scalars(
            sa.select(DuplicateGroupMember)
            .where(DuplicateGroupMember.group_id == group_id)
            .order_by(DuplicateGroupMember.media_id)
        ).all()
    )
    media_by_id: dict[str, MediaCacheIndex] = {}
    media_ids = [m.media_id for m in members]
    if media_ids:
        media_by_id = {
            media.media_id: media
            for media in session.scalars(
                sa.select(MediaCacheIndex).where(MediaCacheIndex.media_id.in_(media_ids))
            ).all()
        }
    return DuplicateGroupDetail(
        group_id=row.group_id,
        type=row.type,
        detail=row.detail or "",
        count=row.count,
        size_bytes=row.size_bytes or 0,
        duration_ms=row.duration_ms,
        members=[
            DuplicateMemberDetail(
                media_id=m.media_id,
                name=m.name,
                keep=m.keep,
                media=media_by_id.get(m.media_id),
            )
            for m in members
        ],
    )


def _task_status(database: Database, task_id: str) -> str | None:
    with database.session() as session:
        task = session.get(BackgroundTask, task_id)
        return task.status if task is not None else None


def _persist_group_rows(session: Session, group: DuplicateGroup) -> None:
    """写入单个分组 + 成员(幂等: 按 group_id 覆盖)。"""
    session.merge(
        DuplicateGroupRow(
            group_id=group.group_id,
            type=group.type,
            size_bytes=group.size_bytes,
            duration_ms=group.duration_ms,
            count=group.count,
            detail=group.detail,
        )
    )
    for media_id, name in zip(group.media_ids, group.names, strict=False):
        session.merge(
            DuplicateGroupMember(
                group_id=group.group_id,
                media_id=media_id,
                name=name,
                fingerprint=f"{group.type}:{media_id}",
                keep=False,
            )
        )


def run_duplicate_scan(database: Database, task_id: str) -> None:
    """TaskManager 注册的重复扫描处理器:计算分组并持久化,支持进度/协作取消/暂停。

    在 to_thread 线程内运行;每个阶段用短会话提交,阶段间检查任务状态:
    - cancelled: 停止并保留部分结果;
    - paused:    停止并保留部分结果,resume 后重新执行(幂等覆盖)。
    """
    logger.info("执行重复扫描任务 %s", task_id)
    try:
        _duplicate_scan_pass(database, task_id, scan_all)
        _finish_scan(database, task_id)
    except Exception as exc:  # noqa: BLE001 - 后台任务失败记录并置 failed
        logger.exception("重复扫描失败")
        _fail_scan(database, task_id, str(exc))


def _duplicate_scan_pass(database: Database, task_id: str, scan_fn) -> None:
    """执行一轮完整分组并持久化;期间检查取消/暂停。"""
    with database.session() as session:
        # 清空旧分组(保持最近一次扫描一致)
        session.execute(sa.delete(DuplicateGroupMember))
        session.execute(sa.delete(DuplicateGroupRow))
        task = session.get(BackgroundTask, task_id)
        if task is not None:
            task.progress = 5
        session.commit()

    groups = _compute_all(database)
    total = max(len(groups), 1)
    for idx, group in enumerate(groups, start=1):
        # 只在 running 状态继续;cancelled/paused(外部协作)/其他终态都停止
        if _task_status(database, task_id) != "running":
            break
        with database.session() as session:
            _persist_group_rows(session, group)
            task = session.get(BackgroundTask, task_id)
            if task is not None:
                task.progress = 5 + int(idx / total * 90)
            session.commit()


def _compute_all(database: Database) -> list[DuplicateGroup]:
    with database.session() as session:
        return scan_all(session)


def _finish_scan(database: Database, task_id: str) -> None:
    with database.session() as session:
        won = session.execute(
            sa.update(BackgroundTask)
            .where(
                BackgroundTask.task_id == task_id,
                BackgroundTask.status == "running",
            )
            .values(status="succeeded", progress=100, error=None, finished_at=utc_now())
            .returning(BackgroundTask.task_id)
        ).first()
        session.commit()
        return won is not None


def _fail_scan(database: Database, task_id: str, error: str) -> None:
    with database.session() as session:
        session.execute(
            sa.update(BackgroundTask)
            .where(BackgroundTask.task_id == task_id)
            .values(status="failed", error=error, finished_at=utc_now())
        )
        session.commit()


__all__ = [
    "DuplicateGroup",
    "DuplicateGroupDetail",
    "DuplicateMemberDetail",
    "group_detail",
    "has_pending_hashes",
    "latest_duplicate_scan",
    "persisted_group_counts",
    "persisted_groups",
    "run_duplicate_scan",
    "scan_all",
    "scan_candidates",
    "scan_exact_duplicates",
    "scan_high_confidence",
    "scan_similar_candidates",
    "schedule_duplicate_scan",
    "set_keep",
]
