# MediaReview 当前安装入口

唯一当前配套：**Server1.6.3 / API Contract2 / 数据库0017 + Android Alpha19 / code26**。

## 防止装错版本

1. 从项目 `deploy_handoff/CURRENT_RELEASE.json` 读取当前包路径、版本与SHA256；不要按目录排序或文件日期选择包。
2. Server包必须包含 `RELEASE_MANIFEST.json` 和 `SHA256SUMS.txt`，Server为1.6.3；逐文件校验不通过就停止。该既有包内附Alpha18，手机应另装CURRENT_RELEASE指向的独立Alpha19/code26 APK，核对其独立SHA256，不要从Server包回装旧APK。本轮未重建Server。
3. 安装前核对当前服务真实程序路径、已装版本与端口归属；同一时间仅允许既有Windows服务实例。旧包、`.old-*`和备份只用于有明确决定的回滚，不作为启动入口。
4. 运行目标固定为ControlHub已有的`mediareview`接入和`AIHome.MediaReview`服务。不要双击EXE或旧`start_server.cmd`制造交互式实例。

## 电脑端升级

生产升级需要本次明确授权及Windows管理员权限。经ControlHub停服，确认服务停止且端口释放；完整备份有效程序与全部活动数据（历史备份不重复套娃复制），校验副本，随后替换程序并逐文件核对。配置、配对和现有库选择保留，0017迁移由新程序处理，不手动编辑数据库。

经ControlHub启动后必须同时满足：health版本1.6.3、Contract2、14项能力、database=ok、instance_mode=windows_service、lifecycle_target=AIHome.MediaReview，安装EXE哈希与manifest一致。仍显示1.5.2或其他版本就不算部署完成；不能只看HTTP200。

## 手机端

安装Alpha19前核对APK哈希。它与已交付Alpha18使用同一发布证书，正常覆盖升级，无需卸载或清数据。真实手机上的既有包签名若不匹配，先停止，不自行卸载。

回家后手机与电脑使用同一Wi-Fi，保留已有配对；若需重新配对，从电脑管理页取得配对码。按 `USER_LAN_VALIDATION.md` 进行短验证，真实媒体删除不属于这次加载验证。

当前运行版本和部署/邮件结果以 `docs/PROJECT_STATUS.md` 最新记录为准；旧版本完整文档已保存在 `docs/history/20261027-before-release-closure/`，不可照旧文档执行部署。
