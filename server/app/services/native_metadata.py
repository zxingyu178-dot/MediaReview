"""按需、有界 ffprobe 元数据探测，复用后台队列。"""
import json
import math
import subprocess
import uuid

import sqlalchemy as sa

from app.db.models import BackgroundTask, MediaCacheIndex, utc_now
from app.media.ffmpeg import FfmpegExecutor
from app.services.native_sources import open_media

TASK_TYPE = "local_probe"


def schedule(session, row):
    if row.duration_ms is not None:
        return
    session.execute(sa.update(MediaCacheIndex).where(MediaCacheIndex.media_id == row.media_id)
                    .values(is_available=MediaCacheIndex.is_available))
    previous = session.scalar(sa.select(BackgroundTask).where(BackgroundTask.type == TASK_TYPE,
        BackgroundTask.media_id == row.media_id).order_by(BackgroundTask.created_at.desc()).limit(1))
    # 同一实体失败不因每次播放无限重探；重扫后指纹变化才允许再试。
    if previous and json.loads(previous.params)["fingerprint"] == row.fingerprint:
        return
    count = session.scalar(sa.select(sa.func.count()).select_from(BackgroundTask).where(
        BackgroundTask.type == TASK_TYPE, BackgroundTask.status.in_(("pending", "running"))))
    if count >= 64:
        return
    session.add(BackgroundTask(task_id=uuid.uuid4().hex, type=TASK_TYPE, media_id=row.media_id,
        status="pending", params=json.dumps({"fingerprint": row.fingerprint})))


def make_handler(ffmpeg_dir):
    executor = FfmpegExecutor(ffmpeg_dir)
    def handler(database, task_id):
        with database.session() as session:
            task = session.get(BackgroundTask, task_id)
            if task.status != "running":
                return
            row = session.get(MediaCacheIndex, task.media_id)
            try:
                file, _info = open_media(session, row)
                with file:
                    result = subprocess.run([executor._ffprobe(), "-v", "error", "-select_streams", "v:0",
                        "-show_entries", "stream=width,height,codec_name:format=duration", "-of", "json", "pipe:0"],
                        stdin=file, capture_output=True, timeout=8, check=False,
                        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
                if result.returncode or len(result.stdout) > 65536:
                    raise ValueError("probe_failed")
                data = json.loads(result.stdout)
                session.refresh(task)
                session.refresh(row)
                if task.status != "running":
                    return
                if row.fingerprint != json.loads(task.params)["fingerprint"]:
                    task.status, task.finished_at = "cancelled", utc_now()
                    session.commit()
                    return
                seconds = float(data.get("format", {}).get("duration", "nan"))
                stream = next(iter(data.get("streams", [])), {})
                if math.isfinite(seconds) and 0 < seconds < 9_000_000_000:
                    row.duration_ms = int(seconds * 1000)
                for key in ("width", "height"):
                    value = stream.get(key)
                    if isinstance(value, int) and 0 < value <= 32768:
                        setattr(row, key, value)
                task.result = json.dumps({"codec": stream.get("codec_name"), "duration_known": row.duration_ms is not None})
                task.status, task.progress, task.finished_at = "succeeded", 100, utc_now()
            except Exception:
                task.status, task.error, task.finished_at = "failed", "local_probe_unavailable", utc_now()
            session.commit()
    return handler
