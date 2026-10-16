"""Stage8I: 尺寸回填与真实删除回执；全部使用隔离文件/数据库。"""

import os
import sys
from unittest.mock import patch

import pytest
from test_delete_nonce_11 import _enqueue, _make_db, _seed_media

from app.adapters.jellyfin.mapper import map_media_item
from app.adapters.jellyfin.models import JFItem
from app.db.models import DeleteQueue, MediaCacheIndex
from app.services import delete_queue, media_index


@pytest.mark.parametrize(
    "top,expected",
    [(None, 4 * 1024**3), (0, 4 * 1024**3), (-1, 4 * 1024**3), (True, 4 * 1024**3), (9, 9)],
)
def test_source_size_priority(top, expected):
    item = JFItem.from_raw(
        {
            "Id": "clip",
            "Type": "Video",
            "Size": top,
            "MediaSources": [{"Id": "primary", "Size": 4 * 1024**3}],
        }
    )
    assert map_media_item(item, "lib").size_bytes == expected


def test_refresh_backfills_existing_index_and_pending_without_read_stat(tmp_path):
    db = _make_db(tmp_path)
    item = map_media_item(JFItem.from_raw({"Id": "clip", "Type": "Video"}), "lib")
    with db.session() as s:
        media_index.upsert_media_items(s, [item])
        delete_queue.enqueue(s, item.media_id)
        s.commit()
    updated = map_media_item(
        JFItem.from_raw({"Id": "clip", "Type": "Video", "MediaSources": [{"Size": 4 * 1024**3}]}),
        "lib",
    )
    with db.session() as s:
        media_index.upsert_media_items(s, [updated], generation="refresh")
        s.commit()
    with db.session() as s, patch("os.stat", side_effect=AssertionError("queue must not stat")):
        assert media_index.get_cached_media(s, item.media_id).size_bytes == 4 * 1024**3
        assert delete_queue.list_queue(s)[0].size_bytes == 4 * 1024**3
        assert delete_queue.pending_summary(s) == (1, 4 * 1024**3)
    db.dispose()


def test_load_backfills_pending_from_index(tmp_path):
    db = _make_db(tmp_path)
    _seed_media(db, "a", str(tmp_path / "missing.mp4"), size=123)
    _enqueue(db, "a", 0)
    with db.session() as s:
        assert delete_queue.list_queue(s)[0].size_bytes == 123
        assert delete_queue.prepare_commit(s).total_bytes == 123
    db.dispose()


def test_receipt_real_remove_exact_bytes_and_missing(tmp_path):
    db = _make_db(tmp_path)
    path = tmp_path / "clip.mp4"
    path.write_bytes(b"real-file-contents")
    _seed_media(db, "a", str(path))
    _seed_media(db, "missing", str(tmp_path / "missing.mp4"), size=1000)
    _enqueue(db, "a", 999999)
    _enqueue(db, "missing", 1000)
    with db.session() as s:
        nonce = delete_queue.prepare_commit(s).nonce
        result = delete_queue.commit_with_nonce(s, nonce)
        assert result == {"a": "success", "missing": "missing"}
        assert result.deleted_count == 1
        assert result.deleted_bytes == len(b"real-file-contents")
        assert result.missing_count == 1
        assert result.failed_count == 0
        assert not path.exists()
    db.dispose()


@pytest.mark.parametrize("path_kind", ["leaf_link", "parent_link", "directory"])
def test_commit_rejects_link_chain_and_nonregular_leaf(tmp_path, path_kind):
    target = tmp_path / "target"
    target.mkdir()
    actual = target / "clip.mp4"
    actual.write_bytes(b"do-not-delete")
    if path_kind == "leaf_link":
        media_path = tmp_path / "linked.mp4"
        try:
            media_path.symlink_to(actual)
        except (OSError, NotImplementedError) as exc:
            pytest.skip(f"file symlink unavailable: {exc}")
    elif path_kind == "parent_link":
        linked = tmp_path / "linked-parent"
        if sys.platform == "win32":
            import _winapi

            _winapi.CreateJunction(str(target), str(linked))
        else:
            linked.symlink_to(target, target_is_directory=True)
        media_path = linked / "clip.mp4"
    else:
        media_path = tmp_path / "directory.mp4"
        media_path.mkdir()

    db = _make_db(tmp_path)
    _seed_media(db, "linked", str(media_path), size=len(b"do-not-delete"))
    _enqueue(db, "linked", len(b"do-not-delete"))
    with db.session() as s:
        nonce = delete_queue.prepare_commit(s).nonce
        receipt = delete_queue.commit_with_nonce(s, nonce)
        assert receipt == {"linked": "failed"}
        assert receipt.deleted_bytes == receipt.deleted_count == 0
        assert s.get(DeleteQueue, "linked").status == "failed"
        assert s.get(MediaCacheIndex, "linked") is not None
        assert actual.read_bytes() == b"do-not-delete"
        if path_kind != "directory":
            assert os.path.lexists(media_path)
    db.dispose()


