"""Stage8I: 尺寸回填与真实删除回执；全部使用隔离文件/数据库。"""

import os
import sys
from datetime import UTC, datetime
from unittest.mock import patch

import pytest
import sqlalchemy as sa
from test_delete_nonce_11 import _enqueue, _make_db, _seed_media

from app.adapters.jellyfin.mapper import map_media_item
from app.adapters.jellyfin.models import JFItem
from app.db.models import AuditLog, DeleteCommitNonce, DeleteQueue, MediaCacheIndex
from app.services import audit, delete_queue, media_index


def test_stale_session_cannot_reuse_consumed_nonce(tmp_path):
    from app.db.models import DeleteCommitNonce

    db = _make_db(tmp_path)
    path = tmp_path / "once.mp4"
    path.write_bytes(b"once")
    _seed_media(db, "once", str(path))
    _enqueue(db, "once", 4)
    with db.session() as s:
        nonce = delete_queue.prepare_commit(s).nonce
        s.commit()
    with db.session() as stale:
        cached_nonce = stale.get(DeleteCommitNonce, nonce)
        assert not cached_nonce.used
        with db.session() as first:
            delete_queue.commit_with_nonce(first, nonce)
            first.commit()
        with patch("app.services.delete_queue._commit_media_ids") as remove:
            with pytest.raises(delete_queue.NonceReusedError):
                delete_queue.commit_with_nonce(stale, nonce)
            remove.assert_not_called()
    db.dispose()


def test_second_snapshot_does_not_retry_failed_item_without_requeue(tmp_path):
    db = _make_db(tmp_path)
    path = tmp_path / "protected.mp4"
    path.write_bytes(b"keep")
    _seed_media(db, "guarded", str(path))
    _enqueue(db, "guarded", 4)
    with db.session() as s:
        first = delete_queue.prepare_commit(s).nonce
        second = delete_queue.prepare_commit(s).nonce
        with patch("os.remove", side_effect=PermissionError("blocked")):
            assert delete_queue.commit_with_nonce(s, first) == {"guarded": "failed"}
        result = delete_queue.commit_with_nonce(s, second)
        assert result == {"guarded": "failed"}
        assert result.deleted_count == result.deleted_bytes == 0
        assert path.exists()
        assert (
            s.scalar(
                sa.select(AuditLog.detail)
                .where(AuditLog.action == "delete_commit", AuditLog.media_id == "guarded")
                .order_by(AuditLog.id.desc())
            )
            == "failed:not_pending"
        )
    db.dispose()


def test_snapshot_missing_queue_entry_is_audited(tmp_path):
    db = _make_db(tmp_path)
    path = tmp_path / "withdrawn.mp4"
    path.write_bytes(b"keep")
    _seed_media(db, "withdrawn", str(path))
    _enqueue(db, "withdrawn", 4)
    with db.session() as session:
        nonce = delete_queue.prepare_commit(session).nonce
        session.commit()
    with db.session() as session:
        assert delete_queue.dequeue(session, "withdrawn") is True
        session.commit()
    with db.session() as session:
        assert delete_queue.commit_with_nonce(session, nonce) == {}
    with db.session() as session:
        assert (
            session.scalar(
                sa.select(AuditLog.detail)
                .where(AuditLog.action == "delete_commit", AuditLog.media_id == "withdrawn")
                .order_by(AuditLog.id.desc())
            )
            == "skipped:queue_entry_missing"
        )
    assert path.exists()
    db.dispose()


