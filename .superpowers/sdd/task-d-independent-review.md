# Task D 破坏性安全独立审查报告

- 审查对象:Task D git diff(6485881..HEAD,HEAD=afa6b0b,分支 feature/mediareview-1.1)
- 审查性质:对抗性破坏安全(destructive-safety)审查
- 审查日期:2026-09-02
- 工作树状态:clean(提交前确认 `git status` = nothing to commit)

---

## 1. 审查范围与方法

### 范围(按任务书 S 列文件,全部为已提交改动)
- 服务端:server/app/api/v1/delete_queue.py、duplicates.py、tasks.py;server/app/services/delete_queue.py、duplicate_scanner.py、tasks.py;server/app/db/models.py、migrations/versions/0014_*.py;server/app/main.py
- 服务端测试:server/tests/test_delete_nonce_11.py、test_duplicate_persist_11.py、test_phase456_api.py、test_stage7_fix.py
- Android:DeleteQueueViewModel.kt、DuplicatesScreen.kt、DuplicatesViewModel.kt、ApiModels.kt、MediaReviewApi.kt、MediaDataSource.kt、MediaRepository.kt
- Android 测试:TestFakes.kt、ApiModelsTest.kt、DuplicatesViewModelTest.kt、MutationInvalidationViewModelTest.kt、MainShellProductionIntegrationTest.kt

### 方法
1. `git diff 6485881 HEAD --stat` 确认改动面(27 文件,+2092/-91)。
2. 全文通读以上新增/修改文件(未仅看 diff,关键文件整读)。
3. 为上下文额外阅读:delete_queue 服务全量、duplicate_scanner 全量、tasks.py 全量、models.py 全量、db/session.py(get_db 提交/回滚语义)、audit.py、media_index.py 的 `selected_library_ids`/`get_cached_media`、DeleteQueueScreen.kt(最终确认对话框)、迁移链 0012/0013/0014、test_delete_fav_service.py(身份重校验守卫测试,非本次新增但验证同一路径)。
4. 检索服务端全部 `os.remove/unlink/rmtree`,确认唯一真实媒体删除点。
5. 检索 `commit_all` 调用方,确认未暴露为 API。
6. 检索测试是否使用真实媒体目录。
7. 检索仓库内禁止工件。
8. 实际运行三项强制验证命令(见第 4 节)。

---

## 2. 破坏安全清单逐项裁决

### 2.1 两阶段删除 nonce 合同 — CLEAN
- **一次性(reuse 拒绝)**:`DeleteCommitNonce.used` 置位 + `NonceReusedError` → API 409(`delete_queue.py:178-179`,API `delete_queue.py:90-91`);`test_commit_rejects_reused_nonce` 实测断言。
- **过期**:`expires_at < utc_now()` → `NonceExpiredError` → API 409;TTL 10 分钟(`delete_queue.py:180-181,28`);`test_commit_rejects_expired_nonce` 实测断言。
- **伪造/篡改拒绝**:nonce 为 `secrets.token_hex(16)`(128 位随机,服务端落库),客户端 commit 仅提交 nonce,**不提交任何 media_id / 路径**;服务端按主键查库、快照 `media_ids_json` 由服务端在 prepare 时写入。任务书提到"服务端重算 digest"——本实现改为服务端持有随机一次性 token 而非 digest 式合同,防伪强度等效(甚至更强,无需依赖可预测输入)。
- **服务端从 DB 重取媒体、绝不信任客户端路径**:commit 阶段 `mi.get_cached_media(session, media_id)` 从 `media_cache_index` 取 `media_path`(`delete_queue.py:212-213`),客户端无处注入路径。
- **校验库归属/队列成员/当前文件身份**:`_deletion_guard`(`delete_queue.py:106-138`)逐项校验 selected library 归属、文件存在且为普通文件、size 一致、mtime 容差 3s 内;身份不符 → `failed` 并写审计,绝不删除。相关守卫由既有 `test_delete_fav_service.py` 三个用例实测(体积变化/同体积替换 mtime 变化/取消库勾选均拒绝删除)。
- **逐项独立执行**:`_commit_media_ids` 逐项 try/except,单项 `failed` 不影响其余项(`delete_queue.py:205-245`);新入队媒体不在快照内 → 不删(TOCTOU 测试 `test_commit_only_deletes_nonce_snapshot_toctou` 实测);prepare 后被撤销项 commit 时 `row is None` → 跳过。
- **逐项审计**:每项 `audit.log_action("delete_commit", media_id, detail=...)`(`delete_queue.py:235`)。
- **TOCTOU 窗口**:快照绑定 prepare 时刻的 pending 集合;prepare 之后新入队项绝不被 commit 删除;快照内项在 commit 时仍须存在队列行,否则跳过。无"队列变更后仍被顺手删除"的窗口。
- 已知固有限制(非破坏性,Minor 级,见 3-M2):若 commit 中途抛未预期异常,SQLite 事务回滚(used 复位)但已 `os.remove` 的文件不回滚;重试时这些文件按 `missing` 幂等清理,最终一致,不误删他物。

