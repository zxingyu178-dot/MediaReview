"""阶段 6: 收藏 + 待删除队列服务测试(含真实文件删除)。"""

from __future__ import annotations

from datetime import UTC, datetime
from pathlib import Path

from app.db.models import LibrarySelection, MediaCacheIndex
from app.db.session import Database
from app.services import delete_queue, favorites, media_index


def _make_db(data_root: Path) -> Database:
    db = Database(data_root / "database" / "mediareview.db")
    db.create_all()
    return db


def _seed_media(
    db: Database,
    media_id: str,
    media_path: str,
    size: int | None = None,
    *,
    modified_at: datetime | None = None,
) -> None:
    # 让媒体属于一个"已选"的媒体库,满足删除前的库归属校验
    with db.session() as s:
        s.merge(
            LibrarySelection(
                jellyfin_id="lib-movies", name="电影", collection_type="movies", selected=True
            )
        )
        s.commit()
    p = Path(media_path)
    if size is None:
        size = p.stat().st_size if p.is_file() else 0
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
                media_path=media_path,
                modified_at=modified_at,
            )
        )
        s.commit()


def test_favorite_add_list_remove(tmp_path: Path) -> None:
    db = _make_db(tmp_path)
    media_id = "aaa" * 8
    with db.session() as s:
        assert favorites.add(s, media_id) is True
        assert favorites.add(s, media_id) is False
        assert media_id in favorites.list_ids(s)
        assert favorites.remove(s, media_id) is True
        assert favorites.remove(s, media_id) is False
        assert media_id not in favorites.list_ids(s)
    db.dispose()


def test_delete_queue_two_step_lifecycle(tmp_path: Path) -> None:
    db = _make_db(tmp_path)
    media_id = "bbb" * 8
    real_file = tmp_path / "real.mp4"
    real_file.write_bytes(b"media")

    _seed_media(db, media_id, str(real_file))

    with db.session() as s:
        assert delete_queue.enqueue(s, media_id, size_bytes=real_file.stat().st_size) is True
        assert delete_queue.enqueue(s, media_id) is False  # 已入队
        assert len(delete_queue.list_queue(s)) == 1
        # 撤销后再入队
        assert delete_queue.dequeue(s, media_id) is True
        assert delete_queue.enqueue(s, media_id) is True
        s.commit()

    # commit 后真实删除文件并清理索引/队列
    with db.session() as s:
        outcome = delete_queue.commit_all(s)
        s.commit()
    assert outcome[media_id] == "success"
    assert not real_file.exists()
    with db.session() as s:
        assert media_index.get_cached_media(s, media_id) is None
        assert len(delete_queue.list_queue(s)) == 0
    db.dispose()


def test_delete_queue_missing_path_still_cleans(tmp_path: Path) -> None:
    db = _make_db(tmp_path)
    media_id = "ccc" * 8
    _seed_media(db, media_id, "")  # 无路径
    with db.session() as s:
        delete_queue.enqueue(s, media_id)
        outcome = delete_queue.commit_all(s)
        s.commit()
    assert outcome[media_id] == "missing"
    with db.session() as s:
        assert media_index.get_cached_media(s, media_id) is None
    db.dispose()


def _mtime_datetime(path: Path) -> datetime:
    """把文件 mtime 转成 naive UTC datetime(与列存 modified_at 约定一致)。"""
    return datetime.fromtimestamp(path.stat().st_mtime, tz=UTC).replace(tzinfo=None)


def test_delete_protection_when_file_size_changed(tmp_path: Path) -> None:
    """源文件进入待删除后被修改(体积变化) -> 禁止永久删除,索引和水印保留。"""
    db = _make_db(tmp_path)
    media_id = "dd1" * 8
    real_file = tmp_path / "victim.mp4"
    real_file.write_bytes(b"original-content-v1")
    _seed_media(db, media_id, str(real_file), modified_at=_mtime_datetime(real_file))

    with db.session() as s:
        delete_queue.enqueue(s, media_id, size_bytes=real_file.stat().st_size)
        s.commit()

    # 修改源文件(体积变化)
    real_file.write_bytes(b"original-content-v1-and-some-extra-bytes-to-change-size")

    with db.session() as s:
        outcome = delete_queue.commit_all(s)
        s.commit()

    # 必须拒绝删除
    assert outcome[media_id] == "failed"
    assert real_file.exists()  # 文件未被删除
    with db.session() as s:
        assert media_index.get_cached_media(s, media_id) is not None
        assert delete_queue.list_queue(s)[0].status == "failed"
        from app.services import audit

        assert any(
            a.action == "delete_commit" and "file_size_changed" in (a.detail or "")
            for a in audit.recent(s)
        )
    db.dispose()


def test_delete_protection_when_file_replaced_same_size(tmp_path: Path) -> None:
    """源文件被替换为同体积的不同文件(mtime 变化) -> 仍禁止永久删除。"""
    db = _make_db(tmp_path)
    media_id = "dd2" * 8
    real_file = tmp_path / "replaced.mp4"
    real_file.write_bytes(b"A" * 100)  # 100 字节
    original_mtime = real_file.stat().st_mtime
    _seed_media(db, media_id, str(real_file), modified_at=_mtime_datetime(real_file))

    with db.session() as s:
        delete_queue.enqueue(s, media_id, size_bytes=100)
        s.commit()

    # 同体积替换但内容不同,并把 mtime 推到索引记录之后
    replaced = tmp_path / "replaced_new.mp4"
    replaced.write_bytes(b"B" * 100)
    import os as _os

    _os.replace(replaced, real_file)
    new_time = original_mtime + 3600
    _os.utime(real_file, (new_time, new_time))

    with db.session() as s:
        outcome = delete_queue.commit_all(s)
        s.commit()

    assert outcome[media_id] == "failed"
    assert real_file.exists()
    with db.session() as s:
        assert media_index.get_cached_media(s, media_id) is not None
        assert delete_queue.list_queue(s)[0].status == "failed"
    db.dispose()


def test_delete_protection_when_library_deselected(tmp_path: Path) -> None:
    """媒体所属库被取消勾选(不再允许管理) -> 拒绝永久删除。"""
    db = _make_db(tmp_path)
    media_id = "dd3" * 8
    real_file = tmp_path / "lib.mp4"
    real_file.write_bytes(b"content")
    _seed_media(db, media_id, str(real_file), modified_at=_mtime_datetime(real_file))

    with db.session() as s:
        delete_queue.enqueue(s, media_id, size_bytes=real_file.stat().st_size)
        s.commit()

    # 取消该库勾选
    with db.session() as s:
        media_index.apply_selection(s, [], known_ids={"lib-movies"})
        s.commit()

    with db.session() as s:
        outcome = delete_queue.commit_all(s)
        s.commit()

    assert outcome[media_id] == "failed"
    assert real_file.exists()
    with db.session() as s:
        assert delete_queue.list_queue(s)[0].status == "failed"
    db.dispose()