@pytest.mark.parametrize(
    ("branch", "expected_detail", "expected_status"),
    [
        ("missing", "skipped:queue_entry_missing", None),
        ("superseded", "failed:queue_entry_superseded", "pending"),
        ("not_pending", "failed:not_pending", "failed"),
    ],
)
def test_snapshot_audit_failure_rolls_back_nonce_claim(
    tmp_path, monkeypatch, branch, expected_detail, expected_status
):
    """非执行快照审计与 nonce claim 必须同事务；审计失败不能消费 nonce。"""
    db = _make_db(tmp_path)
    path = tmp_path / f"{branch}.mp4"
    path.write_bytes(b"keep")
    media_id = f"snapshot-{branch}"
    _seed_media(db, media_id, str(path), size=4)
    _enqueue(db, media_id, 4)
    with db.session() as session:
        nonce = delete_queue.prepare_commit(session).nonce
        session.commit()
    with db.session() as session:
        row = session.get(DeleteQueue, media_id)
        if branch == "missing":
            session.delete(row)
        elif branch == "superseded":
            session.delete(row)
            session.flush()
            assert delete_queue.enqueue(session, media_id, size_bytes=4) is True
        else:
            row.status = "failed"
            row.error = "preexisting_failure"
        session.commit()

    original_log = audit.log_action

    def fail_snapshot_audit(db_session, action, logged_media_id=None, detail=None):
        if action == "delete_commit" and detail == expected_detail:
            raise RuntimeError("injected snapshot audit failure")
        return original_log(db_session, action, logged_media_id, detail)

    monkeypatch.setattr(audit, "log_action", fail_snapshot_audit)
    with db.session() as session:
        with pytest.raises(RuntimeError, match="snapshot audit failure"):
            delete_queue.commit_with_nonce(session, nonce)

    with db.session() as session:
        assert session.get(DeleteCommitNonce, nonce).used is False
        queued = session.get(DeleteQueue, media_id)
        assert (None if queued is None else queued.status) == expected_status
        assert (
            session.scalar(
                sa.select(sa.func.count())
                .select_from(AuditLog)
                .where(AuditLog.action == "delete_commit", AuditLog.media_id == media_id)
            )
            == 0
        )
    assert path.read_bytes() == b"keep"
    db.dispose()


def test_snapshot_audit_failure_returns_api_error_without_consuming_nonce(
    tmp_path, client, monkeypatch
):
    """审计写失败必须由 API 返回错误，且不能留下已消费 nonce。"""
    db = client.app.state.database
    path = tmp_path / "api-missing.mp4"
    path.write_bytes(b"keep")
    media_id = "api-snapshot-missing"
    _seed_media(db, media_id, str(path), size=4)
    _enqueue(db, media_id, 4)
    with db.session() as session:
        nonce = delete_queue.prepare_commit(session).nonce
        session.commit()
    with db.session() as session:
        assert delete_queue.dequeue(session, media_id) is True
        session.commit()

    original_log = audit.log_action

    def fail_snapshot_audit(db_session, action, logged_media_id=None, detail=None):
        if detail == "skipped:queue_entry_missing":
            raise RuntimeError("injected snapshot audit failure")
        return original_log(db_session, action, logged_media_id, detail)

    monkeypatch.setattr(audit, "log_action", fail_snapshot_audit)
    transport = client._transport
    previous_raise = transport.raise_server_exceptions
    transport.raise_server_exceptions = False
    try:
        response = client.post("/api/v1/delete-queue/commit", json={"nonce": nonce})
    finally:
        transport.raise_server_exceptions = previous_raise
    assert response.status_code == 500
    with db.session() as session:
        assert session.get(DeleteCommitNonce, nonce).used is False
    assert path.read_bytes() == b"keep"


@pytest.mark.parametrize("failure_point", ["audit", "index_flush"])
def test_post_remove_failure_isolated_and_recoverable(tmp_path, monkeypatch, failure_point):
    """文件已删后的意外异常不得回滚成 pending，也不得中断后续项。"""
    db = _make_db(tmp_path)
    first = tmp_path / "first.mp4"
    second = tmp_path / "second.mp4"
    first.write_bytes(b"first")
    second.write_bytes(b"second")
    _seed_media(db, "first", str(first), size=5)
    _seed_media(db, "second", str(second), size=6)
    _enqueue(db, "first", 5)
    _enqueue(db, "second", 6)
    with db.session() as session:
        nonce = delete_queue.prepare_commit(session).nonce
        session.commit()

    with db.session() as session:
        if failure_point == "audit":
            original_log = audit.log_action
            failed_once = False

            def fail_first_delete_audit(db_session, action, media_id=None, detail=None):
                nonlocal failed_once
                if action == "delete_commit" and media_id == "first" and not failed_once:
                    failed_once = True
                    raise RuntimeError("injected audit failure")
                return original_log(db_session, action, media_id, detail)

            monkeypatch.setattr(audit, "log_action", fail_first_delete_audit)
        else:
            original_flush = session.flush
            failed_once = False

            def fail_first_index_flush(*args, **kwargs):
                nonlocal failed_once
                deleting_index = any(isinstance(item, MediaCacheIndex) for item in session.deleted)
                if not failed_once and not first.exists() and deleting_index:
                    failed_once = True
                    raise RuntimeError("injected index flush failure")
                return original_flush(*args, **kwargs)

            monkeypatch.setattr(session, "flush", fail_first_index_flush)

        receipt = delete_queue.commit_with_nonce(session, nonce)

    assert receipt == {"first": "success", "second": "success"}
    assert not first.exists() and not second.exists()
    with db.session() as session:
        assert session.get(DeleteCommitNonce, nonce).used is True
        assert session.get(DeleteQueue, "first").status == "delete_done"
        assert session.get(DeleteQueue, "second") is None
        assert session.get(MediaCacheIndex, "first") is not None

    monkeypatch.undo()
    with db.session() as session:
        delete_queue.recover_interrupted_deletes(session)
    with db.session() as session:
        assert session.get(DeleteQueue, "first") is None
        assert session.get(MediaCacheIndex, "first") is None
        assert (
            session.scalar(
                sa.select(AuditLog.detail)
                .where(AuditLog.action == "delete_commit", AuditLog.media_id == "first")
                .order_by(AuditLog.id.desc())
            )
            == "metadata_cleanup_recovered"
        )
    db.dispose()


