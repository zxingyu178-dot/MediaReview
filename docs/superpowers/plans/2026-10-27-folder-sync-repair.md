# 文件夹漏项修复与模拟器验证计划

> 使用 executing-plans 串行执行；用户已批准最小方案，不新增并行重型任务。

**目标：** 修复Jellyfin库级递归漏项，保留同用户权限、稳定媒体ID和成功后才失效的同步事务。

**架构：** JellyfinClient新增分页的直接子级遍历迭代器；后台同步消费流式页，取消/失败不完成失效。Android Alpha17不改代码，使用现有AVD验证。生产实例不升级、不刷新，不修改真实媒体。

**技术栈：** 现有Python/httpx/SQLite/pytest、Android AVD/ADB/uiautomator2。

- [x] 客户端回归：`server/tests/test_jellyfin_client.py` 验证递归0与非零漏项、分页、子目录/环/重复ID、原库归属、401/404/截断页拒绝。先运行新增用例确认失败。
- [x] 仅改 `server/app/adapters/jellyfin/client.py`、`models.py`：直接子级参数与有界遍历；所有请求仍含原用户，库根需属于该用户可见Views；分页长度/总数变化拒绝，不调用无用户管理员列表。
- [x] `server/app/services/media_index.py`消费迭代器，每页提交已有代次；完整枚举后沿用原CAS完成逻辑。调整 `server/tests/test_media_index_11.py` 现有替身接口并复用取消/失败/跨库回归，补真实客户端集成例。
- [x] 只运行客户端、索引、目录与API相关定向测试及改动文件ruff；不跑全套Server/Android/connected，不重建无改动APK。
- [x] 修复客户端对四个真实上游样本仅做元数据计数对照，不写生产数据库。使用隔离数据目录/可丢弃样本运行临时测试Server；端口先查登记及监听，用例结束停掉自有进程。
- [x] 现有MediaReview_Test AVD安装既有Alpha17（不清数据/不卸载），手动地址连接隔离Server，配对、选库/目录/下级目录/媒体列表定向验证并截取无敏感内容证据。模拟器网络与播放证据单列，不能代替手机Wi-Fi/硬解验收。
- [x] 更新PROJECT_STATUS、DEV_LOG及结果并commit；push以本轮最终Git结果为准。生产部署保留为用户下一步确认，不创建新长期运行对象。
