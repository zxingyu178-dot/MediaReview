"""本地来源回归：仅使用 pytest 临时文件。"""
import json
import os
import stat

import pytest
import sqlalchemy as sa

from app.db.models import (
    AuditLog,
    BackgroundTask,
    DeleteQueue,
    Favorite,
    LocalRoot,
    MediaCacheIndex,
)
from app.db.session import Database
from app.services import audit, delete_queue, native_sources


@pytest.fixture
def native_db(tmp_path):
    db = Database(tmp_path / "index.sqlite")
    db.create_all()
    yield db
    db.dispose()


def scan(db, root_id):
    with db.session() as s:
        task = native_sources.schedule_scan(s, root_id)
        task.status = "running"
        task_id = task.task_id
        s.commit()
    native_sources.run_scan(db, task_id)
    with db.session() as s:
        return s.get(BackgroundTask, task_id)


def test_scan_stable_ids_incremental_and_offline_keeps_favorite(native_db, tmp_path):
    folder = tmp_path / "source"
    (folder / "a" / "b").mkdir(parents=True)
    photo = folder / "a" / "b" / "same.jpg"
    photo.write_bytes(b"temporary picture")
    with native_db.session() as s:
        root = native_sources.register_root(s, "临时来源", str(folder))
        root_id = root.root_id
        s.commit()
    assert scan(native_db, root_id).status == "succeeded"
    with native_db.session() as s:
        row = s.scalar(sa.select(MediaCacheIndex))
        mid = row.media_id
        assert mid.startswith("loc_") and len(mid) == 24
        assert row.source_type == "local" and row.media_path == "a/b/same.jpg"
        s.add(Favorite(media_id=mid))
        s.commit()
    task = scan(native_db, root_id)
    assert json.loads(task.result)["changed"] == 0
    photo.unlink()
    folder.rename(tmp_path / "offline")
    assert scan(native_db, root_id).status == "failed"
    with native_db.session() as s:
        assert not s.get(MediaCacheIndex, mid).is_available
        assert s.get(Favorite, mid) is not None


def test_identity_scoped_by_root_and_reject_unsafe_paths(native_db, tmp_path):
    assert native_sources.local_media_id("one", "a.jpg") != native_sources.local_media_id("two", "a.jpg")
    assert native_sources.local_media_id("one", "A.jpg") != native_sources.local_media_id("one", "a.jpg")
    for path in ("relative", r"\\host\share", str(tmp_path / "missing")):
        with native_db.session() as s, pytest.raises(native_sources.SourcePathError):
            native_sources.register_root(s, "坏目录", path)
    root = tmp_path / "valid"
    root.mkdir()
    with native_db.session() as s:
        registered = native_sources.register_root(s, "临时", str(root))
        for relative in ("../escape.jpg", str(tmp_path / "escape.jpg"), "a/../../escape.jpg"):
            with pytest.raises(native_sources.SourcePathError):
                native_sources.safe_path(registered, relative)


def test_scan_skips_symlink(native_db, tmp_path):
    root = tmp_path / "valid"
    root.mkdir()
    outside = tmp_path / "outside.jpg"
    outside.write_bytes(b"never index")
    try:
        (root / "link.jpg").symlink_to(outside)
    except OSError:
        pytest.skip("Windows 未授权建立符号链接")
    with native_db.session() as s:
        row = native_sources.register_root(s, "临时", str(root))
        root_id = row.root_id
        s.commit()
    assert scan(native_db, root_id).status == "succeeded"
    with native_db.session() as s:
        assert s.scalar(sa.select(sa.func.count()).select_from(MediaCacheIndex)) == 0


def _register_and_scan(native_db, tmp_path, files=None, folder_name="source"):
    """建立独立本地根并同步扫描；返回 (根目录, 根 ID)。"""
    root = tmp_path / folder_name
    root.mkdir()
    for name, payload in (files or {"sample.jpg": b"temporary"}).items():
        target = root / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(payload)
    with native_db.session() as s:
        root_id = native_sources.register_root(s, "临时来源", str(root)).root_id
        s.commit()
    assert scan(native_db, root_id).status == "succeeded"
    return root, root_id


def _indexed(native_db):
    """返回 (media_id, 相对路径, 尺寸)；测试文件只有一个媒体时使用。"""
    with native_db.session() as s:
        row = s.scalar(sa.select(MediaCacheIndex))
        assert row is not None
        return row.media_id, row.media_path, row.size_bytes


def _forge_pending(native_db, media_id, size):
    with native_db.session() as s:
        if s.get(DeleteQueue, media_id) is None:
            s.add(DeleteQueue(media_id=media_id, status="pending", size_bytes=size))
            s.commit()


def _commit_pending_with_nonce(
    native_db, nonce, *, local_delete_hook=None, force_legacy_disposition=False
):
    with native_db.session() as s:
        outcome = delete_queue.commit_with_nonce(
            s,
            nonce,
            _local_delete_hook=local_delete_hook,
            _force_legacy_disposition=force_legacy_disposition,
        )
        s.commit()
    return outcome


def _commit_pending(native_db):
    with native_db.session() as s:
        nonce = delete_queue.prepare_commit(s).nonce
        s.commit()
    return _commit_pending_with_nonce(native_db, nonce)


def _open_windows_read_handle(path, *, share_delete):
    """以真实 Win32 共享模式持有读取句柄；调用方负责 CloseHandle。"""
    import ctypes
    from ctypes import wintypes

    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    create_file = kernel32.CreateFileW
    create_file.argtypes = [
        wintypes.LPCWSTR,
        wintypes.DWORD,
        wintypes.DWORD,
        wintypes.LPVOID,
        wintypes.DWORD,
        wintypes.DWORD,
        wintypes.HANDLE,
    ]
    create_file.restype = wintypes.HANDLE
    kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
    kernel32.CloseHandle.restype = wintypes.BOOL
    share_mode = 0x00000001 | 0x00000002
    if share_delete:
        share_mode |= 0x00000004
    handle = create_file(str(path), 0x80000000, share_mode, None, 3, 0, None)
    assert handle != wintypes.HANDLE(-1).value, ctypes.get_last_error()
    return kernel32, handle


