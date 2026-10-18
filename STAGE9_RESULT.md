# Stage 9 交付结果（2026-10-18）

状态：**CODE_READY / READY_FOR_USER_VALIDATION**。未部署、停止或重启生产实例；未读取或改动真实媒体。**Phone/HomeLAN NOT TESTED，不声称 PRODUCTION_READY / REAL_LAN_PASS。**

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

使用顺序：**先按包内说明通过ControlHub停服备份并人工升级1.5.0**，保留配置/数据/配对，再安装同签名APK；在本机后台登记来源。0015回滚须恢复升级前完整数据备份，不可只换旧EXE。手机只验：深层目录 → 图片及原图 → 视频拖动 → 批阅/文件夹 → 本地永久删除不可执行。真实手机/LAN和异常编码样本留待用户验证；本轮到此，不启动下一阶段。
