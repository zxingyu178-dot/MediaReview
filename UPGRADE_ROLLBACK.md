# MediaReview 升级与回滚说明（1.1.0）

本说明覆盖 `MediaReview_Migration_1.1.0` 部署包的事务式升级与回滚流程。升级由 `scripts/install.ps1` 自动完成，失败时自动回滚，正常情况下无需人工干预。

## 事务式升级流程

再次运行 `scripts/install.ps1`（或新版部署包）时，脚本检测到旧版本（存在 `VERSION.txt` / `CURRENT_VERSION` / 已安装程序）即进入升级路径：

1. **停止旧服务**：按 `MediaReviewServer.exe` 绝对路径精确停止本服务进程，并停止同名计划任务；不会误杀无关进程。
2. **备份**：将 `config/`、`database/` 与旧二进制复制到 `%ProgramData%\MediaReview\backup\<时间戳>\`。
3. **staging**：新程序复制到安装目录下的 `.new`，不与运行中文件冲突。
4. **迁移 + 健康检查**：以新程序 + 同一数据根启动，等待数据库自动迁移；轮询 `http://127.0.0.1:<port>/api/v1/system/health` 直至 `status=ok`。
5. **原子提升**：健康检查通过后，用新程序替换旧程序目录，写入 `VERSION.txt` 与 `CURRENT_VERSION`，清理 staging。
6. **重启**：通过计划任务拉起新版本并再次健康检查。

## 失败自动回滚

若第 4 步健康检查未通过（超时或状态异常），脚本自动执行 `Restore-Previous`：

1. 停止新 staging 进程与计划任务。
2. 删除已替换的新程序目录。
3. 从备份目录恢复旧二进制、`config/`、`database/`。
4. 重新启动旧版本并写回版本标记。

升级失败时旧数据与旧版本保持不变，部署报告记录 `restore: restored old version from <backup>`。

## 手动回滚（紧急情况）

如果升级后运行异常，可手动回滚到备份：

1. 停止服务：`scripts\stop.ps1`
2. 恢复二进制：将 `%ProgramData%\MediaReview\backup\<时间戳>\binaries\*` 覆盖回安装目录。
3. 恢复数据：将备份中的 `config/`、`database/` 覆盖回 `%ProgramData%\MediaReview\`。
4. 重启：`scripts\start.ps1`
5. 用 `scripts\status.ps1` 验证健康状态与版本。

## 备份位置

- 自动备份：`%ProgramData%\MediaReview\backup\<时间戳>\{binaries,config,database}`
- 手工备份建议：完整复制 `config` 与 `database` 两个目录到安全位置。

## 验证

- `scripts\status.ps1` — 显示进程、端口、健康状态与版本。
- `%ProgramData%\MediaReview\deploy_report.txt` — 最近一次安装/升级的步骤报告。
- `%ProgramData%\MediaReview\config\CURRENT_VERSION` — 当前运行版本。