### 2.2 禁止"点击即永久删除" — CLEAN
- 服务端不存在单步删除端点;唯一删除入口是带 nonce 的 `/delete-queue/commit`。
- Android:`DeleteQueueScreen.kt` “最终删除”按钮仅置 `showConfirm=true`(第 97-100 行),必须经 `AlertDialog` “确认永久删除?”二次确认后才调用 `viewModel.commit()`(第 122-139 行)。`commit()` 内部先 prepare 取 nonce 再 commit(`DeleteQueueViewModel.kt:82-100`)。
- 无隐式删除:媒体墙/批阅路径只入队(`enqueue`),可撤销;`commit_all` 仅测试使用,未挂路由。

### 2.3 重复扫描绝不自动删除 — CLEAN
- `duplicate_scanner.py` 全部 scan 函数为只读(纯内存/DB 聚合),模块内无任何 `os.remove`;检索确认服务端唯一媒体删除点在 `delete_queue.py:225`(已守卫),`sprite.py:239` 仅清理自产雪碧图缓存。
- `keep` 仅是 `duplicate_group_member.keep` 布尔记录(`set_keep`),不触发任何删除。
- 分组持久化 `session.merge` 幂等(`_persist_group_rows`),重扫覆盖。
- Android 重复页无删除入口;`DuplicatesScreen.kt:298-304` 明确文案“重复文件不会自动删除……删除仍需在待删除页确认”。

### 2.4 任务控制安全 — CLEAN(附 Minor 备注)
- **不重复运行**:`TaskManager._claim_next` 用 `UPDATE … WHERE status='pending' RETURNING` 原子认领,单实例下同一 task 不会被二次取走;`schedule_duplicate_scan` 复用 pending/running/paused 任务(幂等)。
- **无死锁**:处理器在 `to_thread` 线程、每阶段独立短会话提交;SQLite 开 WAL + `busy_timeout=5000`;HTTP 请求与后台线程并发写受 busy_timeout 保护。
- **不损坏 duplicate_group 行**:写入仅 `merge`(按 group_id 幂等覆盖),读取用独立短会话;并发读可能看到“新旧混合”的瞬时分组(分页边界语义),非损坏。
- **状态转换有界**:cancel/pause 仅作用于 pending/running(SQL WHERE 守卫);resume 仅作用于 paused;`_finish_scan` 用 `WHERE status='running'` CAS,不会把 cancelled/paused 覆盖为 succeeded;succeeded/failed/cancelled 为终态。
- **取消真正停止循环**:`_duplicate_scan_pass` 每分组前查 `_task_status != "running"` 即 break。
- **进度单调**:单趟内 5→95→100 单调;跨 pause/resume 会重置(Minor,见 3-M3)。

### 2.5 迁移 0014 — CLEAN
- 单一线性 head:0012→0013→0014(`down_revision="0013"`,0013 的 `down_revision="0012"`,仅一个迁移文件对应 0014,无分支)。任务书提示“down_revision=0012”与实际不符,但实际 0013 在链路中间,线性成立,属任务书表述误差而非代码问题。
- upgrade/downgrade 对称(索引、三张新表逐一对称)。
- 仅新建表,无对既有表的重写/破坏性变更;round-trip 由 `test_migration_0014_adds_nonce_and_duplicate_tables` 实测(升 0014 保留旧行,降 0013 后三表消失且旧行仍在)。
- FK/约束合理:`duplicate_group_member` 组合主键 (group_id,media_id),无外键依赖(与项目既有风格一致)。

### 2.6 无密钥/Token/绝对路径泄漏 — FINDING(Minor,见 3-M1)
- 提交/预览均不携带 API key/token;nonce 仅出现在请求体;URL 与日志无新增凭据。
- Android 播放凭据仅走 headers(既有 Task C 合同,本次未新增)。
- **异常**:`delete_queue.py:231` `row.error = str(exc)` 与 `:234` 审计 `detail` 在 `os.remove`/`os.stat` 抛 `OSError` 时会把**含绝对服务器路径**的异常文本(如 `[Errno 13] Permission denied: 'C:\...\file.mp4'`,已实测确认格式)写入 `delete_queue.error` 与 `audit_log.detail`。仅存于服务端本地 SQLite;`list_queue` 视图与 commit outcome 均不返回该字段,不会到达客户端;但违背“audit 中不出现绝对路径”的清单要求 → Minor。

### 2.7 测试诚实性 — CLEAN
- 真实删除测试全部使用 `tmp_path` 一次性夹具(`test_delete_nonce_11.py`、`test_duplicate_persist_11.py`、`test_phase456_api.py:174 tmp_path/"victim.mp4"`),未触碰真实媒体目录。
- 无伪造“全通过”:本审查实际运行了命令并记录真实输出(见第 4 节),15 项服务端用例全过、ruff 全过、Android 构建成功。
- 合同断言覆盖:nonce 一次性/过期/未知、TOCTOU 快照、API prepare→commit→复用 409→缺 nonce 422;身份重校验(size/mtime/library)由既有 `test_delete_fav_service.py` 覆盖;迁移 round-trip 已覆盖。

