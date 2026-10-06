# Stage 8E 生产部署前检查

2026-10-06，只读观察；详细脱敏快照保存在阶段交接logs/production-preflight.json。

| 项目 | 实际结果 |
|---|---|
| 已运行Server公开health | status=ok / version=1.1.0 / 无api_contract与capabilities |
| 监听 | 0.0.0.0:8766；8096未监听 |
| 受管对象 | AIHome.MediaReview Windows服务Running；JellyfinServer Stopped |
| 数据根 | C:\ProgramData\MediaReview |
| 当前配置 | config/config.json存在；Jellyfin凭据已配置，仅记录存在性 |
| 数据库 | database/mediareview.db及WAL/SHM存在；未读取媒体行或设备Token |
| 服务定义 | service/main/WinSW.exe与WinSW.xml存在 |
| Jellyfin Server URL | http://127.0.0.1:8096 |
| 已配置client_url | http://192.168.31.107:8096 |
| 网络证据 | 在线物理WLAN 192.168.31.107，网关192.168.31.1；以太网断开，WSL无默认网关 |
| Jellyfin可达/认证 | 不可达；认证NOT TESTED，不能判定凭据失效 |

用户授权启动已安装Jellyfin。ControlHub官方ServiceHelper对JellyfinServer返回
scm_unavailable；原生Start-Service也返回CouldNotStartService，服务仍停止。
未绕过服务身份启动第二个生产进程，未修改服务权限、系统环境或防火墙。

生产部署：NOT PERFORMED。备份：NOT PERFORMED，因为没有开始覆盖；未生成伪造的backup_manifest。
升级前必须在服务停止、数据库不再写入后备份config/database/旧二进制/服务定义，
生成文件SHA256与旧版本的backup_manifest，再按USER_INSTALL_GUIDE.md受控切换。
当前安装脚本发现既有WinSW服务或服务查询失败会在写文件前拒绝，防止平行计划任务。

地址配置已有合理证据，不代表手机LAN可达。用户当前不在家，Real User LAN Playback: NOT TESTED。
真实媒体永久删除：NOT PERFORMED。发布包只能READY_FOR_USER_VALIDATION。
