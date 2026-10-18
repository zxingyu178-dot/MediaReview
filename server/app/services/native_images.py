"""本地图片衍生任务复用单 TaskManager；原图只读句柄分块传输。"""
from __future__ import annotations

import asyncio
import io
import json
import subprocess
import time
import uuid

import sqlalchemy as sa
from PIL import Image, ImageOps

from app.core.errors import AppError, ValidationFailedError
from app.db.models import BackgroundTask, MediaCacheIndex, utc_now
from app.media.ffmpeg import FfmpegExecutor
from app.services.native_sources import open_media
from app.services.thumbnail_cache import THUMBNAIL_PROFILES, ThumbnailFetchResult, media_source_fingerprint, thumbnail_cache_key

TASK_TYPE = "local_thumbnail"
MAX_PIXELS = 32_000_000
MAX_IMAGE_BYTES = 25 * 1024 * 1024
QUEUE_LIMIT = 64
FORMATS = {"JPEG": "image/jpeg", "PNG": "image/png", "WEBP": "image/webp"}


class ThumbnailPending(AppError):
    status_code = 503
    code = "LOCAL_IMAGE_PENDING"
    default_message = "本地封面仍在后台队列中，请稍后重试"


def inspect_image(handle, size):
    if size > MAX_IMAGE_BYTES:
        raise ValidationFailedError("图片超过25 MiB读取限制")
    try:
        image = Image.open(handle, formats=list(FORMATS))
        if image.width * image.height > MAX_PIXELS:
            image.close()
            raise ValidationFailedError("图片像素超过安全解码限制")
        return image
    except (OSError, ValueError, Image.DecompressionBombError):
        raise ValidationFailedError("图片内容无效或像素超过安全限制") from None


def render_thumbnail(handle, size, profile):
    image = inspect_image(handle, size)
    try:
        # JPEG 先通过 draft 缩小解码；EXIF 在裁剪前校正。
        image.draft("RGB", (profile.width * 2, profile.height * 2))
        oriented = ImageOps.exif_transpose(image)
        rgb = oriented.convert("RGB")
        if profile.fill:
            output = ImageOps.fit(rgb, (profile.width, profile.height))
        else:
            rgb.thumbnail((profile.width, profile.height))
            output = rgb
        buffer = io.BytesIO()
        output.save(buffer, "JPEG", quality=profile.quality)
        output.close()
        rgb.close()
        oriented.close()
        return buffer.getvalue(), "image/jpeg"
    finally:
        image.close()


def make_handler(cache, ffmpeg_dir):
    executor = FfmpegExecutor(ffmpeg_dir)

    def handle(database, task_id):
        with database.session() as session:
            task = session.get(BackgroundTask, task_id)
            if task.status != "running":
                return
            params = json.loads(task.params)
            row = session.get(MediaCacheIndex, params["media_id"])
            try:
                profile = THUMBNAIL_PROFILES[params["profile"]]
                key = thumbnail_cache_key(media_id=row.media_id,
                    fingerprint=media_source_fingerprint(row), variant=profile.variant)
                if key != params["key"]:
                    raise ValidationFailedError("来源版本已变化，请重试封面")
                file, info = open_media(session, row)
                with file:
                    if row.media_type == "image":
                        payload, content_type = render_thumbnail(file, info.st_size, profile)
                    else:
                        # ffmpeg 仅输出一张有界 JPEG，stdin 为已验证句柄，不再按路径重开源文件。
                        completed = subprocess.run([executor._ffmpeg(), "-v", "error", "-threads", "1",
                            "-i", "pipe:0", "-frames:v", "1", "-vf",
                            f"scale={profile.width}:{profile.height}:force_original_aspect_ratio=decrease",
                            "-f", "image2pipe", "-c:v", "mjpeg", "pipe:1"],
                            stdin=file, capture_output=True, timeout=15, check=False,
                            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
                        if completed.returncode or not completed.stdout or len(completed.stdout) > 2 * 1024 * 1024:
                            raise ValidationFailedError("视频封面无法提取，仍可尝试直接播放")
                        payload, content_type = completed.stdout, "image/jpeg"
                session.refresh(task)
                if task.status != "running":
                    return
                cache.write(key, payload, content_type)
                # 共用现有缓存容量策略；任务在队列线程内执行。
                cache._writes_since_prune += 1
                if cache._writes_since_prune >= 32:
                    cache.prune()
                    cache._writes_since_prune = 0
                task.status, task.progress, task.finished_at = "succeeded", 100, utc_now()
            except Exception as exc:  # 任务必须落失败态；不持久化底层异常/路径。
                task.status, task.finished_at = "failed", utc_now()
                # 仅持久化已审查的业务文案，不记录文件路径或 ffmpeg stderr。
                task.error = exc.message if isinstance(exc, AppError) else "本地图片无法读取或处理超时"
            session.commit()
    return handle


async def get_thumbnail(database, cache, row, profile, key):
    cached = await asyncio.to_thread(cache.read, key)
    if cached:
        return ThumbnailFetchResult(cached[0], cached[1], True)
    with database.session() as session:
        # 获取写锁后查找同 key 活动任务，保证并发请求合并。
        session.execute(sa.update(MediaCacheIndex).where(MediaCacheIndex.media_id == row.media_id)
                        .values(is_available=MediaCacheIndex.is_available))
        tasks = list(session.scalars(sa.select(BackgroundTask).where(
            BackgroundTask.type == TASK_TYPE, BackgroundTask.status.in_(("pending", "running")))))
        task = next((t for t in tasks if json.loads(t.params)["key"] == key), None)
        if task is None:
            if len(tasks) >= QUEUE_LIMIT:
                raise ThumbnailPending()
            task = BackgroundTask(task_id=uuid.uuid4().hex, type=TASK_TYPE, status="pending",
                media_id=row.media_id, params=json.dumps({"media_id": row.media_id, "profile": profile.name, "key": key}))
            session.add(task)
        task_id = task.task_id
        session.commit()
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        await asyncio.sleep(0.15)
        with database.session() as session:
            task = session.get(BackgroundTask, task_id)
            if task.status == "failed":
                raise ValidationFailedError(task.error)
            if task.status == "succeeded":
                cached = await asyncio.to_thread(cache.read, key)
                if cached:
                    return ThumbnailFetchResult(cached[0], cached[1], False)
                raise ThumbnailPending()
            if task.status in ("cancelled", "paused"):
                raise ThumbnailPending()
    raise ThumbnailPending()
