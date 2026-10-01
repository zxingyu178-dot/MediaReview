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

import json
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
from app.services import media_index
from app.services.hash_contract import is_full_sha256
from app.services.hash_tasks import HASH_UNREADABLE

logger = get_logger("duplicate_scan")

# TaskManager 注册的任务类型
TASK_TYPE_DUPLICATE_SCAN = "duplicate_scan"

# 疑似重复的时长差异容忍(秒): 相同大小但时长相差超过该值的视为"疑似需人工确认"
_DURATION_TOLERANCE_MS = 3_000

# Stage 8C.2 §17~§19: 扫描期间媒体库勾选发生变化 → 本次扫描结果失效(不替换、不自动重扫)
ERROR_LIBRARY_SELECTION_CHANGED = "library_selection_changed"
_ERROR_LIBRARY_SELECTION_CHANGED = ERROR_LIBRARY_SELECTION_CHANGED


@dataclass(frozen=True)
class DuplicateScanScope:
    """一次重复扫描的**冻结范围**(Stage 8C.2 §13~§16)。

    扫描正式开始时对已选媒体库取一次快照并写进 `BackgroundTask.params`,
    四个阶段(exact / high / candidate / similar)、pause→resume 全部复用同一快照,
    绝不各阶段重新读取"当前选择",否则会出现"同一份结果混合两个范围"的脏数据。
    """

    library_ids: tuple[str, ...]

    @classmethod
    def of(cls, library_ids) -> "DuplicateScanScope":
        return cls(library_ids=tuple(sorted(str(i) for i in library_ids)))


def eligible_duplicate_conditions(session: Session, library_ids=None) -> list:
    """重复检测的**统一数据范围**(Stage 8C.1 §5/§6; Stage 8C.2 §15): 仍可用 且 属于指定媒体库。

    - `MediaCacheIndex.is_available == True`: 失效/已删除媒体绝不进入新重复结果;
    - `library_id ∈ library_ids`: 未勾选媒体库绝不参与扫描;
    - 没有已勾选媒体库时返回 `false()`(明确空结果,绝不偷偷扫描全库)。

    ``library_ids`` 显式传入时使用该快照(扫描期间范围冻结);省略时才读取当前选择
    (交互式查询路径,如 has_pending_hashes)。

    exact / high / candidate / similar / has_pending / 详情成员判定 全部共用本条件,
    禁止各函数自行拼接导致"exact 过滤 selected、similar 却扫全库"。
    """
    targets = (
        media_index.selected_library_ids(session) if library_ids is None else list(library_ids)
    )
    if not targets:
        return [sa.false()]
    return [
        MediaCacheIndex.is_available.is_(True),
        MediaCacheIndex.library_id.in_(targets),
    ]


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


def _candidate_pairs(session: Session, library_ids=None) -> list[tuple[int, int]]:
    """候选筛选: 大小 + 时长 均一致且不止一份的组合(size_bytes, duration_ms)。

    Stage 8C.1 §9: 检测阶段**禁止**人为截断(旧 `_MAX_GROUPS = 200` 会漏掉真实存在的
    第 201+ 组重复)。数量控制一律交给 API 层分页,不在扫描阶段丢数据。
    """
    rows = session.execute(
        sa.select(
            MediaCacheIndex.size_bytes,
            MediaCacheIndex.duration_ms,
            sa.func.count(MediaCacheIndex.media_id).label("cnt"),
        )
        .where(
            MediaCacheIndex.size_bytes.is_not(None),
            MediaCacheIndex.duration_ms.is_not(None),
            *eligible_duplicate_conditions(session, library_ids),
        )
        .group_by(MediaCacheIndex.size_bytes, MediaCacheIndex.duration_ms)
        .having(sa.func.count(MediaCacheIndex.media_id) > 1)
        .order_by(MediaCacheIndex.size_bytes.desc())
    ).all()
    return [(int(sz), int(dur)) for sz, dur, _cnt in rows]


def _is_valid_quick_hash(h: str | None) -> bool:
    """采样哈希字段有效: 非空且不是"读取失败"哨兵。"""
    return bool(h) and h != HASH_UNREADABLE