def _windows_handle_still_references_file(kernel32, handle):
    """delete-pending 期间用既有句柄确认文件对象仍存活。"""
    import ctypes
    from ctypes import wintypes

    class ByHandleFileInformation(ctypes.Structure):
        _fields_ = [
            ("dwFileAttributes", wintypes.DWORD),
            ("ftCreationTime", wintypes.FILETIME),
            ("ftLastAccessTime", wintypes.FILETIME),
            ("ftLastWriteTime", wintypes.FILETIME),
            ("dwVolumeSerialNumber", wintypes.DWORD),
            ("nFileSizeHigh", wintypes.DWORD),
            ("nFileSizeLow", wintypes.DWORD),
            ("nNumberOfLinks", wintypes.DWORD),
            ("nFileIndexHigh", wintypes.DWORD),
            ("nFileIndexLow", wintypes.DWORD),
        ]

    get_info = kernel32.GetFileInformationByHandle
    get_info.argtypes = [wintypes.HANDLE, ctypes.POINTER(ByHandleFileInformation)]
    get_info.restype = wintypes.BOOL
    return bool(get_info(handle, ctypes.byref(ByHandleFileInformation())))


def test_local_recovery_converges_missing_file_without_deleting_again(native_db, tmp_path):
    """恢复只观察本地相对路径；已删文件收敛元数据且重复调用幂等。"""
    from unittest.mock import patch

    root, _root_id = _register_and_scan(native_db, tmp_path, {"gone.jpg": b"gone"})
    target = root / "gone.jpg"
    mid, relative_path, size = _indexed(native_db)
    assert relative_path == "gone.jpg"
    with native_db.session() as session:
        session.add(Favorite(media_id=mid))
        session.add(DeleteQueue(media_id=mid, status="committing", size_bytes=size))
        session.commit()
    target.unlink()

    with (
        patch("app.services.native_sources.delete_local_media") as local_delete,
        patch("app.services.delete_queue.os.remove") as jellyfin_delete,
    ):
        with native_db.session() as session:
            delete_queue.recover_interrupted_deletes(session)
        with native_db.session() as session:
            delete_queue.recover_interrupted_deletes(session)
        local_delete.assert_not_called()
        jellyfin_delete.assert_not_called()

    with native_db.session() as session:
        assert session.get(DeleteQueue, mid) is None
        assert session.get(MediaCacheIndex, mid) is None
        assert session.get(Favorite, mid) is None
        details = list(
            session.scalars(
                sa.select(AuditLog.detail).where(
                    AuditLog.action == "delete_commit",
                    AuditLog.media_id == mid,
                )
            )
        )
    assert details == ["recovered_deleted"]


def test_local_recovery_keeps_present_file_and_marks_failed(native_db, tmp_path):
    """本地文件仍存在时恢复 fail-safe，不执行删除也不重复审计。"""
    from unittest.mock import patch

    root, _root_id = _register_and_scan(native_db, tmp_path, {"stay.jpg": b"stay"})
    target = root / "stay.jpg"
    mid, relative_path, size = _indexed(native_db)
    assert relative_path == "stay.jpg"
    with native_db.session() as session:
        session.add(DeleteQueue(media_id=mid, status="committing", size_bytes=size))
        session.commit()

    with (
        patch("app.services.native_sources.delete_local_media") as local_delete,
        patch("app.services.delete_queue.os.remove") as jellyfin_delete,
    ):
        with native_db.session() as session:
            delete_queue.recover_interrupted_deletes(session)
        with native_db.session() as session:
            delete_queue.recover_interrupted_deletes(session)
        local_delete.assert_not_called()
        jellyfin_delete.assert_not_called()

    assert target.read_bytes() == b"stay"
    with native_db.session() as session:
        row = session.get(DeleteQueue, mid)
        assert row.status == "failed"
        assert row.error == "interrupted_not_deleted"
        assert session.get(MediaCacheIndex, mid) is not None
        details = list(
            session.scalars(
                sa.select(AuditLog.detail).where(
                    AuditLog.action == "delete_commit",
                    AuditLog.media_id == mid,
                )
            )
        )
    assert details == ["failed:interrupted_not_deleted"]


def test_local_delete_two_phase_success_and_audit_without_path(native_db, tmp_path):
    """两阶段成功删除：入队不删、nonce 快照才删；审计 detail 不含绝对路径。"""
    payload = b"temporary-delete-bytes"
    root, _root_id = _register_and_scan(native_db, tmp_path, {"discard.jpg": payload})
    target = root / "discard.jpg"
    mid, media_path, size = _indexed(native_db)
    assert media_path == "discard.jpg" and size == len(payload)

    with native_db.session() as s:
        assert delete_queue.enqueue(s, mid, size_bytes=size) is True
        s.commit()
    assert target.exists(), "入队阶段绝不能删除文件"

    with native_db.session() as s:
        nonce = delete_queue.prepare_commit(s).nonce
        s.commit()
    with native_db.session() as s:
        outcome = delete_queue.commit_with_nonce(s, nonce)
        s.commit()

    assert outcome == {mid: "success"}
    assert outcome.deleted_count == 1 and outcome.deleted_bytes == len(payload)
    assert not target.exists()
    with native_db.session() as s:
        assert s.get(MediaCacheIndex, mid) is None
        assert s.get(DeleteQueue, mid) is None
        details = [row.detail or "" for row in audit.recent(s)]
    assert "success" in details
    assert all(str(root) not in detail for detail in details), "审计 detail 不得写绝对路径"
    assert all(str(tmp_path) not in detail for detail in details)


def test_local_delete_missing_semantics(native_db, tmp_path):
    """入队后文件在电脑端消失 → missing 回执，仍清理索引且不虚报字节。"""
    root, _ = _register_and_scan(native_db, tmp_path, {"gone.jpg": b"gone"})
    mid, _path, size = _indexed(native_db)
    with native_db.session() as s:
        delete_queue.enqueue(s, mid, size_bytes=size)
        s.commit()
    (root / "gone.jpg").unlink()
    outcome = _commit_pending(native_db)
    assert outcome == {mid: "missing"}
    assert outcome.deleted_bytes == outcome.deleted_count == 0
    with native_db.session() as s:
        assert s.get(MediaCacheIndex, mid) is None
        assert s.get(DeleteQueue, mid) is None


