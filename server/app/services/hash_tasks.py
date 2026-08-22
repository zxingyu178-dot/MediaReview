"""重复检测哈希后台任务: 快速采样哈希 + (候选)完整 SHA-256。

磁盘 I/O 全部在独立线程(to_thread 的 handler)中执行,禁止阻塞 FastAPI 请求。

执行两阶段:
1. quick pass :为缺失 quick_hash 的已索引媒体计算采样哈希(头部 + 25/50/75% + 尾部),
                结果写入 quick_hash(用于"高度可信"判定,廉价)。
2. full pass  :仅对"候选重复"(size+duration 一致且多于一份)的媒体计算整文件 sha256,
                结果写入 sha256(用于 byte-identical / exact 判定)。
                非候选的媒体不做全量哈希,避免对大量大文件做无谓的全盘读取。

读取失败的文件写入哨兵 HASH_UNREADABLE(非 64 位十六进制哈希),避免重复重试,
同时在分组时会被排除(不会误判为重复)。

每批在独立短会话中提交,避免长时间持有 SQLite 写锁。
"""

from __future__ import annotations

import json
from collections.abc import Callable

import sqlalchemy as sa

from app.core.logging import get_logger
from app.db.models import BackgroundTask, MediaCacheIndex, utc_now
from app.db.session import Database
from app.services import hashing

TASK_TYPE_DUPLICATE_HASH = "duplicate_hash"

# 读取失败哨兵: 非 hex 哈希,分组时会被排除,且避免无限重试
HASH_UNREADABLE = "unreadable-io"

logger = get_logger("hash_tasks")

# 每批处理的媒体条数(控制单次提交大小与写锁持有时间)
_BATCH = 50


def schedule_duplicate_hash(session) -> BackgroundTask | None:
    """编排一个重复检测哈希任务(已存在 pending/running 则返回其本身)。"""
    existing = (
        session.query(BackgroundTask)
        .filter(
            BackgroundTask.type == TASK_TYPE_DUPLICATE_HASH,
            BackgroundTask.status.in_(("pending", "running")),
        )
        .first()
    )
    if existing is not None:
        return existing
    task = BackgroundTask(
        task_id=utc_now().strftime("%Y%m%d%H%M%S%f")[-14:],
        type=TASK_TYPE_DUPLICATE_HASH,
        status="pending",
        params=json.dumps({"scope": "all"}),
        progress=0,
    )
    session.add(task)
    session.flush()
    return task


def make_hash_handler(*, compute_full_sha: bool = True) -> Callable[[Database, str], None]:
    """构造可注册到 TaskManager 的重复检测哈希处理器。"""

    def _handler(database: Database, task_id: str) -> None:
        _execute(database, task_id, compute_full_sha=compute_full_sha)

    return _handler


def _execute(database: Database, task_id: str, *, compute_full_sha: bool) -> None:
    _quick_pass(database, task_id)
    if compute_full_sha:
        _full_pass(database, task_id)
    _finish_succeeded(database, task_id)


def _quick_pass(database: Database, task_id: str) -> None:
    """为缺失 quick_hash 的媒体计算采样哈希,分批短会话提交。"""
    while True:
        with database.session() as session:
            rows = (
                session.query(MediaCacheIndex)
                .filter(
                    MediaCacheIndex.media_path.is_not(None),
                    MediaCacheIndex.quick_hash.is_(None),
                )
                .limit(_BATCH)
                .all()
            )
            if not rows:
                return
            started = 0
            for media in rows:
                started += 1
                media.quick_hash = _safe_quick_hash(media.media_path)
            _bump_progress(session, task_id, step=started)
            session.commit()


def _full_pass(database: Database, task_id: str) -> None:
    """仅为候选重复媒体的缺失 sha256 计算整文件哈希,分批短会话提交。"""
    while True:
        with database.session() as session:
            rows = _candidate_sha_missing(session)
            if not rows:
                return
            started = 0
            for media in rows:
                started += 1
                if media.media_path:
                    media.sha256 = _safe_full_sha(media.media_path)
                else:
                    media.sha256 = HASH_UNREADABLE
            _bump_progress(session, task_id, step=started, base=50)
            session.commit()


def _candidate_sha_missing(session) -> list[MediaCacheIndex]:
    """需要整文件 sha256 的媒体: size+duration+quick_hash 一致 且 该组合多于一份。

    只有 quick_hash 相同且数量≥2 才做全量 SHA-256,避免两个 20GB 视频仅因
    size+duration 碰巧相同(内容不同 -> quick_hash 不同)就被整体读一遍。
    这是对"候选筛选(size+duration) -> quick_hash -> full sha256"的第三层收敛。
    """
    sub = (
        sa.select(
            MediaCacheIndex.size_bytes,
            MediaCacheIndex.duration_ms,
            MediaCacheIndex.quick_hash,
        )
        .where(
            MediaCacheIndex.size_bytes.is_not(None),
            MediaCacheIndex.duration_ms.is_not(None),
            MediaCacheIndex.quick_hash.is_not(None),
            MediaCacheIndex.quick_hash != HASH_UNREADABLE,
        )
        .group_by(
            MediaCacheIndex.size_bytes,
            MediaCacheIndex.duration_ms,
            MediaCacheIndex.quick_hash,
        )
        .having(sa.func.count(MediaCacheIndex.media_id) > 1)
        .subquery()
    )
    return (
        session.query(MediaCacheIndex)
        .join(
            sub,
            sa.and_(
                MediaCacheIndex.size_bytes == sub.c.size_bytes,
                MediaCacheIndex.duration_ms == sub.c.duration_ms,
                MediaCacheIndex.quick_hash == sub.c.quick_hash,
            ),
        )
        .filter(
            MediaCacheIndex.media_path.is_not(None),
            MediaCacheIndex.sha256.is_(None),
        )
        .limit(_BATCH)
        .all()
    )


def _safe_quick_hash(path: str | None) -> str | None:
    if not path:
        return HASH_UNREADABLE
    try:
        return hashing.quick_hash(path)
    except OSError:
        return HASH_UNREADABLE


def _safe_full_sha(path: str) -> str | None:
    try:
        return hashing.full_sha256(path)
    except OSError:
        return HASH_UNREADABLE


def _bump_progress(session, task_id: str, *, step: int = 1, base: int = 0) -> None:
    task = session.get(BackgroundTask, task_id)
    if task is None:
        return
    # 以固定小步长推进,避免数值超出;full pass 从 base 之上继续
    task.progress = min(99, base + min(task.progress + step, 99 - base))


def _finish_succeeded(database: Database, task_id: str) -> None:
    with database.session() as session:
        task = session.get(BackgroundTask, task_id)
        if task is None:
            return
        task.status = "succeeded"
        task.progress = 100
        task.result = json.dumps({"ok": True})
        task.finished_at = utc_now()
        session.commit()


__all__ = [
    "HASH_UNREADABLE",
    "TASK_TYPE_DUPLICATE_HASH",
    "make_hash_handler",
    "schedule_duplicate_hash",
]
