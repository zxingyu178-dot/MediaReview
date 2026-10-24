# Stage 9 交付结果（2026-10-18）— 历史快照

> **后续状态**：本文件记录 9A–9D（Alpha14/1.5.0/1.5.1）。之后的 0016 多库归属修复、Server 1.5.2 / Alpha15 发版与生产升级见 `docs/STAGE9_REPAIR_RESULT.md`、`docs/DEV_LOG.md`；当前状态见 [docs/PROJECT_STATUS.md](docs/PROJECT_STATUS.md)。

状态：**DEPLOYED / LIMITED_PHONE_VALIDATION**。用户分别授权1.5.0及1.5.1备份升级；1.5.1已通过ControlHub停服、备份、升级及启动。Alpha14/code21无需重装。真实手机经现有局域网链路验证了两段Jellyfin视频解码出帧；未移动、修改或删除真实媒体。**仅限两个样本，不声称 PRODUCTION_READY / 全库或全部编码 REAL_LAN_PASS。**

分支：`codex/mediareview-stage9-native-source`，从远端核对后的 `cbf56ba` 开发。9A `f4ae162` → 9B `62f5abf` → 9C `ddd6b00` → 9D `838941d`，收口代码为 **`f1a4ac85c8f95e7bc9a84e615242a3567e363fea`**，均已 push。APK构建于 `838941d`，后续仅改Server，已核对Android输入未变化。

已完成：本机后台登记多根、单队列分批递归/增量索引、离线与取消保留状态；0015增量迁移保持旧Jellyfin主键/收藏；本地图片原图/EXIF缩略图/视频封面；认证Range/HEAD/条件请求、最多4视频连接及64KiB缓冲、本地播放进度；Android沿用现有媒体墙/播放器/批阅，加入来源筛选和只读标识，按钮/VM/仓库及Server最终删除均阻断本地来源。旧Jellyfin直连、设备凭据、HLS和删除安全合同保留。

关键落点：`server/app/adapters/media_source.py`（中立DTO/能力策略，避免引入复杂继承层）；`db/migrations/versions/0015_native_sources.py`；`services/native_sources.py`、`native_images.py`、`native_playback.py`、`native_metadata.py`；`api/v1/{sources,media}.py`；Android `V2MediaMapper/V2PlaybackResolver/V2ServerMediaRepository` 及既有Home/Viewer/Full/Review入口。公共队列、缓存和SQLite均复用。

实际验证：Server最终定向轮 **128通过/1部署版本声明失配**，同步后原失败项与受影响目标3通过；迁移/取消补验2通过、图片收口5通过。JVM最后四个相关suite **57通过**。包内真实HTTP发现登记成功早于事务提交，独立连接回归先失败；成功前显式commit后3项通过，最终EXE立即查询任务、图片原图/封面、视频封面/Range/HEAD及进度回读均通过。不是单轮全量全绿。

执行命令与结果（完整长参数见 `docs/DEV_LOG.md` 本轮命令段）：

```text
python -m pytest tests/test_native_playback.py tests/test_playback_contract_11.py -q -o addopts= --junitxml=../review_meta/stage9/9c-targeted.xml  # 14通过
gradlew.bat :app:testDebugUnitTest --tests <4个相关suite> --max-workers=1 --console=plain  # 57通过
gradlew.bat :app:assembleRelease --max-workers=1 --offline --console=plain  # 一次成功，4m38s
python -m PyInstaller packaging/mediareview_server.spec --distpath dist/stage9 --workpath build/pyinstaller --noconfirm
```

Server首次构建后因真实HTTP可见性缺陷作一次必要增量重打包（约68秒），APK未重建。新模块F/E9及后台JS语法检查通过；保留既有AGP/弃用/可选tzdata警告。未重跑638/488/178、全量lint或真机测试，验证仅覆盖相关变化和最终主链路。

