"""重复检测接口(Task D: 持久化分组 + 后台扫描任务,只读不自动删除)。

- POST /duplicates/scan            编排后台重复扫描(进度/暂停/取消/恢复)
- GET  /duplicates/status          最近一次扫描任务状态
- GET  /duplicates                 最近扫描的持久化分组(exact 优先)
- GET  /duplicates/exact|high|candidates|similar  按类型筛选
- POST /duplicates/{group_id}/keep 记录双栏对比页的人工"保留"选择

首次访问若媒体缺少哈希,会自动编排哈希后台任务(hash I/O 不阻塞本请求)。
"""

from __future__ import annotations

from datetime import datetime

from fastapi import APIRouter, Depends, Query
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from app.api.v1.auth import require_auth
from app.api.v1.media import media_thumbnail_url, row_source_version
from app.core.errors import MediaNotFoundError, NotFoundError
from app.core.responses import Envelope, ok
from app.db.session import get_db
from app.services import duplicate_scanner, hash_tasks
from app.services.tasks import safe_task_view

router = APIRouter(prefix="/duplicates", tags=["duplicates"])


class DuplicateSummaryView(BaseModel):
    """重复媒体摘要(Stage 8C §12): 计数 + 最近扫描任务状态,不返回分组本体。

    Stage 8C.1 §18/§19: 计数始终来自**最近一次成功扫描**的持久化结果;
    重扫失败时计数不变(不是 0),`scan_status=failed` 由客户端叠加提示;
    `last_successful_scan_at` 供页面显示"上次扫描时间"。
    """

    exact_groups: int
    similar_groups: int
    scan_task_id: str | None = None
    scan_status: str | None = None
    scan_progress: int = 0
    last_successful_scan_at: datetime | None = None


class DuplicateDetailMemberView(BaseModel):
    """分组详情内的成员媒体摘要(Stage 8C §33): 一次批量 SQL 取回,客户端零 N+1。

    `available`(Stage 8C.1 §20): 扫描后媒体可能已失效 —— 客户端必须据此显示
    「文件已不可用」,不得展示假封面/假可用状态。
    """

    media_id: str
    name: str
    keep: bool = False
    available: bool = True
    size_bytes: int | None = None
    duration_ms: int | None = None
    width: int | None = None
    height: int | None = None
    media_type: str | None = None
    cover_url: str | None = None


class DuplicateGroupDetailView(BaseModel):
    group_id: str
    type: str
    detail: str = ""
    count: int
    size_bytes: int
    duration_ms: int | None = None
    members: list[DuplicateDetailMemberView] = []


class DuplicateMemberView(BaseModel):
    media_id: str
    name: str
    keep: bool = False


class DuplicateGroupView(BaseModel):
    group_id: str
    type: str  # exact | high | candidate | similar
    count: int
    media_ids: list[str]
    names: list[str]
    size_bytes: int
    duration_ms: int | None = None
    detail: str = ""
    members: list[DuplicateMemberView] = []


class DuplicateGroupPageView(BaseModel):
    """重复分组分页视图(Stage 8C.1 §11): items/total/page/page_size。

    扫描阶段不再截断(旧 200 组上限已删除),数量控制统一放在读取侧分页。
    """

    items: list[DuplicateGroupView] = []
    total: int = 0
    page: int = 1
    page_size: int = 50


class KeepBody(BaseModel):
    media_id: str = Field(min_length=1)
    keep: bool


def _view(group: duplicate_scanner.DuplicateGroup) -> DuplicateGroupView:
    return DuplicateGroupView(
        group_id=group.group_id,
        type=group.type,
        count=group.count,
        media_ids=group.media_ids,
        names=group.names,
        size_bytes=group.size_bytes,
        duration_ms=group.duration_ms,
        detail=group.detail,
        members=[
            DuplicateMemberView(media_id=mid, name=name, keep=group.keep.get(mid, False))
            for mid, name in zip(group.media_ids, group.names, strict=False)
        ],
    )


def _ensure_hash(db: Session) -> None:
    """若存在待哈希媒体则编排后台哈希任务(幂等),并在当前事务提交。"""
    if duplicate_scanner.has_pending_hashes(db):
        hash_tasks.schedule_duplicate_hash(db)
        db.commit()


@router.post("/scan", response_model=Envelope[dict])
def scan(_auth=Depends(require_auth), db: Session = Depends(get_db)) -> Envelope[dict]:
    _ensure_hash(db)
    task = duplicate_scanner.schedule_duplicate_scan(db)
    db.commit()
    return ok(safe_task_view(task))


@router.get("/summary", response_model=Envelope[DuplicateSummaryView])
def summary(
    _auth=Depends(require_auth), db: Session = Depends(get_db)
) -> Envelope[DuplicateSummaryView]:
    """重复媒体摘要: 完全/疑似分组计数(单条 SQL) + 最近一次扫描任务状态。

    重扫失败时计数保持上一份成功结果(§18),另附 `last_successful_scan_at`(§19)。
    """
    counts = duplicate_scanner.persisted_group_counts(db)
    task = duplicate_scanner.latest_duplicate_scan(db)
    view = safe_task_view(task) if task is not None else None
    success = duplicate_scanner.last_successful_scan(db)
    return ok(
        DuplicateSummaryView(
            exact_groups=counts.get("exact", 0),
            similar_groups=counts.get("similar", 0),
            scan_task_id=(view or {}).get("task_id"),
            scan_status=(view or {}).get("status"),
            scan_progress=int((view or {}).get("progress") or 0),
            last_successful_scan_at=success.finished_at if success is not None else None,
        )
    )