def _pair_members(
    session: Session, size_bytes: int, duration_ms: int, library_ids=None
) -> list[tuple[str, str, str | None, str | None]]:
    """返回某候选组合内的成员 (media_id, name, quick_hash, sha256)。

    成员查询与候选发现共用同一 eligible 范围(§6): 失效/未勾选媒体不得混入分组。
    """
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
            *eligible_duplicate_conditions(session, library_ids),
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


def scan_exact_duplicates(session: Session, library_ids=None) -> list[DuplicateGroup]:
    """完全重复(byte-identical): 大小+时长一致 且 整文件 sha256 一致。

    只有已由后台任务计算 full sha256 的媒体才会进入;未计算的一律不得判定为 exact。
    这保证"两个大小与时长相同但内容不同的文件"绝不会被标记为完全重复。
    """
    groups: list[DuplicateGroup] = []
    for size_bytes, duration_ms in _candidate_pairs(session, library_ids):
        members = _pair_members(session, size_bytes, duration_ms, library_ids)
        # 按 sha256 进一步切分;sha256 无效(None 或读取失败哨兵)的成员排除在外
        buckets: dict[str, list[tuple[str, str]]] = {}
        for media_id, name, _qh, sha in members:
            if not is_full_sha256(sha):
                continue
            buckets.setdefault(sha, []).append((media_id, name))
        groups.extend(_bucket_groups("exact", size_bytes, duration_ms, list(buckets.values())))
    return groups


def scan_high_confidence(session: Session, library_ids=None) -> list[DuplicateGroup]:
    """高度可信重复: 大小+时长一致 且 quick_hash 一致。

    排除已进入 exact 的成员,避免同一对既报 exact 又报 high。
    """
    return _dedup_orders(_high_groups(session, library_ids))


def _high_groups(session: Session, library_ids=None) -> list[DuplicateGroup]:
    """scan_high_confidence 的内部实现(返回未去重顺序的 high 分组)。"""
    groups: list[DuplicateGroup] = []
    exact_assigned = {
        mid for grp in scan_exact_duplicates(session, library_ids) for mid in grp.media_ids
    }
    for size_bytes, duration_ms in _candidate_pairs(session, library_ids):
        members = _pair_members(session, size_bytes, duration_ms, library_ids)
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


def scan_candidates(session: Session, library_ids=None) -> list[DuplicateGroup]:
    """候选筛选结果: 大小+时长一致 但 quick_hash 尚未计算(等待后台哈希)。"""
    groups: list[DuplicateGroup] = []
    for size_bytes, duration_ms in _candidate_pairs(session, library_ids):
        members = _pair_members(session, size_bytes, duration_ms, library_ids)
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


