"""重复检测接口(受保护,只读扫描分组,不做自动删除)。

- GET /duplicates            全文: 完全重复 + 高度可信 + 候选 + 疑似重复
- GET /duplicates/exact      仅完全重复(byte-identical,需后台已算完整哈希)
- GET /duplicates/high       仅高度可信重复(采样哈希一致)
- GET /duplicates/candidates 仅候选筛选结果(等待后台哈希)
- GET /duplicates/similar    仅疑似重复

首次扫描若存在待计算哈希的媒体,会自动编排后台哈希任务(hash I/O 不阻塞本请求),
此时 exact/high 可能为空,需等后台任务完成后再查询。
"""

from __future__ import annotations

from fastapi import APIRouter, Depends
from pydantic import BaseModel
from sqlalchemy.orm import Session

from app.api.v1.auth import require_auth
from app.core.responses import Envelope, ok
from app.db.session import get_db
from app.services import duplicate_scanner, hash_tasks

router = APIRouter(prefix="/duplicates", tags=["duplicates"])


class DuplicateGroupView(BaseModel):
    group_id: str
    type: str  # exact | high | candidate | similar
    count: int
    media_ids: list[str]
    names: list[str]
    size_bytes: int
    duration_ms: int | None = None
    detail: str = ""


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
    )


def _ensure_hash(db: Session) -> None:
    """若存在待哈希媒体则编排后台任务(幂等),并在当前事务提交。"""
    if duplicate_scanner.has_pending_hashes(db):
        hash_tasks.schedule_duplicate_hash(db)
        db.commit()


@router.get("", response_model=Envelope[list[DuplicateGroupView]])
def scan_all(
    _auth=Depends(require_auth), db: Session = Depends(get_db)
) -> Envelope[list[DuplicateGroupView]]:
    _ensure_hash(db)
    return ok([_view(g) for g in duplicate_scanner.scan_all(db)])


@router.get("/exact", response_model=Envelope[list[DuplicateGroupView]])
def scan_exact(
    _auth=Depends(require_auth), db: Session = Depends(get_db)
) -> Envelope[list[DuplicateGroupView]]:
    _ensure_hash(db)
    return ok([_view(g) for g in duplicate_scanner.scan_exact_duplicates(db)])


@router.get("/high", response_model=Envelope[list[DuplicateGroupView]])
def scan_high(
    _auth=Depends(require_auth), db: Session = Depends(get_db)
) -> Envelope[list[DuplicateGroupView]]:
    return ok([_view(g) for g in duplicate_scanner.scan_high_confidence(db)])


@router.get("/candidates", response_model=Envelope[list[DuplicateGroupView]])
def scan_candidates(
    _auth=Depends(require_auth), db: Session = Depends(get_db)
) -> Envelope[list[DuplicateGroupView]]:
    _ensure_hash(db)
    return ok([_view(g) for g in duplicate_scanner.scan_candidates(db)])


@router.get("/similar", response_model=Envelope[list[DuplicateGroupView]])
def scan_similar(
    _auth=Depends(require_auth), db: Session = Depends(get_db)
) -> Envelope[list[DuplicateGroupView]]:
    return ok([_view(g) for g in duplicate_scanner.scan_similar_candidates(db)])


__all__ = ["router"]
