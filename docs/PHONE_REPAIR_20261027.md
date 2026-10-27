# Alpha18实机排障 / Server1.6.3候选修复

## 已确认

- USB真机安装Alpha18。初查SCM仍Running，但8766无监听，ControlHub offline；21:03日志有15连接的QueuePool耗尽及37–42秒图片失败。端口丢失的具体触发尚未确认，不能仅凭连接池日志断言全部因果。
- 官方ControlHub stop把健康offline当成already_in_target_state，未实际停服，start随后失败。用户明确批准本次Windows服务停启例外；核对服务路径、安装EXE哈希后只重启AIHome.MediaReview，恢复1.6.2/Contract2/windows_service/数据库ok。手机重新检测后在线，配对未清除。
- 三个失败图片样本和一个视频样本：Jellyfin项目元数据200，原文件Download均404；Windows路径stat为winerror3、盘符存在。视频Primary封面200。这解释部分“可预览但打不开”，不是已证实的解码错误。
- Jellyfin当前24库根均存在，最近扫描已Completed；多数新媒体样本文件存在但ID未进入MediaReview缓存，缓存上次成功刷新仍10月22日。26个选择中2个库已不存在。正常API保留24个有效库、取消2个失效选择（原选择本地留档），不删任何媒体/用户记录。
- 首次整批刷新失败，旧索引按既有安全语义保留。逐库刷新已排队，独立成功的库先替换索引；尚未全部完成。只读逐层枚举也观察到Jellyfin请求异常，目前整批失败的原始异常被旧实现统一脱敏，具体原因不能认定。

## 最小代码修复

认证在心跳未到一分钟时仍保持SELECT事务；图片/播放信息端点在慢IO前也保持事务。已通过失败用例确认连接未归还。现在认证完成以及缩略图、原图、播放Key请求等待外部IO前提交短事务，使用现有expire_on_commit=False保留已读取字段；不扩大连接池、不改鉴权规则、不改删除逻辑、不迁移数据库。

目标Server1.6.3，Contract2/0017/14能力保持；Android仍Alpha18，不构建新APK。四处版本声明与现用部署指南同步候选版本。生产仍1.6.2，候选部署必须另获授权，CURRENT_RELEASE暂仍指已部署1.6.2。

## 验证

- 缩略图/原图/播放上游等待连接占用测试：3项先失败，修改后3项通过。
- `pytest server/tests/test_auth_concurrency.py server/tests/test_media_image_proxy_11.py server/tests/test_playback_contract_11.py server/tests/test_native_images.py -k 'not migration and not disposable_clip' -ra`：36通过、2未选，777.10秒。此轮先验证media修复。
- recent_auth最小用例先失败；补认证事务释放后：`pytest server/tests/test_auth_concurrency.py server/tests/test_media_image_proxy_11.py -k 'auth or upstream_wait' -ra`：11通过、12未选，139.02秒。与前轮有重叠，不汇总为47个独立测试。
- 首次3项被本机SQLite临时目录权限阻断（非有效RED）；仅对子进程设置既有项目TEMP/TMP后取得有效断言失败，再修复。没有改系统权限/环境。
- 不重跑全量Server/JVM/connected，不重建Alpha18。只做相关pytest、候选EXE构建和实际健康检查；当前真实手机播放/原图及完整目录恢复尚待继续验证。

证据在忽略的review_meta/ops-20261027：phone-scm-recovery.json、index-recovery-receipt.json、per-library-recovery-receipt.json、pytest-pool-red2.log、pytest-pool-green.log、pytest-phone-focused.log、pytest-auth-red.log、pytest-auth-pool-final.log。独立诊断配对已逐次撤销，未改手机原配对，不输出凭据和完整媒体路径。