@router.get("/status", response_model=Envelope[dict])
def status(_auth=Depends(require_auth), db: Session = Depends(get_db)) -> Envelope[dict]:
    task = duplicate_scanner.latest_duplicate_scan(db)
    return ok(safe_task_view(task) if task else {"task_id": None})


@router.get("", response_model=Envelope[list[DuplicateGroupView]])
def persisted_all(
    _auth=Depends(require_auth), db: Session = Depends(get_db)
) -> Envelope[list[DuplicateGroupView]]:
    _ensure_hash(db)
    return ok([_view(g) for g in duplicate_scanner.persisted_groups(db)])


def _page_view(
    db: Session, group_type: str, page: int, page_size: int
) -> DuplicateGroupPageView:
    groups, total = duplicate_scanner.persisted_groups_page(
        db, group_type, page=page, page_size=page_size
    )
    return DuplicateGroupPageView(
        items=[_view(g) for g in groups],
        total=total,
        page=page,
        page_size=page_size,
    )


@router.get("/exact", response_model=Envelope[DuplicateGroupPageView])
def persisted_exact(
    page: int = Query(default=1, ge=1),
    page_size: int = Query(default=50, ge=1, le=200),
    _auth=Depends(require_auth),
    db: Session = Depends(get_db),
) -> Envelope[DuplicateGroupPageView]:
    """完全重复分组分页(Stage 8C.1 §11): 扫描结果全量持久化,读取侧分页。"""
    _ensure_hash(db)
    return ok(_page_view(db, "exact", page, page_size))


@router.get("/high", response_model=Envelope[list[DuplicateGroupView]])
def persisted_high(
    _auth=Depends(require_auth), db: Session = Depends(get_db)
) -> Envelope[list[DuplicateGroupView]]:
    return ok([_view(g) for g in duplicate_scanner.persisted_groups(db, "high")])


@router.get("/candidates", response_model=Envelope[list[DuplicateGroupView]])
def persisted_candidates(
    _auth=Depends(require_auth), db: Session = Depends(get_db)
) -> Envelope[list[DuplicateGroupView]]:
    _ensure_hash(db)
    return ok([_view(g) for g in duplicate_scanner.persisted_groups(db, "candidate")])


@router.get("/similar", response_model=Envelope[DuplicateGroupPageView])
def persisted_similar(
    page: int = Query(default=1, ge=1),
    page_size: int = Query(default=50, ge=1, le=200),
    _auth=Depends(require_auth),
    db: Session = Depends(get_db),
) -> Envelope[DuplicateGroupPageView]:
    """疑似重复分组分页(Stage 8C.1 §11): 与 exact 相同的分页合同。"""
    return ok(_page_view(db, "similar", page, page_size))


@router.get("/{group_id}", response_model=Envelope[DuplicateGroupDetailView])
def group_detail(
    group_id: str,
    _auth=Depends(require_auth),
    db: Session = Depends(get_db),
) -> Envelope[DuplicateGroupDetailView]:
    """分组详情(Stage 8C §33): 分组 + 成员 + 每个成员的媒体摘要,一次批量 SQL。"""
    detail = duplicate_scanner.group_detail(db, group_id)
    if detail is None:
        raise NotFoundError(message="重复分组不存在")
    return ok(
        DuplicateGroupDetailView(
            group_id=detail.group_id,
            type=detail.type,
            detail=detail.detail,
            count=detail.count,
            size_bytes=detail.size_bytes,
            duration_ms=detail.duration_ms,
            members=[
                DuplicateDetailMemberView(
                    media_id=member.media_id,
                    # 媒体索引行可能已缺失(刚被删除等):回退到分组内持久化姓名
                    name=member.media.name if member.media is not None else member.name,
                    keep=member.keep,
                    # 扫描后失效的成员:客户端据此显示「文件已不可用」(§20)
                    available=member.available,
                    size_bytes=member.media.size_bytes if member.media is not None else None,
                    duration_ms=member.media.duration_ms if member.media is not None else None,
                    width=member.media.width if member.media is not None else None,
                    height=member.media.height if member.media is not None else None,
                    media_type=member.media.media_type if member.media is not None else None,
                    cover_url=(
                        media_thumbnail_url(
                            member.media.media_id, row_source_version(member.media)
                        )
                        if member.media is not None
                        else None
                    ),
                )
                for member in detail.members
            ],
        )
    )


@router.post("/{group_id}/keep", response_model=Envelope[dict])
def keep(
    group_id: str,
    body: KeepBody,
    _auth=Depends(require_auth),
    db: Session = Depends(get_db),
) -> Envelope[dict]:
    changed = duplicate_scanner.set_keep(db, group_id, body.media_id, body.keep)
    if not changed:
        raise MediaNotFoundError(message="重复分组或成员不存在")
    db.commit()
    return ok({"group_id": group_id, "media_id": body.media_id, "keep": body.keep})


__all__ = ["router"]