def test_local_enqueue_and_commit_reject_disabled_or_missing_root(native_db, tmp_path):
    """登记根停用/移除必须明确拒绝入队；伪造 pending 也只能 failed，绝不删文件。"""
    payload = b"protected"
    root, root_id = _register_and_scan(native_db, tmp_path, {"keep.jpg": payload})
    target, mid = root / "keep.jpg", _indexed(native_db)[0]

    with native_db.session() as s:
        s.get(LocalRoot, root_id).enabled = False
        s.commit()
    with native_db.session() as s, pytest.raises(native_sources.LocalRootDisabledError):
        delete_queue.enqueue(s, mid)
    _forge_pending(native_db, mid, len(payload))
    assert _commit_pending(native_db) == {mid: "failed"}
    with native_db.session() as s:
        assert s.get(DeleteQueue, mid).error == "local_root_disabled"
    assert target.read_bytes() == payload

    with native_db.session() as s:
        s.get(LocalRoot, root_id).enabled = True
        s.get(DeleteQueue, mid).status = "pending"
        s.commit()
    with native_db.session() as s:
        s.delete(s.get(LocalRoot, root_id))
        s.commit()
    with native_db.session() as s, pytest.raises(native_sources.LocalRootMissingError):
        delete_queue.enqueue(s, mid)
    assert _commit_pending(native_db) == {mid: "failed"}
    with native_db.session() as s:
        assert s.get(DeleteQueue, mid).error == "local_root_missing"
    assert target.read_bytes() == payload


def test_local_delete_rejects_replaced_file(native_db, tmp_path):
    """入队后文件被同尺寸替换/改写 → 身份复验失败，禁止删除。"""
    root, _ = _register_and_scan(native_db, tmp_path, {"keep.jpg": b"A" * 64})
    target, mid = root / "keep.jpg", _indexed(native_db)[0]
    with native_db.session() as s:
        delete_queue.enqueue(s, mid, size_bytes=64)
        s.commit()
    replacement = tmp_path / "replacement.jpg"
    replacement.write_bytes(b"B" * 64)
    os.replace(replacement, target)
    future = target.stat().st_mtime + 3600
    os.utime(target, (future, future))

    outcome = _commit_pending(native_db)
    assert outcome == {mid: "failed"} and outcome.deleted_bytes == 0
    assert target.read_bytes() == b"B" * 64
    with native_db.session() as s:
        queued = s.get(DeleteQueue, mid)
        assert queued.status == "failed" and queued.error == "file_identity_changed"
        assert s.get(MediaCacheIndex, mid) is not None


def test_local_delete_rejects_same_size_swap_after_identity_check(native_db, tmp_path):
    """首次句柄复验后发生同尺寸叶替换，实际删除前必须以绑定身份拒绝。"""
    payload = b"A" * 64
    root, _ = _register_and_scan(native_db, tmp_path, {"keep.jpg": payload})
    target, mid = root / "keep.jpg", _indexed(native_db)[0]
    replacement = tmp_path / "replacement.jpg"
    replacement.write_bytes(b"B" * len(payload))
    with native_db.session() as s:
        delete_queue.enqueue(s, mid, size_bytes=len(payload))
        nonce = delete_queue.prepare_commit(s).nonce
        s.commit()

    def swap_leaf():
        os.replace(replacement, target)

    outcome = _commit_pending_with_nonce(
        native_db, nonce, local_delete_hook=swap_leaf
    )
    assert outcome == {mid: "failed"} and outcome.deleted_bytes == 0
    assert target.read_bytes() == b"B" * len(payload)
    with native_db.session() as s:
        queued = s.get(DeleteQueue, mid)
        assert queued.status == "failed"
        assert queued.error == "identity_binding_failed"


def test_local_delete_rejects_parent_junction_swap_after_identity_check(
    native_db, tmp_path
):
    """首次身份复验与删除之间换父目录为根外 junction，必须 failed 且不删根外文件。"""
    if os.name != "nt":
        pytest.skip("Windows junction 竞态专项")
    import subprocess

    payload = b"outside-must-survive"
    root, _ = _register_and_scan(
        native_db, tmp_path, {"album/victim.jpg": payload}
    )
    original_parent = root / "album"
    parked_parent = root / "album-before-race"
    outside = tmp_path / "outside-race"
    outside.mkdir()
    outside_target = outside / "victim.jpg"
    outside_target.write_bytes(payload)

    # 先探测当前 token 是否能创建 junction；不能则明确 skip，不伪装覆盖成功。
    probe = root / "junction-probe"
    made = subprocess.run(
        ["cmd", "/c", "mklink", "/J", str(probe), str(outside)],
        capture_output=True,
    )
    if made.returncode:
        reason = made.stderr.decode(errors="replace").strip() or "mklink /J failed"
        pytest.skip(f"当前环境无法创建 junction: {reason}")
    os.rmdir(probe)

    mid = _indexed(native_db)[0]
    with native_db.session() as s:
        delete_queue.enqueue(s, mid, size_bytes=len(payload))
        nonce = delete_queue.prepare_commit(s).nonce
        s.commit()

    def swap_parent_to_junction():
        original_parent.rename(parked_parent)
        result = subprocess.run(
            ["cmd", "/c", "mklink", "/J", str(original_parent), str(outside)],
            capture_output=True,
        )
        if result.returncode:
            raise RuntimeError("junction race setup failed")

    try:
        outcome = _commit_pending_with_nonce(
            native_db, nonce, local_delete_hook=swap_parent_to_junction
        )
        assert outcome == {mid: "failed"} and outcome.deleted_bytes == 0
        assert outside_target.read_bytes() == payload
        assert (parked_parent / "victim.jpg").read_bytes() == payload
        with native_db.session() as s:
            queued = s.get(DeleteQueue, mid)
            assert queued.status == "failed"
            assert queued.error in {"identity_binding_failed", "path_rejected"}
    finally:
        if original_parent.exists() and (
            original_parent.lstat().st_file_attributes & 0x400
        ):
            os.rmdir(original_parent)
        if parked_parent.exists() and not original_parent.exists():
            parked_parent.rename(original_parent)