### 2.8 禁止工件 — CLEAN
- `git ls-files` 按 `apk|build/|\.tmp|\.env|key\.properties|jks|keystore|\.db$|\.log$` 检索仅命中 `docs/HERMES_APK_EMAIL_DELIVERY.md`(文档文件名含 "apk",非构建产物)。无 APK、build/、.tmp、venv、密钥、.db、.log 被跟踪。

---

## 3. 发现清单

| # | 级别 | 位置 | 说明 | 建议 |
|---|------|------|------|------|
| M1 | M=Minor | server/app/services/delete_queue.py:231,234 | `os.remove`/`os.stat` 的 `OSError` 文本经 `str(exc)` 写入 `delete_queue.error` 与审计 `detail`,其中可能含服务器绝对路径(仅存本地 SQLite,不外发,但违反“audit 无绝对路径”) | 改用 `exc.errno`/`strerror` 或剥离文件名后再落库/落审计 |
| M2 | M=Minor | server/app/services/duplicate_scanner.py:443-449 | 每趟扫描先无条件清空全部旧 duplicate_group/member;中途取消/暂停会丢失上一次完整扫描结果(仅元数据,非媒体文件);且 `_persist_group_rows` 恒写 keep=False,重扫会清掉人工保留标记 | 取消/暂停时保留旧分组直至新一趟完整成功,或合并保留 keep 标记 |
| M3 | M=Minor | server/app/services/duplicate_scanner.py:448,461;schedule_duplicate_scan:324-343 | ① resume 后 progress 从 5 重新计(跨暂停非单调);② `schedule_duplicate_scan` 对 paused 任务只复用不恢复,POST /scan 在暂停态为 no-op(UI 走“继续”按钮可绕开,属易混淆) | resume 时继承上次 progress 或重新整趟计算;scan 接口对 paused 直接置 pending |
| M4 | M=Minor | 任务书描述 vs 实际 | 任务书清单第 5 项写“down_revision=0012”,实际 0014 的 down_revision=0013(链路 0012→0013→0014,线性成立),系任务书表述误差,非代码缺陷 | 无需改代码;如正式记录需修正任务书文字 |

另附非问题备注(记录为 CLEAN 佐证,不需整改):
- 崩溃后遗留的 `running` 任务不会被重新认领(重启后仅取 pending),表现为卡死任务而非重复运行——非破坏。
- commit 中途未预期异常时 DB 回滚而文件已删,重试按 missing 幂等清理——非破坏。

---

## 4. 已执行验证命令与真实输出

### 4.1 服务端测试(工作目录 server)
```
.venv\Scripts\python.exe -m pytest tests\test_delete_nonce_11.py tests\test_duplicate_persist_11.py -q
```
输出(末段,真实):
```
...............                                                          [100%]
============================== warnings summary ===============================
... StarletteDeprecationWarning: Using `httpx` with `starlette.testclient` is deprecated ...
-- Docs: https://docs.pytest.org/en/stable/how-to/capture-warnings.html
```
- 结果:15 passed(13=nonce 文件 8 例 + persist 文件 7 例,与 15 个点对应),exit code 0。

### 4.2 服务端 ruff(工作目录 server)
```
.venv\Scripts\python.exe -m ruff check app tests
```
输出(末段,真实):
```
All checks passed!
```
- 结果:exit code 0,无告警。

### 4.3 Android 聚焦 JVM 测试(工作目录 android)
```
$env:JAVA_HOME="E:\aihome\tools\jdk\jdk-17.0.13+11"; .\gradlew.bat --offline --no-daemon :app:testDebugUnitTest --tests "*DuplicatesViewModelTest" --tests "*MutationInvalidationViewModelTest" --tests "*ApiModelsTest"
```
输出(末段,真实):
```
> Task :app:transformDebugUnitTestClassesWithAsm UP-TO-DATE
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 40s
31 actionable tasks: 1 executed, 30 up-to-date
```
- 结果:BUILD SUCCESSFUL,exit code 0。

### 4.4 辅助核验
- `git status`:clean;HEAD=afa6b0b。
- `git ls-files | grep -iE "apk|build/|\.tmp|\.env|key\.properties|jks|keystore|\.db$|\.log$"`:仅命中 `docs/HERMES_APK_EMAIL_DELIVERY.md`。
- 全服务端 `os.remove|unlink|rmtree` 检索:唯一媒体删除点 delete_queue.py:225(已守卫)。
- `commit_all` 检索:仅测试引用,无路由暴露。
- 迁移链核验:0014.down_revision=0013、0013.down_revision=0012(单一线性 head)。

---

## 5. 结论

**CLEAN(0 Critical / 0 Important / 4 Minor)**

无需关闭任何 Critical/Important 项。4 项 Minor 均不构成破坏性安全缺陷(无越权删除、无自动删除、无单步永久删除、无凭据泄漏;绝对路径仅可能出现在服务端本地 SQLite 审计/错误字段且不外发)。建议在后续阶段顺手处理 M1(OSError 去路径化)与 M2(重扫保留旧分组与 keep 标记),M3/M4 可选。
