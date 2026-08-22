# MediaReview Server 部署包

本目录由 `scripts/build_deploy.py` 生成 `deploy_handoff/MediaReviewServer-<version>_deploy_*.zip`。

## 包内结构

```
MediaReviewServer-<version>/
├── server/
│   └── MediaReviewServer/     # PyInstaller 打包的 Server(Mediaserver.exe + _internal)
├── ffmpeg/                    # FFmpeg(可选,由 third_party/ffmpeg 提供)
├── scripts/
│   ├── install.ps1            # 一键安装(管理员)
│   ├── repair.ps1             # 修复/重建服务(不删数据)
│   ├── uninstall.ps1          # 卸载(默认保留数据,-DeleteData 才删)
│   └── diagnose.ps1           # 导出诊断 ZIP
├── config.example.json        # 配置模板
├── HANDOVER.md                # 交接说明
└── README.md                  # 本文档
```

## 使用

1. 解压到目标电脑(局域网内,装有 Jellyfin 的机器)。
2. 管理员运行 `scripts/install.ps1`。
3. 若需要修改端口/数据目录:`.\scripts\install.ps1 -Port 9000 -DataRoot D:\MediaReviewData`。
4. 手机安装 APK,自动发现并配对。

## 构建

开发机执行:

```bash
python scripts/build_deploy.py
```

- 自动调用 PyInstaller 构建 Server EXE。
- 若存在 `third_party/ffmpeg/ffmpeg.exe`(与 ffprobe.exe)会一并打包;
  否则部署时由 `install.ps1` 检测系统 PATH 中的 ffmpeg,缺失会告警(雪碧图不可用)。
