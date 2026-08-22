"""媒体处理执行器与雪碧图规划。

- SpritePlan / build_sprite_plan: 纯逻辑,给定视频时长计算裁剪网格与取样间隔
- build_ffmpeg_args: 拼出真实 ffmpeg tile 命令(便于单测,无需安装 ffmpeg)
- FfmpegExecutor: 系统 ffprobe/ffmpeg 封装;本机无 ffmpeg 时由测试注入假实现
"""

from __future__ import annotations

import asyncio
import dataclasses
import json
import math
import shutil
from pathlib import Path
from typing import Protocol

_MS_PER_S = 1000


@dataclasses.dataclass(frozen=True)
class SpritePlan:
    columns: int
    rows: int
    count: int
    tile_width: int
    tile_height: int
    interval_ms: int
    total_duration_ms: int

    @property
    def width(self) -> int:
        return self.columns * self.tile_width

    @property
    def height(self) -> int:
        return self.rows * self.tile_height


def _tile_size_for(
    tile_width: int,
    tile_height: int,
    video_width: int | None,
    video_height: int | None,
) -> tuple[int, int]:
    """按视频真实宽高比计算雪碧图单格尺寸,保持宽高比、不强制拉伸。

    - 无视频尺寸时回退默认 320×180(16:9)
    - 横屏(16:9 / 21:9):宽固定为默认宽,高按比例缩放
    - 竖屏(9:16 等):高固定为默认高,宽按比例缩放
    结果向下取整且最小不低于 48px,避免过窄导致看不清。
    """
    if not video_width or not video_height or video_width <= 0 or video_height <= 0:
        return tile_width, tile_height
    aspect = video_width / video_height  # >1 横屏,<1 竖屏
    if aspect >= 1.0:
        width = tile_width
        height = max(48, int(round(width / aspect)))
        return width, height
    height = tile_height
    width = max(48, int(round(height * aspect)))
    return width, height


