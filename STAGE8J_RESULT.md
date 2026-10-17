# Stage 8J 结果

**已确认缺陷修复；真实手机图片主故障未复现，不能声称已在用户设备修好。** 用户不在家，ADB 无设备，本轮按此限制结束，不启动下一阶段。

基线 `79c40fd`，分支 `codex/mediareview-stage8j`。验收版本：Alpha13/code20、Server 1.4.1/Contract2/原十项能力。生产仍为 Server 1.4.0，手机安装版本未核验；生产未部署、未改 Jellyfin、未触碰真实媒体、未清手机数据、无 schema 迁移。源码和本报告同次提交；最终 SHA/push 结果见交付消息，部署包 manifest 绑定源码 SHA。

## 根因与结果

- **图片**：调用链为相对 URL → Android Resolver/同源认证/Coil → Server thumbnail/original → Jellyfin。源码确认：重试只重建原图 Painter，未更新预览请求；预览成功还会遮住原图失败。现已修复，错误提示增加 HTTP/安全请求号，服务端按该号记录上游 HTTP、非图片、大小上限、重定向、连接等分类，不记密钥/上游正文/真实路径。隔离认证代理的三个缩略图 profile 和原图均返回有效 PNG，能解压出预期像素；**不是实际手机渲染或真实 Jellyfin 现场通过**。既有日志只有成功性能事件，不足以归因用户故障；没有猜测改路由或放宽安全限制。
- **0 B**：未知尺寸被映射成 0 后显示成预计空间。summary/prepare 新增可选 `unknown_size_count`，客户端保留未知；部分未知显示已知总量及未知项数，旧 Server 缺字段时不声称总量已知。真实零字节删除仍可为 0 B；缺失和失败不计成功/字节。结果改称“本次已删除文件大小”，不把文件逻辑字节数冒充磁盘可用空间变化。
- **重复/误报**：隔离样本复现旧 ORM 会话可绕过 nonce 已用检查、另一份旧快照可重试 failed 项。改为 SQLite 条件更新先原子认领 nonce，仅处理仍 pending 项；重放返回 409/`DELETE_ALREADY_PROCESSED`。客户端区分已处理与网络结果未确认，不提示盲目重试。另修复“路径资料缺失＝文件不存在”：现在失败并保留索引/队列，只有文件系统确认不存在才计 missing。用户现场“重复删除”是否由这些缺陷触发仍未确认。
- **安全**：保留两阶段、快照、库归属、大小/mtime、链接/junction 检查，补拒绝相对路径、`..`、UNC/URI。正常删除、重复、失败、缺失和路径拒绝均只用临时可丢弃样本验证。

**现场诊断入口**：打开原失败图片，记录 HTTP/请求号，在服务器 `logs/server.log` 按号查 `image_proxy_failed` 的 endpoint/code/upstream_status/reason。不要再次删除真实媒体来复现。中途断流、格式解码、超大原图和真实手机显示仍需现场证据。

## 实际验证

Server 使用当前 `.venv` 并显式设置当前 server 的 `PYTHONPATH`。以下为核心命令（从 server 执行）：

```text
python -m pytest tests/test_delete_receipt_8i.py -k 'stale_session or second_snapshot or relative_deletion or prepare_distinguishes' -q
python -m pytest tests/test_delete_receipt_8i.py tests/test_delete_nonce_11.py tests/test_delete_fav_service.py tests/test_media_image_proxy_11.py tests/test_thumbnail_profiles_8i.py tests/test_health_contract_8d1.py -q
python -m pytest tests/test_health_contract_8d1.py -q
python -m pytest tests/test_delete_fav_service.py -k unknown_path -q
python -m pytest tests/test_delete_receipt_8i.py tests/test_delete_fav_service.py tests/test_delete_nonce_11.py -q
python -m pytest tests/test_media_image_proxy_11.py -k correlated_safe -q
```

最后一个诊断命令实际从项目根以 `server/tests/...` 执行。结果：四个回归 RED→GREEN；六文件首轮 **63 PASS/1旧版本断言 FAIL**，同步版本后 health **6/6 PASS**；路径资料误报 RED→修复后相关三文件 **43/43 PASS**；最终诊断分类 **3/3 PASS**。未改动图片成功分支的既有输出复用。七个改动 Python 文件 `ruff check` 通过，TestClient 弃用警告保留。

Android 从 android 执行（过滤项不是全量 JVM）：

