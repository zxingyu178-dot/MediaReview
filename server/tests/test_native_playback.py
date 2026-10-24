"""原生视频认证、Range和本地进度：仅临时字节样本。"""
import json

import pytest
import sqlalchemy as sa
from fastapi.testclient import TestClient

from app.db.models import MediaCacheIndex
from app.services import native_sources


@pytest.fixture
def local_video(app, tmp_path):
    root = tmp_path / "video"
    root.mkdir()
    (root / "sample.mp4").write_bytes(b"0123456789abcdef")
    with TestClient(app) as client:
        with app.state.database.session() as s:
            source = native_sources.register_root(s, "样本", str(root))
            task = native_sources.schedule_scan(s, source.root_id)
            task.status = "running"
            task_id = task.task_id
            s.commit()
        native_sources.run_scan(app.state.database, task_id)
        with app.state.database.session() as s:
            mid = s.scalar(sa.select(MediaCacheIndex.media_id))
        yield client, mid, root


def test_range_head_conditionals_and_no_paths(local_video):
    client, mid, root = local_video
    info = client.get(f"/api/v1/media/{mid}/playback")
    assert info.status_code == 200, info.text
    contract = info.json()["data"]
    assert contract["fallback_hls"] is None and contract["source_type"] == "local"
    assert str(root) not in json.dumps(contract)
    url = contract["direct"]["url"]
    full = client.get(url)
    assert full.status_code == 200 and full.content == b"0123456789abcdef"
    assert full.headers["accept-ranges"] == "bytes"
    head = client.head(url)
    assert head.status_code == 200 and head.content == b"" and head.headers["content-length"] == "16"
    for byte_range, expected in (("bytes=2-5", b"2345"), ("bytes=12-", b"cdef"), ("bytes=-3", b"def")):
        response = client.get(url, headers={"Range": byte_range})
        assert response.status_code == 206 and response.content == expected
        assert int(response.headers["content-length"]) == len(expected)
        assert response.headers["content-range"].endswith("/16")
    for byte_range in ("bytes=16-", "bytes=7-2", "bytes=-0", "bytes=0-1,3-4", "bytes=nope"):
        response = client.get(url, headers={"Range": byte_range})
        assert response.status_code == 416 and response.headers["content-range"] == "bytes */16"
    assert client.get(url, headers={"If-None-Match": full.headers["etag"]}).status_code == 304
    assert client.head(url, headers={"If-Modified-Since": full.headers["last-modified"]}).status_code == 304
    assert client.get(url, headers={"Range": "bytes=2-3", "If-Range": '"stale"'}).status_code == 200
    assert client.get(url, headers={"Range": "bytes=2-3", "If-Range": full.headers["etag"]}).status_code == 206


def test_local_progress_no_jellyfin(local_video):
    client, mid, _root = local_video
    response = client.post(f"/api/v1/media/{mid}/progress", json={"position_ms": 1200, "is_paused": True})
    assert response.status_code == 200
    assert client.get(f"/api/v1/media/{mid}/playback").json()["data"]["resume_position_ms"] == 1200


def test_local_stream_auth_required_and_readonly(local_video, app):
    client, mid, _root = local_video
    app.state.settings.security.pairing_required = True
    for suffix in ("playback", "file", "thumbnail", "original"):
        assert client.get(f"/api/v1/media/{mid}/{suffix}").status_code == 401
    assert client.head(f"/api/v1/media/{mid}/file").status_code == 401
    assert client.get(f"/api/v1/media/{mid}/file", headers={"Authorization": "Bearer forged"}).status_code == 401


def test_file_replacement_never_streamed(local_video):
    client, mid, root = local_video
    (root / "sample.mp4").write_bytes(b"different file")
    assert client.get(f"/api/v1/media/{mid}/file").status_code == 409


def test_paired_bearer_carried_only_in_headers_and_revoked(local_video, app):
    from app.services import pairing
    client, mid, _root = local_video
    with app.state.database.session() as s:
        code = pairing.generate_pairing_code(s)
        token = pairing.verify_and_pair(s, code, "native-test-device")
        s.commit()
    app.state.settings.security.pairing_required = True
    headers = {"Authorization": "Bearer " + token}
    info = client.get(f"/api/v1/media/{mid}/playback", headers=headers).json()["data"]
    assert info["direct"]["headers"] == headers
    assert token not in info["direct"]["url"]
    assert client.get(info["direct"]["url"], headers=headers).status_code == 200
    with app.state.database.session() as s:
        pairing.revoke_device(s, "native-test-device")
        s.commit()
    assert client.get(info["direct"]["url"], headers=headers).status_code == 401


def test_extended_container_mime_mapping() -> None:
    """去 Jellyfin 依赖：常见容器必须有明确 MIME，不得回落到 octet-stream。"""
    from app.services.native_playback import MIME

    expected = {
        "m4v": "video/mp4",
        "ts": "video/mp2t",
        "m2ts": "video/mp2t",
        "avi": "video/x-msvideo",
        "wmv": "video/x-ms-wmv",
        "flv": "video/x-flv",
        "mpg": "video/mpeg",
        "mpeg": "video/mpeg",
    }
    for container, mime in expected.items():
        assert MIME.get(container) == mime, container


def test_metadata_probe_disposable_mp4(tmp_path):
    import subprocess
    from pathlib import Path

    from app.db.models import BackgroundTask
    from app.db.session import Database
    from app.services import native_metadata
    binaries = Path(__file__).resolve().parents[2] / "third_party/ffmpeg"
    if not (binaries / "ffmpeg.exe").is_file():
        pytest.skip("项目FFmpeg不可用")
    root = tmp_path / "sample"
    root.mkdir()
    subprocess.run([str(binaries / "ffmpeg.exe"), "-v", "error", "-f", "lavfi", "-i",
        "color=c=blue:s=64x48:r=2", "-t", "1", "-c:v", "libx264", "-movflags", "+faststart",
        str(root / "sample.mp4")], check=True, timeout=20, capture_output=True)
    db = Database(tmp_path / "index.sqlite")
    db.create_all()
    try:
        with db.session() as s:
            source = native_sources.register_root(s, "样本", str(root))
            scan = native_sources.schedule_scan(s, source.root_id)
            scan.status = "running"
            scan_id = scan.task_id
            s.commit()
        native_sources.run_scan(db, scan_id)
        with db.session() as s:
            row = s.scalar(sa.select(MediaCacheIndex))
            mid = row.media_id
            native_metadata.schedule(s, row)
            s.flush()
            probe = s.scalar(sa.select(BackgroundTask).where(BackgroundTask.type == native_metadata.TASK_TYPE))
            probe.status = "running"
            probe_id = probe.task_id
            s.commit()
        native_metadata.make_handler(str(binaries))(db, probe_id)
        with db.session() as s:
            assert s.get(BackgroundTask, probe_id).status == "succeeded"
            row = s.get(MediaCacheIndex, mid)
            assert row.duration_ms == 1000 and (row.width, row.height) == (64, 48)
    finally:
        db.dispose()