def scan_similar_candidates(session: Session, library_ids=None) -> list[DuplicateGroup]:
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
        .where(
            MediaCacheIndex.size_bytes.is_not(None),
            *eligible_duplicate_conditions(session, library_ids),
        )
        .group_by(MediaCacheIndex.size_bytes)
        .having(sa.func.count(MediaCacheIndex.media_id) > 1)
        .order_by(MediaCacheIndex.size_bytes.desc())
    ).all()

    for size_bytes, cnt in size_rows:
        members = session.execute(
            sa.select(
                MediaCacheIndex.media_id,
                MediaCacheIndex.name,
                MediaCacheIndex.duration_ms,
                MediaCacheIndex.width,
                MediaCacheIndex.height,
            ).where(
                MediaCacheIndex.size_bytes == size_bytes,
                *eligible_duplicate_conditions(session, library_ids),
            )
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


def scan_all(session: Session, library_ids=None) -> list[DuplicateGroup]:
    """返回 完全重复 + 高度可信 + 候选 + 疑似重复(exact 优先)。"""
    return (
        scan_exact_duplicates(session, library_ids)
        + scan_high_confidence(session, library_ids)
        + scan_candidates(session, library_ids)
        + scan_similar_candidates(session, library_ids)
    )


def has_pending_hashes(session: Session) -> bool:
    """是否存在缺少 quick_hash(需要后台计算)的媒体。用于触发哈希任务。

    只统计 eligible 范围内的媒体(§6): 失效/未勾选媒体不应触发扫描侧哈希编排。
    """
    row = session.execute(
        sa.select(MediaCacheIndex.media_id)
        .where(
            MediaCacheIndex.media_path.is_not(None),
            MediaCacheIndex.quick_hash.is_(None),
            *eligible_duplicate_conditions(session),
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


def last_successful_scan(session: Session) -> BackgroundTask | None:
    """最近一次 **succeeded** 的重复扫描任务(Stage 8C.1 §19: last_successful_scan_at)。"""
    return session.scalars(
        sa.select(BackgroundTask)
        .where(
            BackgroundTask.type == "duplicate_scan",
            BackgroundTask.status == "succeeded",
        )
        .order_by(BackgroundTask.finished_at.desc())
        .limit(1)
    ).first()


def _groups_from_rows(
    session: Session, rows: list[DuplicateGroupRow]
) -> list[DuplicateGroup]:
    """把分组行批量转换为领域对象: 成员用**一次 IN 查询**取回,禁止逐组 N+1。"""
    group_ids = [row.group_id for row in rows]
    members_by_group: dict[str, list[DuplicateGroupMember]] = {}
    if group_ids:
        for member in session.scalars(
            sa.select(DuplicateGroupMember)
            .where(DuplicateGroupMember.group_id.in_(group_ids))
            .order_by(DuplicateGroupMember.group_id, DuplicateGroupMember.media_id)
        ).all():
            members_by_group.setdefault(member.group_id, []).append(member)
    groups: list[DuplicateGroup] = []
    for row in rows:
        members = members_by_group.get(row.group_id, [])
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


def persisted_groups(session: Session, group_type: str | None = None) -> list[DuplicateGroup]:
    """从 DB 读取最近一次扫描的持久化分组(Android 只读展示)。"""
    stmt = sa.select(DuplicateGroupRow)
    if group_type:
        stmt = stmt.where(DuplicateGroupRow.type == group_type)
    rows = list(
        session.scalars(
            stmt.order_by(DuplicateGroupRow.size_bytes.desc(), DuplicateGroupRow.group_id)
        ).all()
    )
    return _groups_from_rows(session, rows)


def persisted_groups_page(
    session: Session, group_type: str, *, page: int, page_size: int
) -> tuple[list[DuplicateGroup], int]:
    """按类型分页读取持久化分组(Stage 8C.1 §11): 返回 ``(items, total)``。

    数量控制只发生在读取侧(API 分页),扫描阶段绝不截断;
    排序对全量结果稳定(`size_bytes desc, group_id`),跨页不会重复/漏项。
    """
    total = int(
        session.scalar(
            sa.select(sa.func.count(DuplicateGroupRow.group_id)).where(
                DuplicateGroupRow.type == group_type
            )
        )
        or 0
    )
    offset = (max(page, 1) - 1) * max(page_size, 1)
    rows = list(
        session.scalars(
            sa.select(DuplicateGroupRow)
            .where(DuplicateGroupRow.type == group_type)
            .order_by(DuplicateGroupRow.size_bytes.desc(), DuplicateGroupRow.group_id)
            .offset(offset)
            .limit(page_size)
        ).all()
    )
    return _groups_from_rows(session, rows), total


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
    """分组详情内的单个成员: 持久化姓名/保留选择 + 可选的媒体索引行。

    ``available``(Stage 8C.1 §20): 扫描后媒体可能已失效/被删除(is_available=false
    或索引行缺失),客户端必须据此显示「文件已不可用」,不得继续展示假可用状态。
    """

    media_id: str
    name: str
    keep: bool
    media: MediaCacheIndex | None
    available: bool


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
    details: list[DuplicateMemberDetail] = []
    for m in members:
        media = media_by_id.get(m.media_id)
        details.append(
            DuplicateMemberDetail(
                media_id=m.media_id,
                name=m.name,
                keep=m.keep,
                media=media,
                # 索引行缺失或已置 is_available=false 都视为"文件已不可用"(§20)
                available=media is not None and bool(media.is_available),
            )
        )
    return DuplicateGroupDetail(
        group_id=row.group_id,
        type=row.type,
        detail=row.detail or "",
        count=row.count,
        size_bytes=row.size_bytes or 0,
        duration_ms=row.duration_ms,
        members=details,
    )


def _task_status(database: Database, task_id: str) -> str | None:
    with database.session() as session:
        task = session.get(BackgroundTask, task_id)
        return task.status if task is not None else None


def _persist_group_rows(
    session: Session,
    group: DuplicateGroup,
    keeps: dict[tuple[str, str], bool] | None = None,
) -> None:
    """在**原子替换事务内**写入单个分组 + 成员。

    调用前旧分组已在同一事务内整体删除,因此这里用 `add` 直接插入
    (`merge` 会对每行额外发一次 SELECT,大结果集下纯属浪费)。

    ``keeps``(Stage 8C.2 §6~§11): 同一事务内读出的旧人工"保留"选择,
    仅当 **(group_id, media_id) 完全一致** 时才恢复;分组语义变化(拆并/改类型)
    或成员已消失时保持 `keep=False`,绝不自动继承。
    """
    session.add(
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
        session.add(
            DuplicateGroupMember(
                group_id=group.group_id,
                media_id=media_id,
                name=name,
                fingerprint=f"{group.type}:{media_id}",
                keep=bool(keeps.get((group.group_id, media_id), False)) if keeps else False,
            )
        )


def _existing_keeps(session: Session) -> dict[tuple[str, str], bool]:
    """读出当前持久化的全部人工"保留"选择,键为 ``(group_id, media_id)``。

    Stage 8C.2 §8: **禁止**只按 media_id 保存 —— 同一文件可能从旧 exact 分组
    迁移到新的 similar 分组,那不是"同一个人工选择",不能自动继承。
    """
    return {
        (row.group_id, row.media_id): bool(row.keep)
        for row in session.scalars(sa.select(DuplicateGroupMember)).all()
    }


def run_duplicate_scan(database: Database, task_id: str) -> None:
    """TaskManager 注册的重复扫描处理器:计算分组并原子替换持久化结果。

    在 to_thread 线程内运行;修复 Stage 8C.1 §13~§17 的"半成品"问题:

    - 计算期间**不触碰旧分组**(旧结果一直可读);
    - 阶段间检查任务状态,`paused` / `cancelled` / 其他终态立即放弃,旧结果完整保留;
    - 全部计算完成后,确认任务仍 `running`,才在**单事务内**删除旧分组 + 写入
      完整新分组 + 提交(读者只会看到旧一代或新一代完整结果,不会看到中间态);
    - 任何异常走 `failed`,同样不破坏上一份成功结果。
    """
    logger.info("执行重复扫描任务 %s", task_id)
    try:
        scope = _load_or_create_scope(database, task_id)
        groups = _compute_all(database, task_id, scope)
        if groups is None:
            # 计算中途被暂停/取消: 保留上一份 succeeded 结果,本次不产生任何分组
            logger.info("重复扫描任务 %s 在完成前停止,保留上一份结果", task_id)
            return
        replaced = _replace_persisted_groups(database, task_id, groups, scope)
        if replaced:
            _finish_scan(database, task_id)
    except Exception as exc:  # noqa: BLE001 - 后台任务失败记录并置 failed
        logger.exception("重复扫描失败")
        _fail_scan(database, task_id, str(exc))


def _load_or_create_scope(database: Database, task_id: str) -> DuplicateScanScope:
    """取得本次扫描的**冻结范围**(Stage 8C.2 §14~§16/§22)。

    首次执行时对"当前已选媒体库"取快照并写进 `BackgroundTask.params`;
    之后(含 pause → resume)一律复用 params 里的快照,绝不改用当前 Library Selection。
    """
    with database.session() as session:
        task = session.get(BackgroundTask, task_id)
        params: dict = {}
        if task is not None and task.params:
            try:
                loaded = json.loads(task.params)
                if isinstance(loaded, dict):
                    params = loaded
            except ValueError:
                params = {}
        stored = params.get("library_ids")
        if isinstance(stored, list):
            return DuplicateScanScope.of(stored)
        scope = DuplicateScanScope.of(media_index.selected_library_ids(session))
        params["library_ids"] = list(scope.library_ids)
        if task is not None:
            task.params = json.dumps(params)
        session.commit()
        return scope


def _compute_all(
    database: Database, task_id: str, scope: DuplicateScanScope
) -> list[DuplicateGroup] | None:
    """分阶段计算全部分组(不落库);任务不再 running 时返回 None。

    每个阶段用独立短会话;阶段间上报粗粒度进度并检查协作状态,
    使暂停/取消最多延迟一个阶段生效,而不是等到全部算完。
    四个阶段**共用同一个 Scope 快照**(§15),不会各自读取当前媒体库选择。
    """
    steps = (
        scan_exact_duplicates,
        scan_high_confidence,
        scan_candidates,
        scan_similar_candidates,
    )
    groups: list[DuplicateGroup] = []
    for idx, step in enumerate(steps):
        if _task_status(database, task_id) != "running":
            return None
        with database.session() as session:
            groups.extend(step(session, scope.library_ids))
        _set_progress(database, task_id, 10 + (idx + 1) * 20)
    return groups


def _set_progress(database: Database, task_id: str, progress: int) -> None:
    with database.session() as session:
        task = session.get(BackgroundTask, task_id)
        if task is not None and task.status == "running":
            task.progress = progress
        session.commit()


def _replace_persisted_groups(
    database: Database,
    task_id: str,
    groups: list[DuplicateGroup],
    scope: DuplicateScanScope,
) -> bool:
    """单事务原子替换: 旧一代结果 → 新一代完整结果(Stage 8C.1 §15 + Stage 8C.2 §11/§17~§19)。

    事务内依次完成,任何一步不满足即整体回滚、旧结果保持不变:

    1. 重查任务仍为 `running`(被 pause/cancel 则放弃);
    2. **Scope 冻结校验**: 当前已选媒体库必须等于本次扫描开始时冻结的快照,
       否则本次结果已失效 → 不替换,并把任务置 `failed: library_selection_changed`
       (§17~§19: 绝不用旧范围的结果冒充当前正式结果);
    3. 读取旧人工"保留"选择 → 删除旧分组 → 写入完整新分组(按 (group_id, media_id)
       恢复 keep,§6~§11),全部在同一事务内提交。

    替代方案(§15 scan_generation)需要新表字段与迁移,本阶段沿用 §16 等价语义。
    """
    with database.session() as session:
        task = session.get(BackgroundTask, task_id)
        if task is None or task.status != "running":
            return False
        live = DuplicateScanScope.of(media_index.selected_library_ids(session))
        if live.library_ids != scope.library_ids:
            # 扫描期间媒体库勾选发生变化: 本次扫描结果不再对应当前范围,拒绝发布
            task.status = "failed"
            task.error = _ERROR_LIBRARY_SELECTION_CHANGED
            task.finished_at = utc_now()
            session.commit()
            logger.info(
                "重复扫描任务 %s 放弃替换: 媒体库范围已变化 %s -> %s",
                task_id,
                list(scope.library_ids),
                list(live.library_ids),
            )
            return False
        keeps = _existing_keeps(session)
        session.execute(sa.delete(DuplicateGroupMember))
        session.execute(sa.delete(DuplicateGroupRow))
        for group in groups:
            _persist_group_rows(session, group, keeps)
        task.progress = 99
        session.commit()
        return True


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
    "ERROR_LIBRARY_SELECTION_CHANGED",
    "DuplicateGroup",
    "DuplicateGroupDetail",
    "DuplicateMemberDetail",
    "DuplicateScanScope",
    "eligible_duplicate_conditions",
    "group_detail",
    "has_pending_hashes",
    "last_successful_scan",
    "latest_duplicate_scan",
    "persisted_group_counts",
    "persisted_groups",
    "persisted_groups_page",
    "run_duplicate_scan",
    "scan_all",
    "scan_candidates",
    "scan_exact_duplicates",
    "scan_high_confidence",
    "scan_similar_candidates",
    "schedule_duplicate_scan",
    "set_keep",
]