def build_sprite_plan(
    duration_ms: int,
    *,
    columns: int = 10,
    min_frames: int = 10,
    max_frames: int = 60,
    tile_width: int = 320,
    tile_height: int = 180,
    video_width: int | None = None,
    video_height: int | None = None,
    max_sheet_dimension: int = 3200,
) -> SpritePlan:
    """根据时长自适应规划雪碧图网格与取样间隔(纯函数)。

    帧数按视频时长自适应(见 PRODUCT_SPEC/ARCHITECTURE),不再固定约每 2 秒抽帧:
    - <30s      10~12 帧
    - 30s~2min  16~20 帧
    - 2~10min   24~30 帧
    - 10~60min  30~40 帧
    - >60min    40~60 帧(长视频通常控制在该区间内,受 max_frames 约束)

    单格宽高按视频宽高比保持,竖屏/非 16:9 不被强拉成 320×180。
    整体画布(width=columns*tile_width, height=rows*tile_height)超
    max_sheet_dimension 时按比例缩小单格,防止生成超大位图占用内存。
    """
    duration_ms = max(0, int(duration_ms))
    if duration_ms <= 0:
        count = min_frames
    else:
        minutes = duration_ms / 60_000
        if duration_ms < 30_000:
            count = min(max_frames, max(min_frames, duration_ms // 3_000))
        elif duration_ms < 2 * 60_000:
            count = min(max_frames, max(16, duration_ms // 6_000))
        elif duration_ms < 10 * 60_000:
            count = min(max_frames, max(24, duration_ms // 12_000))
        elif duration_ms < 60 * 60_000:
            count = min(max_frames, max(30, duration_ms // 18_000))
        else:
            # >60min: 长视频目标 40~60 帧
            count = min(max_frames, max(40, int(duration_ms // (60_000 * (minutes // 50)))))
    count = max(min_frames, min(max_frames, count))
    tile_width, tile_height = _tile_size_for(tile_width, tile_height, video_width, video_height)
    rows = int(math.ceil(count / columns))
    # 画布超限时按比例缩小单格(下限 48px),限制服务端雪碧图整体最大尺寸
    sheet_w = columns * tile_width
    sheet_h = rows * tile_height
    if sheet_w > max_sheet_dimension or sheet_h > max_sheet_dimension:
        scale = min(max_sheet_dimension / sheet_w, max_sheet_dimension / sheet_h, 1.0)
        tile_width = max(48, int(tile_width * scale))
        tile_height = max(48, int(tile_height * scale))
    # 与 count 匹配的实际间隔:duration 跨 count 帧所需的最小整除间隔
    interval = max(1, duration_ms // count) if count and duration_ms else 1000
    return SpritePlan(
        columns=columns,
        rows=rows,
        count=count,
        tile_width=tile_width,
        tile_height=tile_height,
        interval_ms=interval,
        total_duration_ms=duration_ms,
    )


def build_ffmpeg_args(media_path: str, out_file: Path, plan: SpritePlan) -> list[str]:
    """生成 ffmpeg 雪碧图命令参数(不执行,便于单测校验)。"""
    fps = 1.0 / max(plan.interval_ms / _MS_PER_S, 0.0001)
    return [
        "-nostdin",
        "-y",
        "-i",
        media_path,
        "-filter_complex",
        (
            f"fps={fps:.4f},"
            f"scale={plan.tile_width}:{plan.tile_height},"
            f"tile={plan.columns}x{plan.rows}"
        ),
        "-frames:v",
        str(plan.count),
        "-an",
        str(out_file),
    ]


class MediaExecutor(Protocol):
    """媒体处理能力抽象,便于在测试/无 ffmpeg 环境注入替身。"""

    async def probe_duration(self, media_path: str) -> int | None:
        """返回媒体时长(毫秒),探测失败返回 None。"""

    async def make_sprite(self, media_path: str, out_file: Path, plan: SpritePlan) -> None:
        """生成雪碧图到 out_file。"""


def _resolve_binary(ffmpeg_dir: str, name: str) -> str:
    directory = Path(ffmpeg_dir) if ffmpeg_dir else None
    direct = directory / name if directory else None
    if direct and direct.is_file():
        return str(direct)
    found = shutil.which(name)
    if found:
        return found
    raise FileNotFoundError(f"未找到 {name},请配置 storage.ffmpeg_dir")


class FfmpegExecutor:
    """基于系统 ffprobe/ffmpeg 的实现。"""

    def __init__(self, ffmpeg_dir: str = "") -> None:
        self.ffmpeg_dir = ffmpeg_dir

    def _ffprobe(self) -> str:
        return _resolve_binary(self.ffmpeg_dir, "ffprobe")

    def _ffmpeg(self) -> str:
        return _resolve_binary(self.ffmpeg_dir, "ffmpeg")

    async def probe_duration(self, media_path: str) -> int | None:
        cmd = [
            self._ffprobe(),
            "-v",
            "error",
            "-print_format",
            "json",
            "-show_entries",
            "format=duration",
            media_path,
        ]
        proc = await asyncio.create_subprocess_exec(
            *cmd, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE
        )
        stdout, _ = await proc.communicate()
        if proc.returncode != 0:
            return None
        try:
            payload = json.loads(stdout.decode(errors="ignore"))
            seconds = payload.get("format", {}).get("duration", "0")
            seconds = float(seconds)
        except (ValueError, TypeError, json.JSONDecodeError, AttributeError):
            return None
        return int(seconds * _MS_PER_S)

    async def make_sprite(self, media_path: str, out_file: Path, plan: SpritePlan) -> None:
        out_file.parent.mkdir(parents=True, exist_ok=True)
        cmd = [self._ffmpeg(), *build_ffmpeg_args(media_path, out_file, plan)]
        proc = await asyncio.create_subprocess_exec(
            *cmd, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE
        )
        _, stderr = await proc.communicate()
        if proc.returncode != 0:
            raise RuntimeError(stderr.decode(errors="ignore").strip() or "ffmpeg 执行失败")


__all__ = [
    "FfmpegExecutor",
    "MediaExecutor",
    "SpritePlan",
    "build_ffmpeg_args",
    "build_sprite_plan",
]