def test_recover_interrupted_deletes_converges_and_is_idempotent(tmp_path):
    db = _make_db(tmp_path)
    deleted = tmp_path / "already-deleted.mp4"
    present = tmp_path / "still-present.mp4"
    deleted.write_bytes(b"gone")
    present.write_bytes(b"stay")
    _seed_media(db, "gone", str(deleted), size=4)
    _seed_media(db, "stay", str(present), size=4)
    with db.session() as session:
        session.get(MediaCacheIndex, "stay").modified_at = datetime.fromtimestamp(
            present.stat().st_mtime, UTC
        ).replace(tzinfo=None)
        session.commit()
    _enqueue(db, "gone", 4)
    _enqueue(db, "stay", 4)
    with db.session() as session:
        session.execute(
            sa.update(DeleteQueue)
            .values(status="committing")
            .where(DeleteQueue.media_id.in_(["gone", "stay"]))
        )
        session.commit()
    deleted.unlink()

    with db.session() as session:
        delete_queue.recover_interrupted_deletes(session)
    with db.session() as session:
        assert session.get(DeleteQueue, "gone") is None
        stayed = session.get(DeleteQueue, "stay")
        assert stayed.status == "failed"
        assert stayed.error == "interrupted_not_deleted"
        details = list(
            session.scalars(
                sa.select(AuditLog.detail)
                .where(AuditLog.action == "delete_commit", AuditLog.media_id.in_(["gone", "stay"]))
                .order_by(AuditLog.id)
            )
        )
        assert details == ["recovered_deleted", "failed:interrupted_not_deleted"]

    with db.session() as session:
        delete_queue.recover_interrupted_deletes(session)
    with db.session() as session:
        assert (
            session.scalar(
                sa.select(sa.func.count())
                .select_from(AuditLog)
                .where(AuditLog.action == "delete_commit", AuditLog.media_id.in_(["gone", "stay"]))
            )
            == 2
        )
    assert present.read_bytes() == b"stay"
    db.dispose()


def test_recover_jellyfin_unreachable_parent_keeps_committing(tmp_path):
    """父链不可达不能被误认成叶文件已删除。"""
    db = _make_db(tmp_path)
    parent = tmp_path / "mounted-library"
    parent.mkdir()
    target = parent / "movie.mp4"
    target.write_bytes(b"stay")
    _seed_media(db, "unreachable-parent", str(target), size=4)
    _enqueue(db, "unreachable-parent", 4)
    with db.session() as session:
        row = session.get(DeleteQueue, "unreachable-parent")
        row.status = "committing"
        session.commit()

    parent.rename(tmp_path / "mounted-library-offline")
    with db.session() as session:
        delete_queue.recover_interrupted_deletes(session)

    with db.session() as session:
        row = session.get(DeleteQueue, "unreachable-parent")
        assert row is not None
        assert row.status == "committing"
        assert session.get(MediaCacheIndex, "unreachable-parent") is not None
        assert session.scalar(
            sa.select(sa.func.count())
            .select_from(AuditLog)
            .where(
                AuditLog.action == "delete_commit",
                AuditLog.media_id == "unreachable-parent",
            )
        ) == 0
    db.dispose()


