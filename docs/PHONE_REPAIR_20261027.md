# Alpha18实机排障 / Server1.6.3修复与部署

## 已确认

- USB真机安装Alpha18。初查SCM仍Running，但8766无监听，ControlHub offline；21:03日志有15连接的QueuePool耗尽及37–42秒图片失败。端口丢失的具体触发尚未确认，不能仅凭连接池日志断言全部因果。
- 官方ControlHub stop把健康offline当成already_in_target_state，未实际停服，start随后失败。用户明确批准本次Windows服务停启例外；核对服务路径、安装EXE哈希后只重启AIHome.MediaReview，恢复1.6.2/Contract2/windows_service/数据库ok。手机重新检测后在线，配对未清除。
- 三个失败图片样本和一个视频样本：Jellyfin项目元数据200，原文件Download均404；Windows路径stat为winerror3、盘符存在。视频Primary封面200。这解释部分“可预览但打不开”，不是已证实的解码错误。
- Jellyfin当前24库根均存在，最近扫描已Completed；多数新媒体样本文件存在但ID未进入MediaReview缓存，缓存上次成功刷新仍10月22日。26个选择中2个库已不存在。正常API保留24个有效库、取消2个失效选择（原选择本地留档），不删任何媒体/用户记录。
- 首次整批刷新失败，旧索引按既有安全语义保留。通过现有API逐库刷新后，24个当前选中库全部succeeded，合计processed=56553。只读逐层枚举也观察到Jellyfin请求异常，目前整批失败的原始异常被旧实现统一脱敏，具体原因不能认定。取消失效选择及刷新只影响索引，不删除文件。
- 手机重新检测后显示服务器在线；Alpha18在恢复索引后的1.6.2上，媒体墙缩略图、一个视频实际画面及一个图片查看页均已目视确认。升级1.6.3后重新检测显示在线和1.6.3，同一视频再次出帧，服务日志播放信息72ms（凭据52ms/元数据16ms）；不是首帧耗时统计。样本通过不等于全部文件夹验收通过。
- 1.6.3上的手机“浏览文件夹”首屏10个可见目录显示101、1866、196、58、10、159、83、14、99、55项，均有1个子目录，不再是整屏0项。仅验证当前可见目录，未逐项核对24库全部文件。

## 最小代码修复

认证在心跳未到一分钟时仍保持SELECT事务；图片/播放信息端点在慢IO前也保持事务。已通过失败用例确认连接未归还。现在认证完成以及缩略图、原图、播放Key请求等待外部IO前提交短事务，使用现有expire_on_commit=False保留已读取字段；不扩大连接池、不改鉴权规则、不改删除逻辑、不迁移数据库。

Server1.6.3，Contract2/0017/14能力保持；Android仍Alpha18，不构建新APK。用户明确授权后，经ControlHub停服、受保护完整备份、逐文件哈希校验、切换程序并启动，22:19实际health确认1.6.3/windows_service/数据库ok。保留旧程序与完整备份，不处理待删除队列。CURRENT_RELEASE已切至current中的1.6.3正式包，旧1.6.2仅供回滚。

## 验证

- 缩略图/原图/播放上游等待连接占用测试：3项先失败，修改后3项通过。
- `pytest server/tests/test_auth_concurrency.py server/tests/test_media_image_proxy_11.py server/tests/test_playback_contract_11.py server/tests/test_native_images.py -k 'not migration and not disposable_clip' -ra`：36通过、2未选，777.10秒。此轮先验证media修复。
- recent_auth最小用例先失败；补认证事务释放后：`pytest server/tests/test_auth_concurrency.py server/tests/test_media_image_proxy_11.py -k 'auth or upstream_wait' -ra`：11通过、12未选，139.02秒。与前轮有重叠，不汇总为47个独立测试。
- 首次3项被本机SQLite临时目录权限阻断（非有效RED）；仅对子进程设置既有项目TEMP/TMP后取得有效断言失败，再修复。没有改系统权限/环境。
- 增量PyInstaller构建成功；实际EXE health为1.6.3，发布包182项SHA256通过，复用Alpha18并核验版本/签名/同Alpha17证书。ZIP 134334230字节，SHA256 `4c70f794837b36f6d8b4f382d90fa131e722cc5b586942a6fb368277a91db787`；构建源码d87b69a已push。
- 不重跑全量Server/JVM/connected，不重建Alpha18。手机上的实际视频画面和图片查看已确认；全部目录逐个验收未执行。升级后曾遇搜索失败、手动重新检测恢复在线，客户端断线后自动恢复仍有体验缺口；日志中媒体列表/文件夹查询约2.7–4.9秒，不能声称加载延迟已全部解决。

证据在忽略的review_meta/ops-20261027：phone-scm-recovery.json、index-recovery-receipt.json、per-library-recovery-receipt.json、pytest-pool-red2.log、pytest-pool-green.log、pytest-phone-focused.log、pytest-auth-red.log、pytest-auth-pool-final.log。独立诊断配对已逐次撤销，未改手机原配对，不输出凭据和完整媒体路径。