def test_local_delete_rejects_escape_absolute_and_directory(native_db, tmp_path):
    """伪造索引路径：越界/绝对路径/UNC/目录一律路径拒绝，绝不删除根外文件。"""
    root, _ = _register_and_scan(native_db, tmp_path, {"inside.jpg": b"inside"})
    (root / "subdir").mkdir()
    outside = tmp_path / "outside.jpg"
    outside.write_bytes(b"outside")
    mid = _indexed(native_db)[0]
    forged_paths = (
        "../outside.jpg",
        str(outside),
        r"\\untrusted-host\share\outside.jpg",
        "subdir",
    )
    for forged in forged_paths:
        with native_db.session() as s:
            s.get(MediaCacheIndex, mid).media_path = forged
            with pytest.raises(native_sources.SourcePathError):
                delete_queue.enqueue(s, mid)
            s.rollback()

    with native_db.session() as s:
        s.get(MediaCacheIndex, mid).media_path = "../outside.jpg"
        s.add(DeleteQueue(media_id=mid, status="pending", size_bytes=6))
        s.commit()
    assert _commit_pending(native_db) == {mid: "failed"}
    with native_db.session() as s:
        assert s.get(DeleteQueue, mid).error == "path_rejected"
    assert outside.read_bytes() == b"outside"


def test_local_delete_cannot_cross_roots(native_db, tmp_path):
    """B 根的媒体不能用绝对路径指向 A 根文件跨根越界删除。"""
    root_a = tmp_path / "a"
    root_a.mkdir()
    victim = root_a / "victim.jpg"
    victim.write_bytes(b"cross-root-victim")
    root_b = tmp_path / "b"
    root_b.mkdir()
    with native_db.session() as s:
        native_sources.register_root(s, "A", str(root_a))
        root_b_id = native_sources.register_root(s, "B", str(root_b)).root_id
        s.add(MediaCacheIndex(
            media_id="loc_crossroot0000000", source_type="local",
            jellyfin_id="local:cross-root", library_id=root_b_id, name="victim.jpg",
            media_type="image", fingerprint="forged", size_bytes=len(b"cross-root-victim"),
            media_path=str(victim),
        ))
        s.add(DeleteQueue(media_id="loc_crossroot0000000", status="pending",
                          size_bytes=len(b"cross-root-victim")))
        s.commit()
    assert _commit_pending(native_db) == {"loc_crossroot0000000": "failed"}
    with native_db.session() as s:
        assert s.get(DeleteQueue, "loc_crossroot0000000").error == "path_rejected"
    assert victim.read_bytes() == b"cross-root-victim"


def test_local_delete_rejects_junction_chain(native_db, tmp_path):
    """根内 junction 指向根外目录时必须路径拒绝(junction 本身绝不跟随)。"""
    if os.name != "nt":
        pytest.skip("Windows junction 专项")
    import subprocess

    root, _root_id = _register_and_scan(native_db, tmp_path, {"keep.jpg": b"keep"})
    outside = tmp_path / "outside"
    outside.mkdir()
    (outside / "secret.jpg").write_bytes(b"never-delete")
    link = root / "junction"
    made = subprocess.run(["cmd", "/c", "mklink", "/J", str(link), str(outside)],
                          capture_output=True)
    if made.returncode:
        pytest.skip("当前环境无法创建 junction")
    try:
        mid = _indexed(native_db)[0]
        with native_db.session() as s:
            s.get(MediaCacheIndex, mid).media_path = "junction/secret.jpg"
            with pytest.raises(native_sources.SourcePathError):
                delete_queue.enqueue(s, mid)
            s.rollback()
        with native_db.session() as s:
            s.get(MediaCacheIndex, mid).media_path = "junction/secret.jpg"
            s.add(DeleteQueue(media_id=mid, status="pending", size_bytes=11))
            s.commit()
        assert _commit_pending(native_db) == {mid: "failed"}
        with native_db.session() as s:
            assert s.get(DeleteQueue, mid).error == "path_rejected"
        assert (outside / "secret.jpg").read_bytes() == b"never-delete"
    finally:
        assert link.parent == root and link.lstat().st_file_attributes & 0x400
        os.rmdir(link)


def test_local_delete_rejects_file_swapped_to_symlink(native_db, tmp_path):
    """入队后把文件换成符号链接(换链) → 拒绝删除且不触碰链接目标。"""
    root, _ = _register_and_scan(native_db, tmp_path, {"keep.jpg": b"original"})
    target, mid = root / "keep.jpg", _indexed(native_db)[0]
    outside = tmp_path / "outside.jpg"
    outside.write_bytes(b"outside-target")
    with native_db.session() as s:
        delete_queue.enqueue(s, mid, size_bytes=8)
        s.commit()
    target.unlink()
    try:
        target.symlink_to(outside)
    except (OSError, NotImplementedError) as exc:
        pytest.skip(f"文件符号链接不可用: {exc}")
    outcome = _commit_pending(native_db)
    assert outcome == {mid: "failed"}
    with native_db.session() as s:
        assert s.get(DeleteQueue, mid).error == "path_rejected"
    assert outside.read_bytes() == b"outside-target"


def test_local_commit_single_failure_does_not_abort_batch(native_db, tmp_path):
    """逐条处理：一条身份失败不得中断整批，其余项照常删除。"""
    payload = b"batch-good"
    root, _ = _register_and_scan(
        native_db, tmp_path, {"good.jpg": payload, "bad.jpg": b"A" * 32}
    )
    with native_db.session() as s:
        media = [(row.name, row.media_id, row.size_bytes)
                 for row in s.scalars(sa.select(MediaCacheIndex)).all()]
    with native_db.session() as s:
        rows = {name: mid for name, mid, _size in media}
        for _name, mid, size in media:
            delete_queue.enqueue(s, mid, size_bytes=size)
        s.commit()
    bad_target = root / "bad.jpg"
    replacement = tmp_path / "bad-new.jpg"
    replacement.write_bytes(b"B" * 32)
    os.replace(replacement, bad_target)
    future = bad_target.stat().st_mtime + 3600
    os.utime(bad_target, (future, future))

    outcome = _commit_pending(native_db)
    assert outcome == {rows["good.jpg"]: "success", rows["bad.jpg"]: "failed"}
    assert not (root / "good.jpg").exists()
    assert bad_target.exists()