def test_recover_jellyfin_parent_rename_after_validation_never_reports_missing(tmp_path):
    """父目录验证后被改名时，句柄相对观察仍不能把原文件误报为缺失。"""
    if os.name != "nt":
        pytest.skip("仅 Windows 句柄相对观察适用")

    db = _make_db(tmp_path)
    parent = tmp_path / "mounted-library"
    parked_parent = tmp_path / "mounted-library-offline"
    parent.mkdir()
    target = parent / "movie.mp4"
    target.write_bytes(b"stay")
    _seed_media(db, "renamed-parent", str(target), size=4)
    _enqueue(db, "renamed-parent", 4)
    with db.session() as session:
        media = session.get(MediaCacheIndex, "renamed-parent")
        media.modified_at = datetime.fromtimestamp(target.stat().st_mtime, UTC).replace(
            tzinfo=None
        )
        row = session.get(DeleteQueue, "renamed-parent")
        row.status = "committing"
        session.commit()

    hook_called = False

    def rename_parent_after_validation():
        nonlocal hook_called
        parent.rename(parked_parent)
        hook_called = True

    with db.session() as session:
        delete_queue.recover_interrupted_deletes(
            session,
            _jellyfin_observation_hook=rename_parent_after_validation,
        )

    assert hook_called
    assert (parked_parent / "movie.mp4").read_bytes() == b"stay"
    with db.session() as session:
        row = session.get(DeleteQueue, "renamed-parent")
        assert row is not None
        assert row.status == "failed"
        assert row.error == "interrupted_not_deleted"
        assert session.get(MediaCacheIndex, "renamed-parent") is not None
        assert session.scalar(
            sa.select(sa.func.count())
            .select_from(AuditLog)
            .where(
                AuditLog.action == "delete_commit",
                AuditLog.media_id == "renamed-parent",
                AuditLog.detail.in_(
                    ["recovered_deleted", "recovered_original_gone"]
                ),
            )
        ) == 0
    db.dispose()


@pytest.mark.parametrize("missing_evidence", ["size_bytes", "modified_at"])
def test_recover_jellyfin_missing_identity_evidence_keeps_committing(
    tmp_path, missing_evidence
):
    """size/mtime 任一缺失时只能保留 UNKNOWN，不能积极判定原文件仍在。"""
    db = _make_db(tmp_path)
    target = tmp_path / f"missing-{missing_evidence}.mp4"
    target.write_bytes(b"stay")
    media_id = f"missing-{missing_evidence}"
    _seed_media(db, media_id, str(target), size=4)
    _enqueue(db, media_id, 4)
    with db.session() as session:
        media = session.get(MediaCacheIndex, media_id)
        media.modified_at = datetime.fromtimestamp(target.stat().st_mtime, UTC).replace(
            tzinfo=None
        )
        setattr(media, missing_evidence, None)
        row = session.get(DeleteQueue, media_id)
        row.status = "committing"
        session.commit()

    with db.session() as session:
        delete_queue.recover_interrupted_deletes(session)

    with db.session() as session:
        row = session.get(DeleteQueue, media_id)
        assert row is not None
        assert row.status == "committing"
        assert session.get(MediaCacheIndex, media_id) is not None
        assert session.scalar(
            sa.select(sa.func.count())
            .select_from(AuditLog)
            .where(AuditLog.action == "delete_commit", AuditLog.media_id == media_id)
        ) == 0
    assert target.read_bytes() == b"stay"
    db.dispose()


def test_recover_jellyfin_replacement_cleans_metadata_without_deleting_file(tmp_path):
    """Jellyfin 绝对路径身份变化时只收敛旧行，不删除当前文件。"""
    db = _make_db(tmp_path)
    target = tmp_path / "jellyfin-rebuilt.mp4"
    target.write_bytes(b"old!")
    _seed_media(db, "jellyfin-rebuilt", str(target), size=4)
    _enqueue(db, "jellyfin-rebuilt", 4)
    with db.session() as session:
        session.get(MediaCacheIndex, "jellyfin-rebuilt").modified_at = datetime.fromtimestamp(
            target.stat().st_mtime, UTC
        ).replace(tzinfo=None)
        row = session.get(DeleteQueue, "jellyfin-rebuilt")
        row.status = "committing"
        session.commit()

    target.write_bytes(b"replacement")
    with patch("app.services.delete_queue.os.remove") as remove:
        with db.session() as session:
            delete_queue.recover_interrupted_deletes(session)
        remove.assert_not_called()

    assert target.read_bytes() == b"replacement"
    with db.session() as session:
        assert session.get(DeleteQueue, "jellyfin-rebuilt") is None
        assert session.get(MediaCacheIndex, "jellyfin-rebuilt") is None
        assert session.scalar(
            sa.select(AuditLog.detail)
            .where(
                AuditLog.action == "delete_commit",
                AuditLog.media_id == "jellyfin-rebuilt",
            )
            .order_by(AuditLog.id.desc())
        ) == "recovered_original_gone"
    db.dispose()


def test_delete_receipt_contract_only_has_stable_terminal_outcomes():
    receipt = delete_queue.DeleteReceipt()
    receipt.update(ok="success", gone="missing", bad="failed")
    assert receipt.deleted_count == 1
    assert receipt.missing_count == 1
    assert receipt.failed_count == 1
    assert not hasattr(receipt, "pending_count")


