# Stage9 体检问题修复

## 已确认根因与改动

Jellyfin 的同一 Item 可同时存在于父、子媒体库；缓存单值 `library_id` 被后续同步覆盖，导致子库目录缺失。新增 0016 归属表，保持媒体 ID、收藏、批阅和待删关联不变。列表全局去重，目录、批阅、重复检测和删除权限按实际归属查询；成功同步只失效本次目标库未见的关系，仍在其他库的媒体继续可用，失败或取消不清理旧关系。删除成功/文件不存在后清理全部归属。本地来源继续原有单根扫描及只读删除限制。

Android 修正媒体库管理/重复页状态栏遮挡；诊断详情默认收起；空态提供范围解释和重载；待删失败历史与 pending 摘要分开；筛选补充单选/选中语义；图片未知大小不再显示 0 KB。重复摘要区分未扫描、失败、取消、暂停及成功后无重复，避免把未扫描说成“未发现”。

关键文件：

- Server：`app/db/models.py`、`app/db/migrations/versions/0016_media_library_membership.py`；`services/media_index.py`、`folder_tree.py`、`review.py`、`duplicate_scanner.py`、`delete_queue.py`。
- Android：`FolderBrowserScreen.kt`、`HomeScreen.kt`、`RuntimeDiagnosis.kt`、`SortFilterSheet.kt`、`LibraryManagerScreen.kt`、`DuplicatesScreen.kt`、`DeleteQueueScreen.kt`、`ImageInfoSheet.kt`、整理摘要与版本更新日志。
- 发布：Server 版本声明、`deployment/version.ps1`、`scripts/build_deploy.py`；旧版本测试断言改为当前声明/当前摘要合同，0010 查询用例先升级到当前 schema。
- 回归复用 `test_media_index_11.py`、`test_folder_tree.py`、`test_delete_fav_service.py` 等，新增一项迁移回填/降级保护测试；不引入新框架。

## 已执行验证

Server 第一轮修复回归 158 项：155 通过、1 失败、2 跳过。唯一失败是集合 UNION 内部排序触发已有十万条查询计划门禁；改用 IN 集合语义与 UNION ALL 后，受影响的 30 项补测 29 通过、1 跳过，失败项已关闭。合计覆盖 156 项通过、2 项跳过，**分轮闭环，不是单轮全绿**。另迁移补充本地不可用记录保持不变的断言后，单项复测通过。

跳过原因：Windows 未授权建立文件符号链接；其余临时样本上的正常删除、重复请求、失败不报成功、路径边界及本地只读限制按实际测试结果记录。没有操作真实媒体。

命令（server 工作目录；相应 XML/log 存于本机 `review_meta/stage9/repair-server*.{xml,log}`）：

```python
from sqlalchemy import event
from sqlalchemy.engine import Engine
import pytest
event.listen(Engine, "connect", lambda c, r: c.execute("PRAGMA temp_store=MEMORY"))
pytest.main(["tests/test_media_index_11.py", "tests/test_membership_migration.py",
 "tests/test_folder_tree.py", "tests/test_review_folder_scope.py",
 "tests/test_delete_fav_service.py", "tests/test_delete_nonce_11.py",
 "tests/test_delete_receipt_8i.py", "tests/test_native_sources.py",
 "tests/test_native_playback.py", "tests/test_organize_center_8c.py",
 "tests/test_duplicate_persist_11.py", "tests/test_deployment_contract_11.py",
 "tests/test_db.py", "-q", "--tb=short"])
```

补测限定为 `test_db.py::test_0010_query_indexes_serve_all_deterministic_sorts_on_100k_rows`、迁移、原生源和索引四个目标。测试进程使用内存临时表规避本机 Windows 临时目录权限问题，未修改生产 SQLite 配置。改动 Python 文件的 `ruff check --select F,I` 通过；没有为无关 warning 修改代码。

发布完整性相关两个文件共 90 项：第一轮 85 通过、2 失败、3 跳过；旧版本产物名称断言改为当前版本后单项通过。合计 86 项通过，1 项环境阻断、3 跳过。阻断项 `test_scm_query_failure_stops_before_writing_data` 在导入脚本前即被 Windows PowerShell 执行策略拒绝（未实际执行 SCM 模拟），不计通过，没有修改系统策略或部署脚本。命令：`python -m pytest tests/test_release_package_integrity_8e.py tests/test_repackage_integrity_8e2.py -q --tb=short`，仅重跑了旧名称断言失败项。

Android 四个定向类合计 48 项通过、0 失败/跳过。先以新增用例复现未扫描文案错误，再修复；另更新仍固定 Alpha14 的版本说明断言，保留历史版本说明测试。命令：

```text
gradle :app:testDebugUnitTest --tests '*OrganizeViewModelTest' --tests '*DeleteQueueViewModelTest' --tests '*ReleaseNotesContractTest' --tests '*WhatsNewViewModelTest' :app:assembleRelease --offline --no-daemon --max-workers=1 '-Pkotlin.compiler.execution.strategy=in-process'
```

使用登记的 JDK17/Gradle8.9，进程级 Gradle 缓存与 JVM 临时目录位于项目 `review_meta/stage9`，复用用户已有依赖；没有安装第二套工具或更改系统环境。首次环境阻断不计测试通过；保留 AGP/compileSdk 兼容提示和既有弃用 warning。

Release APK 已构建，实际 manifest 为 Alpha15/code22，签名验证通过、证书与已交付 Alpha14 一致。APK 43,606,610 字节，SHA256 `b950d25f88d8a520fdc36afff3400b35c422567da6b3498b4bcf853a8f93ee44`。Server 通过现有 PyInstaller spec 增量构建到独立输出目录，未清空旧 dist；实际 EXE 为1.5.2/Contract2，隔离启动迁移0016、两个库各1项/全局去重1项、仅选子库仍1项、收藏与路径隐私检查通过，进程已停止。首次探针漏填假 user_id 被422拒绝，补齐夹具后通过，未重建EXE。EXE SHA256 `e0dbb6d07b65b58731e844ef33625792af8dbb5401f848708e9b5381e7513b17`。

构建基线为 `9ef0719` 的本轮工作树，输入指纹保存在本机 `repair-build-inputs.json`；最终提交后再比对指纹并消费既有 builder 的验证/打包门禁，不为写报告重新构建 APK。源码分支 `codex/mediareview-stage9-native-source`，最终提交及产物关联见交付 manifest 与 Git；本机验证回执位于 `review_meta/stage9/repair-*-proof.json`。

未重跑全套 Server/JVM、178 connected 或全量 lint；本轮按影响范围验证。当前 ADB 无设备，手机显示、原先两个目录及视频拖动尚未完成新版实机验收。此前 `Invalid NAL length` 的根因仍未确认，没有猜测性修改播放器或批量转码。

## 升级与回滚边界

源码目标 Server 1.5.2 / Contract2、Android Alpha15/code22；生产健康检查仍为 1.5.1。未授权部署本次版本，未改变 ControlHub、端口、服务或生产数据。

0016 仅新增 Jellyfin 归属表并回填尚存的旧归属，无法重建已被覆盖的历史关系。获准升级后须通过 ControlHub 停服，备份成对的程序与全部活动数据；升级后启动，确认后台已勾选需要的目标库，再点一次“刷新媒体库”，同步成功后复核缺失目录。回滚应停服恢复成对备份，不能只换回旧 EXE；升级后新增状态需另行保留。存在额外活动归属时 Alembic 降级拒绝有损操作。

本轮临时测试目录的递归清理被自动审批策略拒绝；已保留并排除 Git 提交，不尝试绕过。