def test_local_forged_or_out_of_snapshot_nonce_never_deletes(native_db, tmp_path):
    """伪造/快照外 nonce 均不得删除本地文件(两阶段 nonce 合同不变)。"""
    root, _ = _register_and_scan(
        native_db, tmp_path, {"a.jpg": b"aa", "b.jpg": b"bbbb"}
    )
    with native_db.session() as s:
        rows = {row.name: row.media_id for row in s.scalars(sa.select(MediaCacheIndex)).all()}
        delete_queue.enqueue(s, rows["a.jpg"], size_bytes=2)
        s.commit()
    with native_db.session() as s:
        with pytest.raises(delete_queue.NonceMissingError):
            delete_queue.commit_with_nonce(s, "deadbeef" * 4)
    assert (root / "a.jpg").exists() and (root / "b.jpg").exists()

    with native_db.session() as s:
        nonce = delete_queue.prepare_commit(s).nonce
        s.commit()
    _forge_pending(native_db, rows["b.jpg"], 4)  # snapshot 之后新入队
    outcome = _commit_pending_with_nonce(native_db, nonce)
    assert outcome == {rows["a.jpg"]: "success"}
    assert (root / "b.jpg").exists(), "nonce 快照外的媒体不得被顺手删除"
    with native_db.session() as s:
        assert s.get(DeleteQueue, rows["b.jpg"]) is not None


def test_local_file_in_use_maps_permission_error(native_db, tmp_path):
    """播放占用导致的 PermissionError → failed:file_in_use，文件保留且可重试。"""
    from unittest.mock import patch

    root, _ = _register_and_scan(native_db, tmp_path, {"locked.jpg": b"locked"})
    target, mid = root / "locked.jpg", _indexed(native_db)[0]
    with native_db.session() as s:
        delete_queue.enqueue(s, mid, size_bytes=6)
        nonce = delete_queue.prepare_commit(s).nonce
        s.commit()
    if os.name == "nt":
        patch_target = "app.services.native_sources._windows_delete_bound"
        side_effect = native_sources.LocalFileInUseError()
    else:
        patch_target = "os.remove"
        side_effect = PermissionError("sharing violation")
    with patch(patch_target, side_effect=side_effect):
        assert _commit_pending_with_nonce(native_db, nonce) == {mid: "failed"}
    with native_db.session() as s:
        queued = s.get(DeleteQueue, mid)
        assert queued.status == "failed" and queued.error == "file_in_use"
    assert target.read_bytes() == b"locked"


def test_windows_posix_disposition_hides_name_and_cleans_metadata(native_db, tmp_path):
    """共享读取句柄仍打开时，POSIX disposition 必须让名字立即消失并成功收敛。"""
    if os.name != "nt":
        pytest.skip("Windows delete-pending 专项")

    root, _ = _register_and_scan(native_db, tmp_path, {"pending.jpg": b"delete-pending"})
    target, mid = root / "pending.jpg", _indexed(native_db)[0]
    with native_db.session() as session:
        session.add(Favorite(media_id=mid))
        delete_queue.enqueue(session, mid, size_bytes=len(b"delete-pending"))
        nonce = delete_queue.prepare_commit(session).nonce
        session.commit()

    kernel32, reader = _open_windows_read_handle(target, share_delete=True)
    try:
        receipt = _commit_pending_with_nonce(native_db, nonce)
        assert receipt == {mid: "success"}
        assert receipt.deleted_bytes == len(b"delete-pending")
        assert not target.exists()
        assert _windows_handle_still_references_file(kernel32, reader)
        with native_db.session() as session:
            assert session.get(DeleteQueue, mid) is None
            assert session.get(MediaCacheIndex, mid) is None
            assert session.get(Favorite, mid) is None
            assert (
                session.scalar(
                    sa.select(AuditLog.detail)
                    .where(AuditLog.action == "delete_commit", AuditLog.media_id == mid)
                    .order_by(AuditLog.id.desc())
                )
                == "success"
            )
    finally:
        assert kernel32.CloseHandle(reader)
    assert not target.exists()


def test_windows_posix_cleanup_failure_stays_delete_done_and_recovers(
    native_db, tmp_path, monkeypatch
):
    """名字已消失后清理失败仍回 success，并由 delete_done 恢复补齐元数据与审计。"""
    if os.name != "nt":
        pytest.skip("Windows POSIX disposition 专项")

    root, _ = _register_and_scan(native_db, tmp_path, {"cleanup.jpg": b"cleanup"})
    target, mid = root / "cleanup.jpg", _indexed(native_db)[0]
    with native_db.session() as session:
        session.add(Favorite(media_id=mid))
        delete_queue.enqueue(session, mid, size_bytes=7)
        nonce = delete_queue.prepare_commit(session).nonce
        session.commit()

    original_log = audit.log_action
    failed_once = False

    def fail_cleanup_audit(db_session, action, media_id=None, detail=None):
        nonlocal failed_once
        if action == "delete_commit" and media_id == mid and not failed_once:
            failed_once = True
            raise RuntimeError("injected cleanup audit failure")
        return original_log(db_session, action, media_id, detail)

    kernel32, reader = _open_windows_read_handle(target, share_delete=True)
    try:
        monkeypatch.setattr(audit, "log_action", fail_cleanup_audit)
        receipt = _commit_pending_with_nonce(native_db, nonce)
        assert receipt == {mid: "success"}
        assert not target.exists()
        with native_db.session() as session:
            row = session.get(DeleteQueue, mid)
            assert row.status == "delete_done"
            assert session.get(MediaCacheIndex, mid) is not None
            assert session.get(Favorite, mid) is not None
    finally:
        assert kernel32.CloseHandle(reader)

    monkeypatch.undo()
    with native_db.session() as session:
        delete_queue.recover_interrupted_deletes(session)
    with native_db.session() as session:
        assert session.get(DeleteQueue, mid) is None
        assert session.get(MediaCacheIndex, mid) is None
        assert session.get(Favorite, mid) is None
        assert session.scalar(
            sa.select(AuditLog.detail)
            .where(AuditLog.action == "delete_commit", AuditLog.media_id == mid)
            .order_by(AuditLog.id.desc())
        ) == "metadata_cleanup_recovered"


