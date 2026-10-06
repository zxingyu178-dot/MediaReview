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

## 手机播放地址与局域网检查

- `jellyfin.url` 是电脑端 Server 访问 Jellyfin 的地址，例如 `http://127.0.0.1:8096`。
- `jellyfin.client_url` 是手机直连 Jellyfin 的地址，例如 `http://192.168.1.20:8096`；部署时替换为实际 LAN 地址，保留反向代理的路径前缀。
- `client_url` 留空时，Server 使用本次手机请求的 host，搭配 `url` 的协议、端口与路径推导地址（`request_host_fallback`，非权威地址）。经过代理时建议显式配置 `client_url`。
- `client_host_allowlist` 默认为空，仅为已确认手机可访问的内部主机名提供明确例外；不要用它允许 localhost。
- 手机设置 → 数据源 → 我的服务器 → **检查手机播放链路** 可查看最终 Jellyfin host/port、地址来源、电脑端与手机端 Range 结果。诊断不展示 Token。

部署前分别检查 MediaReview TCP 8766 与 `client_url` 对应 Jellyfin 端口。电脑监听正常不能证明手机可访问；必须从手机所在 Wi-Fi 做检测。HTTP 401/403 表示认证拒绝，404 表示播放资源不存在，5xx 表示 Jellyfin 错误；无法连接需检查 LAN 地址、Wi-Fi 隔离与防火墙规则。本阶段不自动修改防火墙或 Jellyfin 配置，也不覆盖生产部署。

例如（非真实设备配置）：

```json
{"jellyfin":{"url":"http://127.0.0.1:8096","client_url":"http://192.168.1.20:8096","client_host_allowlist":[],"api_key":"__SET_DURING_SETUP__"}}
```

手机视频始终直接访问 Jellyfin，MediaReview Server 不中转视频。

## 构建命令

Stage 8E RC：构建器无条件清理指定的可再生目录、PyInstaller --clean、Gradle clean assembleRelease。
实际EXE隔离health和APK版本/签名验证后才记录本轮证据。默认不允许旧EXE、旧APK或Debug回退。
可先用 `--prepare-only` 构建验证，提交最终文档后用 `--package-verified` 重验同一轮源码/产物哈希与签名。
该入口要求Git clean，构建证据超过6小时或源码/产物发生变化即拒绝打包。
RC目录采用MediaReviewServer/，安装器兼容历史server/MediaReviewServer/目录。
发现既有AIHome.MediaReview服务时禁止创建平行任务，按USER_INSTALL_GUIDE.md执行受控升级。

开发机执行:

```bash
python scripts/build_deploy.py
```

- 自动调用 PyInstaller 构建 Server EXE。
- 若存在 `third_party/ffmpeg/ffmpeg.exe`(与 ffprobe.exe)会一并打包;
  否则部署时由 `install.ps1` 检测系统 PATH 中的 ffmpeg,缺失会告警(雪碧图不可用)。