@pytest.mark.parametrize("kind", ["relative", "parent_traversal", "unc", "uri"])
def test_relative_deletion_path_rejected(tmp_path, monkeypatch, kind):
    db = _make_db(tmp_path)
    monkeypatch.chdir(tmp_path)
    path = tmp_path / "relative.mp4"
    path.write_bytes(b"keep")
    child = tmp_path / "inside"
    child.mkdir()
    unsafe = {
        "relative": "relative.mp4",
        "parent_traversal": str(child / ".." / "relative.mp4"),
        "unc": r"\\untrusted-host\share\relative.mp4",
        "uri": "https://untrusted.invalid/relative.mp4",
    }[kind]
    _seed_media(db, "relative", unsafe, size=4)
    _enqueue(db, "relative", 4)
    with db.session() as s:
        result = delete_queue.commit_with_nonce(s, delete_queue.prepare_commit(s).nonce)
        assert result == {"relative": "failed"}
        assert result.deleted_bytes == 0
        assert path.exists()
    db.dispose()


def test_prepare_distinguishes_unknown_size_from_known_bytes(tmp_path):
    db = _make_db(tmp_path)
    _seed_media(db, "unknown", str(tmp_path / "not-opened.mp4"), size=0)
    _enqueue(db, "unknown", 0)
    with db.session() as s:
        prep = delete_queue.prepare_commit(s)
        assert prep.total_bytes == 0
        assert prep.unknown_size_count == 1
    db.dispose()


def test_api_reports_unknown_sizes_and_replayed_request_without_claiming_success(client, tmp_path):
    db = client.app.state.database
    path = tmp_path / "api-delete.mp4"
    path.write_bytes(b"actual")
    _seed_media(db, "api-delete", str(path))
    _enqueue(db, "api-delete", 6)
    _seed_media(db, "unknown-size", str(tmp_path / "absent.mp4"), size=0)
    _enqueue(db, "unknown-size", 0)
    summary = client.get("/api/v1/delete-queue/summary").json()["data"]
    prep = client.post("/api/v1/delete-queue/commit/prepare").json()["data"]
    assert summary["unknown_size_count"] == prep["unknown_size_count"] == 1
    assert summary["total_bytes"] == prep["total_bytes"] == 6
    response = client.post("/api/v1/delete-queue/commit", json={"nonce": prep["nonce"]})
    assert response.json()["data"]["deleted_bytes"] == 6
    assert response.json()["data"]["missing_count"] == 1
    replay = client.post("/api/v1/delete-queue/commit", json={"nonce": prep["nonce"]})
    assert replay.status_code == 409
    assert replay.json()["error"]["code"] == "DELETE_ALREADY_PROCESSED"
    assert replay.json()["success"] is False


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



def test_recover_jellyfin_object_path_not_found_is_unknown(tmp_path):
    """STATUS_OBJECT_PATH_NOT_FOUND 不是叶缺失证据；必须保持 committing 等待重试。"""
    if os.name != "nt":
        pytest.skip("仅 Windows 句柄相对观察适用")

    db = _make_db(tmp_path)
    parent = tmp_path / "mounted-library-path-nf"
    parent.mkdir()
    target = parent / "movie.mp4"
    target.write_bytes(b"stay")
    _seed_media(db, "path-not-found", str(target), size=4)
    _enqueue(db, "path-not-found", 4)
    with db.session() as session:
        media = session.get(MediaCacheIndex, "path-not-found")
        media.modified_at = datetime.fromtimestamp(target.stat().st_mtime, UTC).replace(
            tzinfo=None
        )
        row = session.get(DeleteQueue, "path-not-found")
        row.status = "committing"
        session.commit()

    with db.session() as session:
        delete_queue.recover_interrupted_deletes(
            session, _jellyfin_ntstatus_override=0xC000003A
        )

    assert target.read_bytes() == b"stay"
    with db.session() as session:
        row = session.get(DeleteQueue, "path-not-found")
        assert row is not None
        assert row.status == "committing"
        assert row.error is None
        assert session.get(MediaCacheIndex, "path-not-found") is not None
        assert session.scalar(
            sa.select(sa.func.count())
            .select_from(AuditLog)
            .where(
                AuditLog.action == "delete_commit",
                AuditLog.media_id == "path-not-found",
                AuditLog.detail.in_(
                    ["recovered_deleted", "recovered_original_gone"]
                ),
            )
        ) == 0
    db.dispose()
