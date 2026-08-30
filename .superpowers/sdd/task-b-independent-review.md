# MediaReview 1.1 Task B 第二轮独立复审报告

日期：2026-08-30 | 复审对象：冻结 diff `.superpowers/sdd/review-6577939-taskb.diff`
（基线 6577939 → 工作树，846 行）+ B1 提交 6577939（抽查）
复审人：独立 reviewer agent（第二轮，与执行 agent 分离）

## 结论行

**CLEAN**（0 Critical / 0 Important / 6 Minor，其中最重要的 M-A 为基线既有 flaky，非本轮修复引入）

## Critical

无。

## Important

无。

## Minor

**M-A（既有，非本轮引入）：`latest_active_session` 平局打破键与创建顺序无关，全量门禁偶发红**
- 位置：`server/app/services/review.py:225-235`（排序 `updated_at.desc, created_at.desc, session_id.desc`）与 `review.py:21-22`（`_new_session_id()` = 秒级时间戳 + `secrets.token_hex(4)` 随机后缀）。
- 证据：复审人复跑全量 3 次中有 1 次失败——`FAILED tests/test_review_service.py::test_complete_and_latest_active`，断言 `'20260830085135e8bba395' == '2026083008513560ab7829'`。两个会话在同一秒内创建且 `created_at/updated_at` 微秒碰撞时，`session_id.desc()` 的随机后缀使"最新会话"选择与创建顺序无关。单跑该测试 5 次全过；`git diff HEAD --stat` 证实 `review.py`、`models.py`、`test_review_service.py` 与基线 6577939 完全一致——**非本轮修复引入**。
- 修复要求：后续把平局打破键改为单调量（微秒时间戳或自增列），或测试中显式控制时钟。生产影响极低，但会污染门禁信号。

**M-B：终态文案在 UI 上实际不可达**
- `SpriteViewModel.kt:118-129`：失败分支在无挂起点的同步块内先 `copy(progress, taskStatus)` 再 `copy(pending=false, scrubbing=false)`，Compose 同帧合并后只渲染最终值，"雪碧图生成失败，可重新长按重试"不可见，覆盖层直接消失。行为本身正确（停止轮询、复位、允许重试），仅文案死代码化。修复要求：终态时保留一帧显示或删除误导性文案。

**M-C：取消按钮显隐条件未过滤空 taskId**
- `SpritePreviewUi.kt:172`（`state.taskId != null`）：`ensure` 失败或服务端 ready 直返时 `task_id` 为 `""`（非 null），按钮仍显示；点击后 `cancel()` 的 `isNullOrBlank()` 守卫跳过网络调用，仅本地复位。建议改 `isNotBlank()`。

**M-D：sprite 生成异常路径终态写入无 CAS**
- `sprite.py:154-165`：`make_sprite` 异常路径直接 `task.status = "failed"` 赋值。生成异常期间任务被协作取消时终态会被覆盖（cancelled→failed）。两者均为终态且 Android 处理相同，仅状态语义受影响。建议后续统一 CAS。

**M-E：`ensure_sprite` 的 202 作用于整个端点**
- `cache.py:98`：manifest 已 ready 直返路径也返回 202（语义上应为 200）。已核实无破坏面（无其他调用方；Android Retrofit 视 2xx 均成功）。仅语义瑕疵。

**M-F：ImageViewerViewModel 的 runCatching 吞取消（瞬态）**
- `ImageViewerViewModel.kt:49`：外层 `runCatching` 捕获取消并把 error 写入 `_ui`。离开页面时无人消费、retry 会覆盖，瞬态自愈。

## 第一轮 I-1/I-2 closure 矩阵

| 项 | 状态 | 证据 |
|---|---|---|
| I-1a `_loop` 异常保护 | **CLOSED** | `tasks.py:58-66`：`try _tick` → `except asyncio.CancelledError: raise`（不被吞）→ `except Exception` + `logger.exception` → 循环继续。`test_task_manager.py` 实测 1 passed (0.58s)。 |
| I-1b 100k 测试 flaky | **CLOSED** | `test_media_api.py:520-556`：client 启动前独立 Database 完成 `run_migrations` + `LibrarySelection(selected=True)` 预勾选 + 5×20k 批量插种 → `seed_db.dispose()` → 启动 client。实测两次全新临时目录全过（27.55s / 23.86s）；预勾选使 `/media` 直读 SQLite（total==100000 且 `calls["items"]==0` 保留并通过）。 |
| I-2 ruff W605 | **CLOSED** | `ruff check .` → All checks passed!；`ruff format --check .` → 87 files formatted。 |

## 其余修复项核实

- **M-1 CLOSED**：`sprite.py:197-222` 终态 CAS（RETURNING + synchronize_session=False，`cancel_task` 有先例）。输掉分支：rollback → 丢文件 → 删 manifest → commit，无残留。`test_sprite_service.py` 16 passed。
- **M-2/M-3 CLOSED**（M-B 保留）：终态停止轮询并复位；轮询耗尽分支带 `cur.pending && !cur.ready` 守卫不误复位 ready；取消竞态自洽；"失败/取消→复位→再次 begin()"推演成立（ensure 幂等 → 同一/新建任务 → 重新轮询）。
- **M-5 CLOSED**：ErrorBox 全仓 0 引用；MediaWallScreen 无死 import。
- **M-7 CLOSED**：`MediaPagingSource.kt:30-33` 取消上抛；JUnit XML 证实 MediaPagingSourceTest 10 tests 全执行（含新取消测试）。

## 证据核实表（复审人实际重跑）

| 命令 | 结果 |
|---|---|
| `ruff check .` / `ruff format --check .` | All checks passed! / 87 files formatted |
| `pytest tests/test_task_manager.py -v` | 1 passed (0.58s) |
| 100k 测试 ×2（全新临时目录） | 2 次全过（27.55s / 23.86s）；durations [0.061,0.035,0.032]、folders 0.634s |
| `pytest tests/test_sprite_service.py -v` | 16 passed |
| 全量 `pytest tests` | **282 tests / 0 failures / 0 errors, exit=0**（1 次因 M-A 既有 flake 失败，复跑全绿） |
| Android `:app:testDebugUnitTest --rerun-tasks` | BUILD SUCCESSFUL；**122 tests / 0 failures**（MediaPagingSourceTest=10、ViewerAndSpriteLogicTest=9 真实执行） |
| `:app:assembleDebug :app:lintDebug :app:assembleAndroidTest` | BUILD SUCCESSFUL (83 tasks) |

执行 Agent 声称的门禁数字全部复现属实。

## 对抗性复查结论（无新引入问题）

- `sqlalchemy as sa` import 组织与 tasks.py 惯例一致；MediaWallScreen 无死 import；`_query` 保持 private（对外 asStateFlow）；test_media_api.py 局部 import 与项目测试惯例一致。
- B1/B2 主路径未受波及：`media_index.py` 与 `app/api/v1/media.py` 相对基线零改动；100k Jellyfin 零调用断言保留并实测通过；`cancel(media_id)` 接线正确。
- 知情保持原状项（M-4/M-6/M-8）复核后均不构成 blocker。

## 最终结论

**CLEAN。** 第一轮 2 个 Important 全部关闭；6 个 Minor 不阻塞，其中 M-A 为基线既有
（建议 Task C 前顺手修复平局打破键），M-B/M-C/M-D/M-E/M-F 随下一阶段处理。
