# MediaReview Server 交接说明

## 版本

- Server：
- Android：
- 构建日期：
- Git commit：

## 部署

1. 解压 ZIP。
2. 以管理员权限运行 `scripts/install.ps1`。
3. 按提示确认 Jellyfin。
4. 浏览器打开管理后台。
5. 手机安装 APK。
6. 自动发现电脑。
7. 输入配对码。
8. 完成。

## 数据位置

默认：

`%ProgramData%\MediaReview`

## 备份

至少备份：

- config
- database

cache 可以重建，不属于必须备份数据。

## 故障

先运行：

`scripts/diagnose.ps1`

再检查：

- Jellyfin
- MediaReview 服务
- 8766
- 防火墙
- 日志