```text
gradlew.bat :app:testDebugUnitTest --tests '*OrganizeViewModelTest' --tests '*DeleteQueueViewModelTest' --tests '*V2ServerOrganizeRepositoryContractTest' --tests '*DeleteReceiptDtoTest' --tests '*ImageFailureTest' --tests '*Stage8IImagePolicyTest' --tests '*ImageRequestLifetimeTest' --tests '*ReleaseNotesTest' :app:assembleRelease --offline --max-workers=1 --console=plain
gradlew.bat :app:testDebugUnitTest --tests '*ReleaseNotesContractTest' --tests '*WhatsNewViewModelTest' --offline --max-workers=1 --console=plain
gradlew.bat :app:testDebugUnitTest --tests '*WhatsNewViewModelTest.升级 19 到 20 只展示 alpha13' --offline --max-workers=1 --console=plain
```

八组相关 JVM **56/56 PASS**、Release APK 一次构建成功（含自动 lintVitalRelease）。版本说明按实际类名另跑 **22 PASS/1旧版本断言 FAIL**，只补验原失败用例 **1/1 PASS**；不是单轮79全绿。TDD 阶段新增诊断函数尚未实现时的编译失败已闭合。AGP/弃用 warning 未顺手修改。

Server 从 server 执行 `python -m PyInstaller packaging/mediareview_server.spec --distpath build/stage8j-dist --workpath build/stage8j-pyinstaller`，一次构建成功；复用现有 builder 校验实际 EXE 隔离 health 为 1.4.1/Contract2/十项能力，APK 自身为 code20/Alpha13，签名通过且证书与 Alpha12 相同。保留 PyInstaller 可选模块/测试模块收集 warning，未扩大依赖。

**未跑**638/488/178全套、connected、全量 lint；未重复构建 APK。仅删除核心扩到相关三文件，未变更全局协议、鉴权或 schema。无设备，软件回归不等同真实 LAN/生产验收。

## 文件与交付

- Server：`app/{services/delete_queue.py,api/v1/delete_queue.py,main.py,__init__.py}`、`pyproject.toml`；测试 `test_{delete_receipt_8i,delete_fav_service,media_image_proxy_11,health_contract_8d1}.py`；部署仅 `deployment/version.ps1`。
- Android：`core/{network/ImageFailure.kt,model/ApiModels.kt}`、`feature/v2/{home/MediaCard.kt,viewer/ZoomableImage.kt}`；`organize/` 下 `OrganizePage.kt`、`OrganizeUiState.kt`、`OrganizeViewModel.kt`、`data/{V2OrganizeRepository,V2ServerOrganizeRepository}.kt`、`delete/{DeleteQueueScreen,DeleteQueueViewModel,DeleteResultSheet}.kt`；另 `app/build.gradle.kts`、`releasenotes/ReleaseNotesCatalog.kt`。五个测试文件：`ImageFailureTest.kt`、`OrganizeViewModelTest.kt`、`V2ServerOrganizeRepositoryContractTest.kt`、`DeleteQueueViewModelTest.kt`、`ReleaseNotesTest.kt`。
- 文档仅本文与 `docs/DEV_LOG.md`、`TASKS.md` 简短追加。没有播放器重写、新 UI 结构、依赖或长期脚本。
- 交付：`deploy_handoff/MediaReview_2.0.0-alpha13_stage8j.apk`；`deploy_handoff/MediaReview_Server_1.4.1_stage8j.zip`（人工升级负载，非自动安装器）。SHA 见包内清单与本地 `review_meta/stage8j/delivery-receipt.json`；成功输出和构建输入摘要保留于该目录，不另打源码/测试证据大包。生产升级仍需另行授权，自动升级桥封锁未解除。

## 本地媒体源建议

| 链路 | 关键耦合 / 接入点 |
|---|---|
| 发现 | Jellyfin items_page/mapper → media_index.upsert；可从统一 MediaItem DTO 接只读扫描器 |
| 图片 | media API 直接依赖 JellyfinClient；缓存可复用，原图须另接文件读取能力 |
| 视频 | 依赖 Jellyfin URL、设备 key、PlaybackInfo/HLS/进度，不能只换扫描器 |
| SQLite | media_id 派生自 Jellyfin ID，jellyfin_id 非空，库选择也用 Jellyfin ID；需隔离来源身份 |
| 删除 | 已直接操作本地文件，nonce/审计可复用；库归属依赖索引，新本地源必须补根白名单，禁止客户端提交路径 |

**下轮优先原生 Python 只读文件夹适配器原型**：仅临时根目录扫描、稳定不透明 ID、SQLite/图片；视频 Range/授权与删除根边界另验。这是基于现有代码的判断，不增加常驻服务。[copyparty 官方 API](https://github.com/9001/copyparty/blob/hovudstraum/docs/devnotes.md) 有独立文件操作与递归删除语义，仍需身份、权限、删除合同映射；明确需要独立文件 HTTP 服务时再受控验证。本轮未安装/启动 copyparty，未替换 Jellyfin。
