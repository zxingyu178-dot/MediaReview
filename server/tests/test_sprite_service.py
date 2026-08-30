"""阶段 4: 雪碧图规划、ffmpeg 命令、后台生成服务测试(使用替身 executor)。"""

from __future__ import annotations

import json
from pathlib import Path

from app.db.session import Database
from app.media.ffmpeg import build_ffmpeg_args, build_sprite_plan
from app.services import sprite


def test_build_sprite_plan_short_video_bumps_to_min_frames() -> None:
    plan = build_sprite_plan(3_000, min_frames=10, max_frames=60)
    assert plan.count == 10
    assert plan.rows == 1
    assert plan.columns == 10


def test_build_sprite_plan_long_video_adaptive_40_60() -> None:
    # 2 小时视频: 自适应帧数应落在 40~60,而非固定每 2s 一帧或高达 240
    plan = build_sprite_plan(7_200_000, min_frames=10, max_frames=60)
    assert 40 <= plan.count <= 60
    assert plan.rows == 6  # ceil(60/10)
    assert plan.total_duration_ms == 7_200_000


def test_build_sprite_plan_never_exceeds_max_frames() -> None:
    # 更长的视频也被 max_frames 拉回
    plan = build_sprite_plan(72_000_000, min_frames=10, max_frames=60)
    assert plan.count <= 60


def test_build_sprite_plan_interval_matches_duration() -> None:
    plan = build_sprite_plan(600_000, min_frames=10, max_frames=60)
    # 10min 视频帧数与总时长按整除间隔保持一致
    assert plan.interval_ms >= 1
    assert plan.total_duration_ms == 600_000


def test_build_sprite_plan_keeps_landscape_aspect_ratio() -> None:
    # 16:9 横屏 -> 保持 320×180
    plan = build_sprite_plan(100_000, video_width=1920, video_height=1080)
    assert plan.tile_width == 320
    assert plan.tile_height == 180


def test_build_sprite_plan_caps_sheet_dimension() -> None:
    """超大画布(多帧×大单格)被 max_sheet_dimension 按比例缩小,限制内存占用。"""
    plan = build_sprite_plan(7_200_000, min_frames=10, max_frames=60, max_sheet_dimension=2048)
    # 60 帧 10 列 320×180 原始画布 3200×1080 > 2048,应被缩小
    assert plan.width <= 2048 or plan.height <= 2048
    assert plan.tile_width < 320
    # 宽高比与数量/网格不受影响
    assert plan.rows == 6
    assert plan.count == 60
    assert abs(plan.tile_width / plan.tile_height - 320 / 180) < 0.01


def test_build_sprite_plan_sheet_within_default_cap_unchanged() -> None:
    # 默认上限 3200 与常规画布宽度一致(10 列×320),不触发缩放
    plan = build_sprite_plan(7_200_000, min_frames=10, max_frames=60)
    assert plan.tile_width == 320
    assert plan.rows == 6


def test_build_sprite_plan_keeps_portrait_aspect_ratio() -> None:
    # 9:16 竖屏 -> 不得拉伸成 320×180,宽高比须与视频一致
    plan = build_sprite_plan(100_000, video_width=720, video_height=1280)
    assert plan.tile_height == 180
    assert plan.tile_width < plan.tile_height
    # 比值接近 720/1280=0.5625
    assert abs(plan.tile_width / plan.tile_height - 720 / 1280) < 0.01


def test_build_sprite_plan_default_tile_when_unknown_size() -> None:
    # 无视频尺寸时回退默认 320×180
    plan = build_sprite_plan(100_000)
    assert (plan.tile_width, plan.tile_height) == (320, 180)


def test_build_ffmpeg_args() -> None:
    plan = build_sprite_plan(3_000, min_frames=10, max_frames=240)
    assert plan.rows == 1
    args = build_ffmpeg_args("D:\\Media\\clip.mp4", Path("out.jpg"), plan)
    assert args[:3] == ["-nostdin", "-y", "-i"]
    assert args[3].endswith("clip.mp4")
    assert "-filter_complex" in args
    filter_idx = args.index("-filter_complex")
    assert "tile=10x1" in args[filter_idx + 1]
    assert "-frames:v" in args
    assert args[-1] == "out.jpg"


