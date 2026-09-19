# Demo 媒体素材来源记录（MediaReview 2.0 Stage 1）

> 本目录下的全部测试媒体均由 **FFmpeg 内置公开测试源（lavfi）** 生成，非商业影视内容，可公开用于测试与演示。生成脚本见项目构建期临时目录（`D:\AIHome_2.0_L1_L2\temp\mediareview-v2\gen_demo_media.ps1`），FFmpeg 版本为项目自带 `third_party/ffmpeg`。

## 视频（6 个，共约 18.8 MB）

| 文件 | 画面源 | 原始 URL | 许可证 | 是否裁剪 |
|---|---|---|---|---|
| `01_landscape.mp4`（1280x720 / 24s / 30fps） | FFmpeg `testsrc2` 动态测试图案（自带时间码）+ `sine` 440Hz | FFmpeg lavfi 内置源（无外部 URL） | FFmpeg 公开测试源，可自由用于测试 | 生成时直接编码；为控制体积二次转码（crf 30） |
| `02_landscape.mp4`（1280x720 / 20s / 25fps） | FFmpeg `mandelbrot` 分形动画 + `sine` 330Hz | FFmpeg lavfi 内置源 | 同上 | 同上（crf 32） |
| `03_portrait.mp4`（720x1280 / 18s / 30fps） | FFmpeg `smptebars` SMPTE 彩条 + `sine` 523Hz | FFmpeg lavfi 内置源 | 同上 | 生成时直接编码 |
| `04_short.mp4`（1280x720 / 10s / 30fps） | FFmpeg `testsrc` 彩条 + `sine` 660Hz | FFmpeg lavfi 内置源 | 同上 | 生成时直接编码 |
| `05_longer.mp4`（1280x720 / 40s / 30fps） | FFmpeg `gradients` 渐变动画 + `sine` 262Hz | FFmpeg lavfi 内置源 | 同上 | 生成时直接编码 |
| `06_wide.mp4`（1024x768 / 22s / 30fps） | FFmpeg `testsrc2` 动态测试图案 + `sine` 392Hz | FFmpeg lavfi 内置源 | 同上 | 生成后二次转码（crf 30） |

编码规格：H.264（libx264，preset medium）+ AAC（96k），全部带音轨，`faststart`。

## 图片（14 张，共约 3.0 MB）

| 文件 | 画面源 | 原始 URL | 许可证 | 是否裁剪 |
|---|---|---|---|---|
| `img_landscape_01~04.jpg`（1920x1080 横图） | testsrc2 / gradients / smptebars / mandelbrot 单帧 | FFmpeg lavfi 内置源 | 同上 | 生成 |
| `img_portrait_01~02.jpg`（1080x1920 竖图） | smptebars / gradients 单帧 | FFmpeg lavfi 内置源 | 同上 | 生成 |
| `img_square_01~03.jpg`（1080x1080 方图） | mandelbrot / testsrc2 / 纯色 单帧 | FFmpeg lavfi 内置源 | 同上 | 生成 |
| `img_hd_01~02.jpg`（2560x1440 高清横图） | gradients / testsrc2 单帧 | FFmpeg lavfi 内置源 | 同上 | 生成 |
| `img_art_01~03.jpg`（1280x720 图案） | cellauto / life / sierpinski 单帧 | FFmpeg lavfi 内置源 | 同上 | 生成 |

编码规格：JPEG（quality 85，`-q:v 2`）。

## 雪碧图（6 个，共约 65 KB）

| 文件 | 内容 |
|---|---|
| `0X_*_sprite.webp` | 对应视频每 2 秒一帧、160x90 缩略图、`tile=5x5` 拼成的 WebP 雪碧图 |
| `0X_*_sprite.json` | manifest：列/行/单元格尺寸/帧间隔/时长/帧数 |

## 说明

- 素材全部由 FFmpeg 内置测试源在**本地**生成，无外部下载，无版权与隐私风险。
- 名称编号 `01~06`、`img_*` 为内部测试命名，非真实媒体。
- 生成脚本与 FFmpeg 二进制：`third_party/ffmpeg/`（随项目源码仓库）。
