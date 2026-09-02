# THIRD-PARTY NOTICES

MediaReview 1.1.0 直接分发或静态链接的第三方组件及其许可声明。
完整清单以各发行包内随附的许可证文本为准。

## Server (FastAPI 中间层)

| 组件 | 版本 | 许可证 |
| --- | --- | --- |
| FastAPI | >=0.115 | MIT |
| Starlette | 随 FastAPI | BSD-3-Clause |
| Uvicorn | >=0.30 | BSD-3-Clause |
| SQLAlchemy | >=2.0 | MIT |
| Alembic | >=1.13 | MIT |
| Pydantic | >=2.9 | MIT |
| pydantic-core | 随 Pydantic | MIT |
| httpx | >=0.27 | BSD-3-Clause |
| httptools / h11 / websockets | 随 Uvicorn | MIT |
| PyInstaller(仅打包工具,不随包分发) | - | GPL-2.0-or-later |

## 多媒体与工具

| 组件 | 许可证 |
| --- | --- |
| FFmpeg / FFprobe | LGPL-2.1-or-later(随包分发时附对应 GPL/LGPL 许可证文本) |
| Jellyfin(外部服务,仅 API 对接) | GPL-2.0-or-later(不随包分发) |

### FFmpeg 二进制来源记录(1.1.0-rc1)

- 版本: 7.1.3-Jellyfin(ffmpeg version 7.1.3-Jellyfin, (c) 2000-2025 FFmpeg developers)
- 来源: Jellyfin Server 发行附带的静态构建(LGPL-2.1-or-later)
- 复制于: `D:\Program Files\Jellyfin\Server\ffmpeg.exe` / `ffprobe.exe`
- SHA-256:
  - `ffmpeg.exe`: `839138090AFCAC63735F3062D19FB72CFB442810AAD125B374E808140F9063F7`
  - `ffprobe.exe`: `87191811BE1BF0AD488B6B188AA7DAB2B4D113FE37DF4A229EDDB0D09DB18D2E`

## Android App

| 组件 | 许可证 |
| --- | --- |
| AndroidX / Jetpack Compose | Apache-2.0 |
| Hilt(Dagger) | Apache-2.0 |
| Kotlin / kotlinx.coroutines / kotlinx.serialization | Apache-2.0 |
| Retrofit / OkHttp | Apache-2.0 |
| Coil | Apache-2.0 |
| Media3 (ExoPlayer) | Apache-2.0 |
| Paging 3 | Apache-2.0 |

## 声明

- 本软件按项目根目录 `LICENSE`(MIT)授权;此处列出的第三方组件仍受各自许可证约束。
- FFmpeg 二进制若随部署包分发,其随附许可证文本必须一并保留在发行目录内。
- 禁止在本项目中包含任何带密钥/Token 的凭据;配置仅以脱敏模板形式分发。