class FakeExecutor:
    """媒体处理替身: 记录调用,固定时长,生成占位文件。"""

    def __init__(self, duration_ms: int = 100_000) -> None:
        self.duration_ms = duration_ms
        self.calls: list[str] = []

    async def probe_duration(self, media_path: str) -> int | None:
        self.calls.append(f"probe:{media_path}")
        return self.duration_ms

    async def make_sprite(self, media_path: str, out_file: Path, plan) -> None:
        self.calls.append(f"sprite:{media_path}->{out_file.name}")
        out_file.parent.mkdir(parents=True, exist_ok=True)
        out_file.write_bytes(b"JPEG")


def _make_db(data_root: Path) -> Database:
    db = Database(data_root / "database" / "mediareview.db")
    db.create_all()
    return db


def test_generate_sprite_creates_manifest_and_file(tmp_path: Path) -> None:
    from app.db.models import BackgroundTask, MediaCacheIndex

    db = _make_db(tmp_path)
    executor = FakeExecutor()
    sprites_dir = tmp_path / "sprites"
    media_id = "m" * 24

    with db.session() as s:
        task = BackgroundTask(
            task_id="t1",
            type=sprite.TASK_TYPE_SPRITE,
            status="pending",
            params=json.dumps({"media_id": media_id, "fingerprint": "fp-1"}),
            media_id=media_id,
        )
        s.add(
            MediaCacheIndex(
                media_id=media_id,
                jellyfin_id="j",
                library_id="l",
                name="clip.mp4",
                media_type="video",
                media_path="D:\\Media\\clip.mp4",
                fingerprint="fp-1",
            )
        )
        s.add(task)
        s.commit()
        task_id = task.task_id

    sprite._execute_sprite_generation(db, task_id, executor, sprites_dir)

    out = sprites_dir / f"{media_id}.jpg"
    assert out.is_file()
    with db.session() as s:
        task = s.get(BackgroundTask, task_id)
        assert task is not None and task.status == "succeeded" and task.progress == 100
        manifest = sprite.get_manifest(s, media_id)
        assert manifest is not None
        assert manifest.status == "ready"
        assert manifest.fingerprint == "fp-1"
    db.dispose()


def test_generate_sprite_fingerprint_mismatch_fails(tmp_path: Path) -> None:
    from app.db.models import BackgroundTask, MediaCacheIndex

    db = _make_db(tmp_path)
    executor = FakeExecutor()
    sprites_dir = tmp_path / "sprites"
    media_id = "n" * 24

    with db.session() as s:
        task = BackgroundTask(
            task_id="t2",
            type=sprite.TASK_TYPE_SPRITE,
            status="pending",
            params=json.dumps({"media_id": media_id, "fingerprint": "fp-old"}),
            media_id=media_id,
        )
        s.add(
            MediaCacheIndex(
                media_id=media_id,
                jellyfin_id="j",
                library_id="l",
                name="clip.mp4",
                media_type="video",
                media_path="D:\\Media\\clip.mp4",
                fingerprint="fp-new",
            )
        )
        s.add(task)
        s.commit()
        task_id = task.task_id

    sprite._execute_sprite_generation(db, task_id, executor, sprites_dir)

    with db.session() as s:
        task = s.get(BackgroundTask, task_id)
        assert task is not None and task.status == "failed"
        manifest = sprite.get_manifest(s, media_id)
        assert manifest is None
    assert not sprites_dir.exists() or not list(sprites_dir.glob("*"))
    db.dispose()


def test_schedule_sprite_is_idempotent(tmp_path: Path) -> None:
    db = _make_db(tmp_path)
    media_id = "i" * 24
    with db.session() as s:
        t1 = sprite.schedule_sprite(s, media_id, "fp-1")
        t2 = sprite.schedule_sprite(s, media_id, "fp-1")
        assert t1.task_id == t2.task_id
    db.dispose()