交付：`deploy_handoff/MediaReview_2.0.0-alpha14_stage9.apk`（code21，43,606,606字节，签名有效且与Alpha13同证书）；`deploy_handoff/MediaReview_Server_1.5.0_stage9.zip`（96,986,726字节，162项文件/完整SHA集合核验通过，实际EXE health1.5.0/Contract2/13能力，EXE未数字签名）。包内含manifest、SHA256SUMS及人工部署说明；本地核验回执为 `review_meta/stage9/delivery-receipt.json`。

SHA256：APK `4d657cf144c6118b3fb1fa2c7d2b46d52fb63bfe303366a9a663f7e15c09c558`；Server ZIP `7cbf0a7fdba576476bbacc752d3a12441dfdc7ac26f51f39792f241acefb067b`。

边界/剩余项：首版JPEG/PNG/WEBP，25MiB/32MP限制；MP4/MKV/MOV/WebM直读，实际手机编码支持待验，部分需seek的输入可能无法生成封面/探测。最多64根、25万扫描条目、128层；拒绝UNC/链接/junction。无本地重复检测、转码、文件整理或跨来源媒体合并。树状分类按来源隔离；遗留平面目录接口仍按相对目录聚合，同名跨根目录的平面统计可能合并，优先使用树状分类。图片限制与EXIF处理参照Pillow官方[Image](https://pillow.readthedocs.io/en/stable/reference/Image.html)/[ImageOps](https://pillow.readthedocs.io/en/stable/reference/ImageOps.html)接口。

实机交付：Alpha14已按用户指令发送到既有QQ收件人，SMTP接受且拒收数为0，实际MIME解码附件字节与上述SHA一致；发送前Defender定向文件扫描完成。生产原实例已核验Server1.5.0/Contract2/13能力、数据库ok及管理页HTTP200，Jellyfin通过ControlHub启动且health HTTP200。升级前活动数据（含配置、配对、SQLite/WAL、缓存及服务文件，既有历史备份不重复复制）、原程序和FFmpeg均有受保护备份并逐文件核验；已安装程序和FFmpeg与发布文件逐文件哈希一致。脱敏回执在 `review_meta/stage9/{email-receipt,production-upgrade-receipt}.json`，没有重跑开发测试或重建产物。

使用顺序：安装邮件中的同签名APK保留数据，在本机后台登记来源并等待扫描完成；手机连接同一局域网。0015回滚须通过ControlHub停服后恢复升级前完整数据及程序备份，不可只换旧EXE。深层目录、图片原图、批阅/文件夹、本地永久删除不可执行及异常编码样本仍需进一步手机验收；本轮不启动下一阶段。

1.5.1实机热修复：代码提交 `d704f51` 已push。认证心跳写事务跨请求保留、锁忙等待及任务claim阻塞事件循环，已用临时库重现后修复；数据库结构和Android未改。相关组合38通过，审查后最终claim受影响目标5通过，版本合同6通过；未重跑全量Server/JVM/connected。实际EXE在临时库持有写锁时12次health全成功、最慢16ms。

部署产物 `deploy_handoff/MediaReview_Server_1.5.1_hotfix.zip`，32,577,238字节，SHA256 `e1e74beb3f74d7b8068c86422f36c2d12356f378b513e65fb6208f20a1e39396`。停服保护备份、155个程序文件哈希和启动身份均核验；原有FFmpeg及配置保留。部署与测试原始回执在 `review_meta/stage9/phone-debug/`。

手机证据：两个H.264/AAC视频分别在约5秒窗口输出146帧；第二个后续窗口150帧、视频Drop0。播放期间12次生产health全成功、最慢16ms。拖动触发解码器flush，但后续日志未保留，不能确认拖动恢复。下午服务随后被用户手动切换成交互式实例；继续工作时重新核对，手机仍显示在线1.5.1。2026-10-19 00:00通过路径/启动时间/端口身份检查后关闭该实例，ControlHub启动成功，SCM Running及health windows_service/AIHome.MediaReview再次核验。无程序或配置二次升级。

剩余问题明确保留：全库是否完整尚未对账；部分Jellyfin封面请求上游404；最新播放信息请求曾约7.5秒，目录请求约11.5秒，原因尚未确认。两段成功播放不能证明这些问题全部解决。