def test_windows_legacy_disposition_is_pending_until_last_handle_closes(
    native_db, tmp_path
):
    """传统 disposition 只返回 pending；恢复只观察，最后句柄关闭后才清理。"""
    if os.name != "nt":
        pytest.skip("Windows legacy disposition 专项")

    root, _ = _register_and_scan(native_db, tmp_path, {"legacy.jpg": b"legacy"})
    target, mid = root / "legacy.jpg", _indexed(native_db)[0]
    with native_db.session() as session:
        session.add(Favorite(media_id=mid))
        delete_queue.enqueue(session, mid, size_bytes=6)
        nonce = delete_queue.prepare_commit(session).nonce
        session.commit()

    kernel32, reader = _open_windows_read_handle(target, share_delete=True)
    receipt = _commit_pending_with_nonce(
        native_db, nonce, force_legacy_disposition=True
    )
    assert receipt == {mid: "pending"}
    assert target.exists()
    with native_db.session() as session:
        row = session.get(DeleteQueue, mid)
        assert row.status == "delete_pending"
        assert session.get(MediaCacheIndex, mid) is not None
    with native_db.session() as session:
        delete_queue.recover_interrupted_deletes(session)
    with native_db.session() as session:
        assert session.get(DeleteQueue, mid).status == "delete_pending"

    assert kernel32.CloseHandle(reader)
    assert not target.exists()
    with native_db.session() as session:
        delete_queue.recover_interrupted_deletes(session)
    with native_db.session() as session:
        assert session.get(DeleteQueue, mid) is None
        assert session.get(MediaCacheIndex, mid) is None
        assert session.get(Favorite, mid) is None
        details = list(
            session.scalars(
                sa.select(AuditLog.detail).where(
                    AuditLog.action == "delete_commit", AuditLog.media_id == mid
                )
            )
        )
        assert details == ["pending", "recovered_deleted"]


@pytest.mark.parametrize(
    ("force_legacy", "expected_receipt", "expected_status"),
    [(False, "success", None), (True, "pending", "delete_pending")],
)
def test_windows_production_close_handle_failure_has_recoverable_semantics(
    native_db, tmp_path, monkeypatch, force_legacy, expected_receipt, expected_status
):
    """生产删除句柄 CloseHandle 报失败：POSIX 名字已消失仍成功，传统路径保持 pending。"""
    if os.name != "nt":
        pytest.skip("Windows CloseHandle 故障注入专项")

    name = "close-legacy.jpg" if force_legacy else "close-posix.jpg"
    root, _ = _register_and_scan(native_db, tmp_path, {name: b"close"})
    target, mid = root / name, _indexed(native_db)[0]
    with native_db.session() as session:
        delete_queue.enqueue(session, mid, size_bytes=5)
        nonce = delete_queue.prepare_commit(session).nonce
        session.commit()

    called = False

    def close_but_report_failure(close_handle, handle):
        nonlocal called
        called = True
        assert close_handle(handle)
        return False

    monkeypatch.setattr(
        native_sources, "_close_windows_handle", close_but_report_failure, raising=False
    )
    receipt = _commit_pending_with_nonce(
        native_db, nonce, force_legacy_disposition=force_legacy
    )
    assert called
    assert receipt == {mid: expected_receipt}
    with native_db.session() as session:
        row = session.get(DeleteQueue, mid)
        assert (None if row is None else row.status) == expected_status
    assert not target.exists()


def test_windows_handle_without_share_delete_fails_file_in_use(native_db, tmp_path):
    """既有句柄未共享 DELETE 时，真实 disposition 路径必须失败且文件保留。"""
    if os.name != "nt":
        pytest.skip("Windows sharing violation 专项")

    root, _ = _register_and_scan(native_db, tmp_path, {"busy.jpg": b"busy"})
    target, mid = root / "busy.jpg", _indexed(native_db)[0]
    with native_db.session() as session:
        delete_queue.enqueue(session, mid, size_bytes=4)
        nonce = delete_queue.prepare_commit(session).nonce
        session.commit()

    kernel32, reader = _open_windows_read_handle(target, share_delete=False)
    try:
        assert _commit_pending_with_nonce(native_db, nonce) == {mid: "failed"}
        assert target.read_bytes() == b"busy"
        with native_db.session() as session:
            queued = session.get(DeleteQueue, mid)
            assert queued.status == "failed"
            assert queued.error == "file_in_use"
    finally:
        assert kernel32.CloseHandle(reader)
    assert target.read_bytes() == b"busy"


def test_windows_access_denied_has_stable_delete_code(native_db, tmp_path):
    """只读属性导致 Win32 error 5 时，不得误报为文件占用。"""
    if os.name != "nt":
        pytest.skip("Windows access denied 专项")

    root, _ = _register_and_scan(native_db, tmp_path, {"readonly.jpg": b"readonly"})
    target, mid = root / "readonly.jpg", _indexed(native_db)[0]
    with native_db.session() as session:
        delete_queue.enqueue(session, mid, size_bytes=8)
        nonce = delete_queue.prepare_commit(session).nonce
        session.commit()
    target.chmod(stat.S_IREAD)
    try:
        assert _commit_pending_with_nonce(native_db, nonce) == {mid: "failed"}
        with native_db.session() as session:
            queued = session.get(DeleteQueue, mid)
            assert queued.status == "failed"
            assert queued.error == "delete_access_denied"
    finally:
        target.chmod(stat.S_IWRITE)
    assert target.read_bytes() == b"readonly"


def test_windows_handle_delete_failure_never_falls_back_to_path_remove(
    native_db, tmp_path
):
    """Windows 句柄删除失败必须 fail-closed，禁止回退到字符串路径 os.remove。"""
    if os.name != "nt":
        pytest.skip("Windows 句柄删除专项")
    from unittest.mock import patch

    root, _ = _register_and_scan(native_db, tmp_path, {"keep.jpg": b"keep"})
    target, mid = root / "keep.jpg", _indexed(native_db)[0]
    with native_db.session() as s:
        delete_queue.enqueue(s, mid, size_bytes=4)
        nonce = delete_queue.prepare_commit(s).nonce
        s.commit()
    with (
        patch(
            "app.services.native_sources._windows_delete_bound",
            side_effect=native_sources.LocalDeleteNotVerifiedError(),
        ),
        patch("os.remove") as path_remove,
    ):
        assert _commit_pending_with_nonce(native_db, nonce) == {mid: "failed"}
        path_remove.assert_not_called()
    with native_db.session() as s:
        queued = s.get(DeleteQueue, mid)
        assert queued.status == "failed" and queued.error == "delete_not_verified"
    assert target.read_bytes() == b"keep"


