"""本地图片仅临时合成图片，无真实媒体读取。"""
import io

import pytest
import sqlalchemy as sa
from PIL import Image
from fastapi.testclient import TestClient

from app.db.models import BackgroundTask, MediaCacheIndex
from app.services import native_sources, native_images


@pytest.fixture
def indexed_image(app, tmp_path):
    root = tmp_path / "photos"
    root.mkdir()
    photo = root / "oriented.jpg"
    image = Image.new("RGB", (40, 20), "red")
    exif = Image.Exif()
    exif[274] = 6
    image.save(photo, exif=exif)
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
        yield client, mid, photo


def test_original_and_thumbnail_orientation_cache(indexed_image, app):
    client, mid, photo = indexed_image
    original = client.get(f"/api/v1/media/{mid}/original")
    assert original.status_code == 200 and original.content == photo.read_bytes()
    thumbnail = client.get(f"/api/v1/media/{mid}/thumbnail?profile=ALBUM")
    assert thumbnail.status_code == 200, thumbnail.text
    with Image.open(io.BytesIO(thumbnail.content)) as result:
        assert result.height > result.width
    second = client.get(f"/api/v1/media/{mid}/thumbnail?profile=ALBUM")
    assert second.headers["x-mediareview-cache"] == "HIT"
    assert client.get(f"/api/v1/media/{mid}/thumbnail?profile=ALBUM", headers={
        "If-None-Match": second.headers["etag"]}).status_code == 304
    with app.state.database.session() as s:
        assert len(list(s.scalars(sa.select(BackgroundTask).where(BackgroundTask.type == native_images.TASK_TYPE)))) == 1


def test_changed_file_refused_before_cached_image_or_original(indexed_image):
    client, mid, photo = indexed_image
    assert client.get(f"/api/v1/media/{mid}/thumbnail").status_code == 200
    photo.write_bytes(b"changed entity")
    for suffix in ("original", "thumbnail"):
        response = client.get(f"/api/v1/media/{mid}/{suffix}")
        assert response.status_code == 409
        assert response.json()["request_id"]


def test_oversized_image_rejected_without_decode(indexed_image, monkeypatch):
    client, mid, photo = indexed_image
    monkeypatch.setattr(native_images, "MAX_PIXELS", 100)
    response = client.get(f"/api/v1/media/{mid}/thumbnail")
    assert response.status_code == 422
    assert "像素" in response.json()["error"]["message"]


def test_video_cover_from_disposable_clip(app, tmp_path):
    import subprocess
    from pathlib import Path
    binaries = Path(__file__).resolve().parents[2] / "third_party/ffmpeg"
    if not (binaries / "ffmpeg.exe").is_file():
        pytest.skip("项目FFmpeg不可用")
    app.state.settings.storage.ffmpeg_dir = str(binaries)
    root = tmp_path / "clips"
    root.mkdir()
    subprocess.run([str(binaries / "ffmpeg.exe"), "-v", "error", "-f", "lavfi", "-i",
        "color=c=blue:s=64x48:r=2", "-t", "1", "-c:v", "libx264", "-movflags", "+faststart",
        str(root / "sample.mp4")], check=True, timeout=20, capture_output=True)
    with TestClient(app) as client:
        with app.state.database.session() as s:
            source = native_sources.register_root(s, "临时片段", str(root))
            task = native_sources.schedule_scan(s, source.root_id)
            task.status = "running"
            task_id = task.task_id
            s.commit()
        native_sources.run_scan(app.state.database, task_id)
        with app.state.database.session() as s:
            mid = s.scalar(sa.select(MediaCacheIndex.media_id))
        response = client.get(f"/api/v1/media/{mid}/thumbnail")
        assert response.status_code == 200, response.text
        assert response.headers["content-type"] == "image/jpeg"