def test_invalidate_manifest_removes_file_and_row(tmp_path: Path) -> None:
    from app.db.models import SpriteManifest

    db = _make_db(tmp_path)
    media_id = "z" * 24
    sprites_dir = tmp_path / "sprites"
    sprites_dir.mkdir(exist_ok=True)
    (sprites_dir / f"{media_id}.jpg").write_bytes(b"JPEG")
    with db.session() as s:
        s.add(SpriteManifest(media_id=media_id, status="ready", fingerprint="f"))
        s.commit()

    with db.session() as s:
        removed = sprite.invalidate_manifest(s, media_id, sprites_dir)
        assert removed is True
        s.commit()
    assert not (sprites_dir / f"{media_id}.jpg").exists()
    with db.session() as s:
        assert sprite.get_manifest(s, media_id) is None
    db.dispose()


def test_generate_sprite_reports_progress_milestones(tmp_path: Path) -> None:
    """生成过程中 progress 按里程碑推进(20 探测前 -> 40 生成前 -> 100 完成)。"""
    from app.db.models import BackgroundTask, MediaCacheIndex

    db = _make_db(tmp_path)
    sprites_dir = tmp_path / "sprites"
    media_id = "p" * 24
    progress_at_generate: list[int] = []

    class ProgressExecutor(FakeExecutor):
        async def make_sprite(self, media_path: str, out_file: Path, plan) -> None:
            with db.session() as s:
                task = s.get(BackgroundTask, task_id)
                progress_at_generate.append(task.progress)
            await super().make_sprite(media_path, out_file, plan)

    executor = ProgressExecutor()
    with db.session() as s:
        task = BackgroundTask(
            task_id="t3",
            type=sprite.TASK_TYPE_SPRITE,
            status="running",
            params=json.dumps({"media_id": media_id, "fingerprint": "fp-1"}),
            media_id=media_id,
        )
        s.add(
            MediaCacheIndex(
                media_id=media_id,
                jellyfin_id="j",
                library_id="l",
                name="clip.mp4",
                media_type="video",
                media_path=r"D:\Media\clip.mp4",
                fingerprint="fp-1",
            )
        )
        s.add(task)
        s.commit()
        task_id = task.task_id

    sprite._execute_sprite_generation(db, task_id, executor, sprites_dir)

    assert progress_at_generate == [40], "进入生成步骤前必须已推进到 40"
    with db.session() as s:
        task = s.get(BackgroundTask, task_id)
        assert task.progress == 100 and task.status == "succeeded"
    db.dispose()


def test_generate_sprite_cancelled_during_generation_keeps_cancelled(tmp_path: Path) -> None:
    """生成期间被协作取消:任务保持 cancelled,产物被丢弃,不得复活为 ready。"""
    from app.db import models
    from app.services import tasks as task_service

    db = _make_db(tmp_path)
    sprites_dir = tmp_path / "sprites"
    media_id = "c" * 24

    class CancellingExecutor(FakeExecutor):
        async def make_sprite(self, media_path: str, out_file: Path, plan) -> None:
            out_file.parent.mkdir(parents=True, exist_ok=True)
            out_file.write_bytes(b"JPEG")
            with db.session() as s:
                task_service.cancel_task(s, s.get(models.BackgroundTask, task_id))
                s.commit()

    executor = CancellingExecutor()
    with db.session() as s:
        task = models.BackgroundTask(
            task_id="t4",
            type=sprite.TASK_TYPE_SPRITE,
            status="running",
            params=json.dumps({"media_id": media_id, "fingerprint": "fp-1"}),
            media_id=media_id,
        )
        s.add(
            models.MediaCacheIndex(
                media_id=media_id,
                jellyfin_id="j",
                library_id="l",
                name="clip.mp4",
                media_type="video",
                media_path=r"D:\Media\clip.mp4",
                fingerprint="fp-1",
            )
        )
        s.add(task)
        s.commit()
        task_id = task.task_id

    sprite._execute_sprite_generation(db, task_id, executor, sprites_dir)

    with db.session() as s:
        task = s.get(models.BackgroundTask, task_id)
        assert task.status == "cancelled"
        assert sprite.get_manifest(s, media_id) is None
    assert not (sprites_dir / f"{media_id}.jpg").exists()
    db.dispose()