def test_local_delete_api_two_phase_receipt(app, tmp_path):
    """API 闭环：本地源入队 → prepare nonce → commit 回执真实字节数。"""
    from fastapi.testclient import TestClient

    root = tmp_path / "api-source"
    root.mkdir()
    target = root / "api.jpg"
    target.write_bytes(b"api-bytes")
    with TestClient(app) as client:
        with app.state.database.session() as s:
            source = native_sources.register_root(s, "API本地来源", str(root))
            task = native_sources.schedule_scan(s, source.root_id)
            task.status = "running"
            task_id = task.task_id
            s.commit()
        native_sources.run_scan(app.state.database, task_id)
        with app.state.database.session() as s:
            mid = s.scalar(sa.select(MediaCacheIndex.media_id))
        assert client.post(f"/api/v1/delete-queue/{mid}").status_code == 200
        assert target.exists(), "入队阶段不得删除"
        prep = client.post("/api/v1/delete-queue/commit/prepare").json()["data"]
        assert prep["count"] == 1 and prep["media_ids"] == [mid]
        receipt = client.post(
            "/api/v1/delete-queue/commit", json={"nonce": prep["nonce"]}
        ).json()["data"]
        assert receipt["outcome"] == {mid: "success"}
        assert receipt["deleted_count"] == 1
        assert receipt["deleted_bytes"] == len(b"api-bytes")
        assert not target.exists()


def test_extended_container_whitelist_scans_correct_media_type(native_db, tmp_path):
    """去 Jellyfin 依赖：常见容器按 media_type 正确分类，未知扩展名跳过。"""
    files = {
        "clip.m4v": b"m4v", "clip.m2ts": b"m2ts", "clip.avi": b"avi", "clip.wmv": b"wmv",
        "clip.flv": b"flv", "clip.mpg": b"mpg", "clip.mpeg": b"mpeg", "clip.ts": b"ts",
        "photo.gif": b"gif", "photo.bmp": b"bmp", "photo.tif": b"tif", "photo.tiff": b"tiff",
        "notes.txt": b"skip",
    }
    _register_and_scan(native_db, tmp_path, files)
    with native_db.session() as s:
        classified = {row.name: row.media_type for row in s.scalars(sa.select(MediaCacheIndex))}
    assert "notes.txt" not in classified
    for name in ("clip.m4v", "clip.m2ts", "clip.avi", "clip.wmv", "clip.flv", "clip.mpg",
                 "clip.mpeg", "clip.ts"):
        assert classified[name] == "video", name
    for name in ("photo.gif", "photo.bmp", "photo.tif", "photo.tiff"):
        assert classified[name] == "image", name


def test_local_mode_without_jellyfin_diagnostics_and_dashboard(app, tmp_path):
    """无 Jellyfin 时：本地诊断不显示 Jellyfin 不可达，运维面板为信息态。"""
    from fastapi.testclient import TestClient

    root = tmp_path / "videos"
    root.mkdir()
    (root / "sample.mp4").write_bytes(b"0123456789abcdef")
    with TestClient(app) as client:
        with app.state.database.session() as s:
            source = native_sources.register_root(s, "本地视频", str(root))
            task = native_sources.schedule_scan(s, source.root_id)
            task.status = "running"
            task_id = task.task_id
            s.commit()
        native_sources.run_scan(app.state.database, task_id)
        with app.state.database.session() as s:
            mid = s.scalar(sa.select(MediaCacheIndex.media_id))
        readiness = client.get(f"/api/v1/system/playback-readiness?media_id={mid}")
        assert readiness.status_code == 200, readiness.text
        data = readiness.json()["data"]
        assert data["source_type"] == "local"
        assert data["jellyfin_server_reachable"] is None
        assert data["local_direct_read"]["status"] == "readable"
        assert "本地来源直读" in data["local_direct_read"]["message"]
        assert "不可达" not in readiness.text
        assert str(root) not in readiness.text

        dashboard = client.get("/api/v1/system/dashboard")
        assert dashboard.status_code == 200, dashboard.text
        jellyfin = dashboard.json()["data"]["jellyfin"]
        assert jellyfin["configured"] is False and jellyfin["mode"] == "local_only"
        assert "本地来源模式" in jellyfin["message"] and jellyfin["error"] is None


def test_media_api_user_message_points_to_local_sources(client):
    """无 Jellyfin 用户时错误文案同时提示登记本地来源与勾选 Jellyfin 媒体库。"""
    response = client.get("/api/v1/media")
    assert response.status_code == 422, response.text
    assert "本地来源" in response.text and "Jellyfin" in response.text


def test_native_api_no_jellyfin_no_path_leak_or_query_scan(app, tmp_path, monkeypatch):
    from fastapi.testclient import TestClient
    source = tmp_path / "source"
    source.mkdir()
    (source / "sample.jpg").write_bytes(b"sample")
    with TestClient(app, base_url="http://127.0.0.1", client=("127.0.0.1", 1234)) as client:
        response = client.post("/api/v1/sources/local", json={"name": "样本", "path": str(source)})
        assert response.status_code == 202, response.text
        root_id = response.json()["data"]["source"]["source_id"]
        task_id = response.json()["data"]["task"]["task_id"]
        with app.state.database.session() as s:
            s.get(BackgroundTask, task_id).status = "running"
            s.commit()
        native_sources.run_scan(app.state.database, task_id)
        monkeypatch.setattr(native_sources, "_walk", lambda *a: pytest.fail("GET must not scan"))
        libraries = client.get("/api/v1/libraries")
        assert libraries.status_code == 200
        assert libraries.json()["data"][0]["can_delete"] is True
        page = client.get("/api/v1/media").json()
        assert page["data"]["items"][0]["source_type"] == "local"
        assert page["data"]["items"][0]["can_delete"] is True
        assert client.get("/api/v1/media?source_type=local").json()["data"]["total"] == 1
        assert client.get("/api/v1/media?source_type=jellyfin").json()["data"]["total"] == 0
        mid = page["data"]["items"][0]["media_id"]
        # 已配对手机可入队/撤销；本地删除走两阶段队列(此处不 commit)。
        assert client.post(f"/api/v1/delete-queue/{mid}").status_code == 200
        assert client.delete(f"/api/v1/delete-queue/{mid}").status_code == 200
        assert client.post(f"/api/v1/favorites/{mid}").status_code == 200
        favorite = client.get("/api/v1/favorites").json()["data"][0]["media"]
        assert favorite["source_type"] == "local" and favorite["cover_url"]
        session = client.post("/api/v1/review/sessions", json={"source": {"filter": {"media_type": "image"}}})
        assert session.status_code == 200, session.text
        sid = session.json()["data"]["session_id"]
        queue = client.get(f"/api/v1/review/sessions/{sid}/queue").json()["data"]["items"]
        assert queue[0]["media"]["media_id"] == mid and queue[0]["media"]["cover_url"]
        assert str(source) not in json.dumps(page)
        assert client.post("/api/v1/sources/local", json={"name": "样本", "path": str(source)},
                           headers={"Origin": "https://evil.invalid"}).status_code == 403
    with TestClient(app) as client:
        assert client.post(f"/api/v1/sources/{root_id}/scan").status_code == 403