@pytest.mark.parametrize("remove_mode", ["noop", "error"])
def test_unverified_or_failed_remove_keeps_queue_and_zero_bytes(tmp_path, remove_mode):
    db = _make_db(tmp_path)
    path = tmp_path / "clip.mp4"
    path.write_bytes(b"keep-me")
    _seed_media(db, "a", str(path))
    _enqueue(db, "a", 7)
    with db.session() as s:
        nonce = delete_queue.prepare_commit(s).nonce
        with patch(
            "os.remove", side_effect=PermissionError("blocked") if remove_mode == "error" else None
        ):
            result = delete_queue.commit_with_nonce(s, nonce)
        assert result == {"a": "failed"}
        assert result.deleted_count == result.deleted_bytes == 0
        assert result.failed_count == 1
        assert s.get(DeleteQueue, "a").status == "failed"
        assert path.exists()
    db.dispose()


def test_sync_local_size_fallback_and_no_read_stat(tmp_path):
    db = _make_db(tmp_path)
    path = tmp_path / "local.mp4"
    path.write_bytes(b"local-bytes")
    _seed_media(db, "seed", str(tmp_path / "missing"), size=0)
    item = map_media_item(
        JFItem.from_raw({"Id": "local", "Type": "Video", "Path": str(path)}), "lib-movies"
    )
    with db.session() as s:
        media_index.upsert_media_items(s, [item], generation="refresh")
        assert media_index.get_cached_media(s, item.media_id).size_bytes == 11
    db.dispose()


@pytest.mark.parametrize(
    "media_path", ["relative.mp4", "https://example.test/video", r"\\server\share\video"]
)
def test_sync_untrusted_path_never_stats(tmp_path, media_path):
    db = _make_db(tmp_path)
    _seed_media(db, "seed", str(tmp_path / "missing"), size=0)
    item = map_media_item(
        JFItem.from_raw({"Id": "untrusted", "Type": "Video", "Path": media_path}), "lib-movies"
    )
    with db.session() as s, patch("os.lstat", side_effect=AssertionError("untrusted path")):
        media_index.upsert_media_items(s, [item], generation="refresh")
        assert media_index.get_cached_media(s, item.media_id).size_bytes is None
    db.dispose()


@pytest.mark.parametrize("link_kind", ["symlink", "reparse"])
def test_sync_rejects_parent_links(tmp_path, link_kind):
    import os
    import stat
    from types import SimpleNamespace

    db = _make_db(tmp_path)
    _seed_media(db, "seed", str(tmp_path / "missing"), size=0)
    path = tmp_path / "clip.mp4"
    path.write_bytes(b"contents")
    item = map_media_item(
        JFItem.from_raw({"Id": "link", "Type": "Video", "Path": str(path)}), "lib-movies"
    )
    original = os.lstat

    def guarded_lstat(component):
        if component == path.parent:
            return SimpleNamespace(
                st_mode=stat.S_IFLNK if link_kind == "symlink" else stat.S_IFDIR,
                st_file_attributes=0x400 if link_kind == "reparse" else 0,
            )
        return original(component)

    with db.session() as s, patch("os.lstat", side_effect=guarded_lstat):
        media_index.upsert_media_items(s, [item], generation="refresh")
        assert media_index.get_cached_media(s, item.media_id).size_bytes is None
    db.dispose()


def test_sync_local_size_updates_fingerprint(tmp_path):
    db = _make_db(tmp_path)
    path = tmp_path / "clip.mp4"
    path.write_bytes(b"one")
    _seed_media(db, "seed", str(tmp_path / "missing"), size=0)
    item = map_media_item(
        JFItem.from_raw({"Id": "local", "Type": "Video", "Path": str(path)}), "lib-movies"
    )
    with db.session() as s:
        media_index.upsert_media_items(s, [item], generation="one")
        before = media_index.get_cached_media(s, item.media_id).fingerprint
    path.write_bytes(b"longer contents")
    with db.session() as s:
        media_index.upsert_media_items(s, [item], generation="two")
        assert media_index.get_cached_media(s, item.media_id).fingerprint != before
    db.dispose()


def test_authenticated_api_nonce_receipt(tmp_path, data_root):
    from fastapi.testclient import TestClient
    from test_stage7_fix import _Loopback, _pair

    from app.core.config import AppConfig, SecurityConfig, StorageConfig
    from app.main import create_app

    app = create_app(
        AppConfig(
            storage=StorageConfig(data_root=str(data_root)),
            security=SecurityConfig(pairing_required=True),
        )
    )
    with TestClient(_Loopback(app)) as client:
        path = tmp_path / "receipt.mp4"
        path.write_bytes(b"api-actual-bytes")
        _seed_media(app.state.database, "a", str(path))
        _enqueue(app.state.database, "a", 999)
        assert client.post("/api/v1/delete-queue/commit/prepare").status_code == 401
        token = _pair(client, device_id="receipt-device")
        headers = {"Authorization": f"Bearer {token}"}
        prep = client.post("/api/v1/delete-queue/commit/prepare", headers=headers)
        assert prep.status_code == 200
        nonce = prep.json()["data"]["nonce"]
        response = client.post(
            "/api/v1/delete-queue/commit", json={"nonce": nonce}, headers=headers
        )
        assert response.status_code == 200
        assert response.json()["data"] == {
            "outcome": {"a": "success"},
            "deleted_count": 1,
            "deleted_bytes": 16,
            "missing_count": 0,
            "failed_count": 0,
        }
        assert not path.exists()
        assert (
            client.post(
                "/api/v1/delete-queue/commit", json={"nonce": nonce}, headers=headers
            ).status_code
            == 409
        )