def test_additive_migration_preserves_0014_rows_and_rollback(tmp_path):
    from alembic import command
    from alembic.config import Config

    from app.db.migrate import _server_root
    config = Config(str(_server_root() / "alembic.ini"))
    config.set_main_option("script_location", str(_server_root() / "app/db/migrations"))
    db_path = tmp_path / "old.sqlite"
    config.set_main_option("sqlalchemy.url", f"sqlite:///{db_path}")
    command.upgrade(config, "0014")
    import sqlite3
    with sqlite3.connect(db_path) as connection:
        connection.execute("INSERT INTO library_selection (jellyfin_id,name,selected,sort_order,updated_at) VALUES ('old-lib','旧库',1,0,CURRENT_TIMESTAMP)")
        connection.execute("INSERT INTO media_cache_index (media_id,jellyfin_id,library_id,name,media_type,fingerprint,synced_at) VALUES ('old-media','jf-old','old-lib','旧片','video','old-fp',CURRENT_TIMESTAMP)")
        connection.execute("INSERT INTO favorite (media_id,created_at) VALUES ('old-media',CURRENT_TIMESTAMP)")
    command.upgrade(config, "head")
    with sqlite3.connect(db_path) as connection:
        assert connection.execute("SELECT media_id,jellyfin_id,source_type FROM media_cache_index").fetchone() == ("old-media", "jf-old", "jellyfin")
        assert connection.execute("SELECT media_id FROM favorite").fetchone()[0] == "old-media"
    command.downgrade(config, "0014")
    with sqlite3.connect(db_path) as connection:
        assert connection.execute("SELECT media_id FROM favorite").fetchone()[0] == "old-media"
    command.upgrade(config, "head")
    with sqlite3.connect(db_path) as connection:
        connection.execute("INSERT INTO local_root VALUES ('native-root','样本','test-only',1,'pending',NULL)")
    with pytest.raises(RuntimeError, match="禁止有损降级"):
        command.downgrade(config, "0014")


def test_windows_junction_never_followed(native_db, tmp_path):
    import os
    import subprocess
    if os.name != "nt":
        pytest.skip("Windows junction专项")
    root, outside = tmp_path / "root", tmp_path / "outside"
    root.mkdir()
    outside.mkdir()
    (outside / "secret.jpg").write_bytes(b"temporary outside fixture")
    link = root / "junction"
    made = subprocess.run(["cmd", "/c", "mklink", "/J", str(link), str(outside)], capture_output=True)
    if made.returncode:
        pytest.skip("当前环境无法创建junction")
    try:
        with native_db.session() as s:
            source = native_sources.register_root(s, "样本", str(root))
            root_id = source.root_id
            with pytest.raises(native_sources.SourcePathError):
                native_sources.safe_path(source, "junction/secret.jpg")
            with pytest.raises(native_sources.SourcePathError):
                native_sources.register_root(s, "链接根", str(link))
            s.commit()
        assert scan(native_db, root_id).status == "succeeded"
        with native_db.session() as s:
            assert s.scalar(sa.select(sa.func.count()).select_from(MediaCacheIndex)) == 0
    finally:
        # 只移除本测试创建且确认是reparse的junction本身，绝不递归删除目标。
        assert link.parent == root and link.lstat().st_file_attributes & 0x400
        os.rmdir(link)
    assert (outside / "secret.jpg").exists()


def test_cancel_scan_does_not_hide_unseen_media(native_db, tmp_path, monkeypatch):
    root = tmp_path / "source"
    root.mkdir()
    (root / "one.jpg").write_bytes(b"one")
    (root / "two.jpg").write_bytes(b"two")
    with native_db.session() as s:
        root_id = native_sources.register_root(s, "样本", str(root)).root_id
        s.commit()
    scan(native_db, root_id)
    (root / "two.jpg").unlink()
    with native_db.session() as s:
        task = native_sources.schedule_scan(s, root_id)
        task.status = "running"
        task_id = task.task_id
        s.commit()
    walk = native_sources._walk
    def cancel_walk(path):
        for value in walk(path):
            with native_db.session() as s:
                s.get(BackgroundTask, task_id).status = "cancelled"
                s.commit()
            yield value
    monkeypatch.setattr(native_sources, "_walk", cancel_walk)
    native_sources.run_scan(native_db, task_id)
    with native_db.session() as s:
        assert all(r.is_available for r in s.scalars(sa.select(MediaCacheIndex)))


def test_registration_success_means_task_visible_to_next_connection(native_db, tmp_path):
    from types import SimpleNamespace

    from app.api.v1.sources import RegisterRoot, register_local
    from app.db.models import LocalRoot
    folder = tmp_path / "samples"
    folder.mkdir()
    request = SimpleNamespace(app=SimpleNamespace(state=SimpleNamespace(
        paths=SimpleNamespace(data_root=tmp_path / "data"))))
    with native_db.session() as write_session:
        response = register_local(RegisterRoot(name="样本", path=str(folder)), request, write_session)
        with native_db.session() as next_connection:
            assert next_connection.get(BackgroundTask, response.data["task"]["task_id"]) is not None
            assert next_connection.get(LocalRoot, response.data["source"]["source_id"]) is not None
