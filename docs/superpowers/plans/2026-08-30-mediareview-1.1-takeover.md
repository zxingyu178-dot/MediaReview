# 家庭媒体管家 1.1 Takeover Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `subagent-driven-development` (recommended) or `executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 从历史包含 `ef528d363f4cca158c6fdbe60a59424fa0615c4a` 的 D 盘接管分支继续，先把 Task 4 修到独立 CLEAN，再完成媒体墙、播放器、整理、运维、部署和真实环境验收，交付可升级可回滚的 `1.1.0`。

**Architecture:** Android 只经 MediaReview Server 做控制、索引和整理，视频由 Android 直连 Jellyfin；Server 以 SQLite 索引为普通请求唯一列表源。每个阶段形成可安装候选、冻结 diff、独立审查和回退点，不跨阶段混改。

**Tech Stack:** Python 3.12、FastAPI、SQLAlchemy 2、Alembic、SQLite、pytest、Kotlin 2.0、Jetpack Compose、Material 3、Paging 3、Media3、Retrofit/OkHttp、DataStore、Hilt、Coil、Gradle 8.9、Windows PowerShell。

## Global Constraints

- 局域网、单用户；不实现公网、云同步或多人账户。
- FastAPI 禁止转发完整视频流。
- Android 用户可见文字全部中文；Material Icons，不使用 Emoji/文本符号充当控件。
- 固定深色视觉：`#0B1118`、`#141D27`、`#47D7E8`、`#F4F7FA`、`#A9B4C0`、`#39D98A`、`#FF6B6B`。
- Windows 后台只做运维，不复制 Android 媒体墙。
- 最终版本 `1.1.0`；旧配置、数据库、配对、收藏、删除队列和批阅状态无损升级。
- 不接触现有生产服务和 `C:\ProgramData\MediaReview`；测试必须使用临时数据根。
- 迁移链当前 head 是 `0012_pairing_device_identity`；后续迁移从 `0012` 延伸。
- 每个任务必须 TDD、focused GREEN、全量回归、独立审查；0 Critical / 0 Important 才能进入下一任务。
- 没有真实手机验收时最多标记 `rc1`，不得发布正式 `1.1.0`。

---

### Task 0A: 补齐可移植阶段验收工具

**Files:**
- Create: `scripts/build_review_handoff.py`
- Create: `server/tests/test_review_handoff_builder.py`
- Modify: `REVIEW_HANDOFF_RULES.md`
- Verify: `docs/TOOLCHAIN_AND_RELEASE_OPERATIONS.md`

**Interfaces:** `python scripts/build_review_handoff.py --stage <id> --name <name> --base <git-ref>` 输出 `review_handoff/MediaReview_Review_Stage-<id>_<timestamp>.zip`；失败返回非零。

- [ ] **Step 1: Write RED tests** proving the builder is missing and specifying required ZIP entries: summary, status/log/diff, test/lint evidence, changed text source and project status docs. Add rejection cases for `.env`, DB, APK/AAB/EXE, build/cache, logs and files over the configured size limit.
- [ ] **Step 2: Implement a standard-library-only builder** using `argparse`, `subprocess`, `pathlib` and `zipfile`. Git unavailable, invalid base, missing summary, secret-pattern path, unreadable file or ZIP write failure must stop with nonzero status.
- [ ] **Step 3: Prove deterministic scope**: use `git diff --name-only <base>..HEAD` plus tracked working-tree changes; include only approved text extensions and never follow paths outside the repository.
- [ ] **Step 4: Run focused and full Server tests**, inspect ZIP with `7z l`, extract into a temporary directory, confirm no forbidden entry and verify recorded commands are real outputs.
- [ ] **Step 5: Commit** as `build(review): add portable handoff packager` before changing Task 4 production code.

---

### Task A: 关闭 Task 4 两个 Important

**Files:**
- Modify: `android/app/src/main/java/com/mediareview/app/feature/deletequeue/DeleteQueueViewModel.kt`
- Modify: `android/app/src/main/java/com/mediareview/app/core/model/ApiModels.kt`
- Modify: `android/app/src/test/java/com/mediareview/app/feature/MutationInvalidationViewModelTest.kt`
- Modify: `android/app/src/androidTest/java/com/mediareview/app/ui/shell/MainShellProductionIntegrationTest.kt`
- Verify: `server/app/services/delete_queue.py`
- Verify: `server/tests/test_delete_fav_service.py`
- Verify: `server/tests/test_phase456_api.py`
- Update: `TASKS.md`, `docs/DEV_LOG.md`, `.superpowers/sdd/task-4-*.md`

**Interfaces:**
- Consumes Server outcome strings: `success`, `missing`, `failed`.
- Produces Android parser `DeleteOutcomeStatus.fromWire(value: String): DeleteOutcomeStatus` with `changed` and `successful` properties.
- Produces a production-shell regression that exercises `MainShellScreen -> ReviewViewModel.onRootDeactivated()` while playback lookup is in flight.

- [ ] **Step 1: Freeze baseline and reproduce review findings**

Run:

```powershell
git rev-parse HEAD
git status --short
git merge-base --is-ancestor ef528d363f4cca158c6fdbe60a59424fa0615c4a HEAD
Get-Content .superpowers\sdd\task-4-post-fix-review.md
```

Expected: ancestor command exits `0`; status empty; review says `NOT CLEAN` with 2 Important. HEAD may be later than the D-copy creation commit because handoff documentation can be corrected independently.

- [ ] **Step 2: Write deletion-contract RED tests**

Add table-driven assertions for:

```kotlin
mapOf("a" to "success")
mapOf("a" to "failed")
emptyMap()
mapOf("a" to "missing")
mapOf("a" to "success", "b" to "missing", "c" to "failed")
```

For each case assert exact success/failure counts, Chinese summary, and revisions for Media/Favorites/DeleteQueue/Duplicates. Before production changes, the all-`success` case must fail because current code expects `deleted`.

- [ ] **Step 3: Implement the shared Android outcome parser**

Use one parser rather than string checks in the ViewModel:

```kotlin
enum class DeleteOutcomeStatus(val changed: Boolean, val successful: Boolean) {
    Success(changed = true, successful = true),
    Missing(changed = true, successful = false),
    Failed(changed = false, successful = false),
    Unknown(changed = false, successful = false);

    companion object {
        fun fromWire(value: String): DeleteOutcomeStatus = when (value.lowercase()) {
            "success" -> Success
            "missing" -> Missing
            "failed" -> Failed
            else -> Unknown
        }
    }
}
```

`DeleteQueueViewModel.commit()` must compute summary from parsed values and invalidate `ContentMutation.FinalDelete` only when any parsed result has `changed=true`.

- [ ] **Step 4: Write production-shell settle RED test**

Configure the instrumentation fake with one video queue item and a `CompletableDeferred` playback lookup. In the production `MainShellScreen` Review root, call the real `reviewViewModel.onSettled(0)`, wait until lookup starts, click another production navigation item, release lookup, then assert:

```kotlin
assertEquals(1, playback.deactivations)
assertEquals(0, playback.settles)
assertEquals(0, playback.plays)
```

Return to Review, disable suspension, settle again and assert one new settle/play. Temporarily remove `settleScheduler.reset()` to prove this test fails, then restore it.

- [ ] **Step 5: Run focused GREEN and protocol checks**

```powershell
Set-Location android
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "*MutationInvalidationViewModelTest" --rerun-tasks
.\gradlew.bat --no-daemon :app:assembleAndroidTest --rerun-tasks
Set-Location ..\server
$env:MEDIAREVIEW_DATA_ROOT = Join-Path $env:TEMP 'mediareview-task-a'
.\.venv\Scripts\python.exe -m pytest tests\test_delete_fav_service.py tests\test_phase456_api.py
```

Expected: all pass; Server tests confirm `success` is canonical.

- [ ] **Step 6: Run full gate, independent review, and commit**

Run the Server and Android full commands in `HANDOFF_STATUS_2026-08-30.md`, then `git diff --check`. Execute instrumentation on an emulator/device if available; otherwise write “APK built, not executed.” Generate a frozen diff and require a new independent reviewer conclusion of CLEAN before marking Task 4 complete.

Suggested commit: `fix(android): close task 4 deletion and shell gates`

---

### Task B: Paging 3 媒体墙、图片与雪碧图闭环 (`1.1.0-alpha2`)

**Files:**
- Modify: `android/gradle/libs.versions.toml`, `android/app/build.gradle.kts`
- Create: `android/app/src/main/java/com/mediareview/app/feature/mediawall/MediaQuery.kt`
- Create: `android/app/src/main/java/com/mediareview/app/feature/mediawall/MediaPagingSource.kt`
- Modify: `MediaWallViewModel.kt`, `MediaWallScreen.kt`, `ImageViewerViewModel.kt`, `ImageViewerScreen.kt`, `SpriteViewModel.kt`, `SpritePreviewUi.kt`
- Modify: `server/app/api/v1/media.py`, `server/app/services/sprite.py`, `server/app/api/v1/tasks.py`
- Test: Android paging/MockWebServer/Compose tests and Server media/sprite/task tests

**Interfaces:**
- `data class MediaQuery(libraryId, type, sortBy, sortOrder, search, excludeFavorites, folderId)`.
- `MediaPagingSource.load(params): LoadResult<Int, MediaSummary>` maps Server `page/page_size/total/sync` without holding all rows.
- Sprite generation returns `202 {task_id,status}`; task detail/cancel uses existing `/api/v1/tasks/{id}` contract.

- [ ] **Step 1: Add Paging 3 dependencies and RED tests** for first page, append, empty page, 401, 500, timeout, cancelled stale query, and out-of-order responses. Expected RED: no `PagingSource` exists.
- [ ] **Step 2: Implement immutable `MediaQuery` and `MediaPagingSource`**. A new query must create a new Pager; old flow is cancelled with `flatMapLatest`; `cachedIn(viewModelScope)` is the only retained page cache.
- [ ] **Step 3: Replace manual list/page state with `LazyPagingItems`** while preserving 2/3/4/5 columns; video/image/mixed and library filters; debounced search; time/name/size/duration/resolution/random sorts; unliked and unprocessed modes; skeleton/empty/offline/error/retry states; and current design tokens.
- [ ] **Step 4: Add file-folder auxiliary mode** under Media, not bottom navigation. Folder identifiers are server IDs, never Windows paths.
- [ ] **Step 5: Close image behavior** with authenticated thumbnail/original endpoints, decode-size calculation from viewport, retry, zoom/pan clamps, and cancellation when leaving the viewer.
- [ ] **Step 6: Close sprite tasks**: idempotent generate, progress, cancel, source-size/source-modified invalidation, cache outside media directories, long-press scrub mapping `[0f,1f]` to frame/time, immediate cover restore on release.
- [ ] **Step 7: Verify** 100k server page query without Jellyfin scan; Android heap never contains 50k DTOs; focused tests and full gates pass; independent review CLEAN.

Suggested commit split: `feat(android): page media wall`; `feat(media): close image and sprite flows`.

---

### Task C: Direct Play 与单次 HLS 回退 (`1.1.0-beta1`)

**Files:**
- Modify: `server/app/adapters/jellyfin/client.py`, `server/app/api/v1/media.py`, `server/app/core/config.py`
- Modify: `android/app/src/main/java/com/mediareview/app/core/model/ApiModels.kt`
- Create: `android/app/src/main/java/com/mediareview/app/feature/player/PlaybackStateMachine.kt`
- Modify: `PlayerViewModel.kt`, `PlayerScreen.kt`, `PlayerCore.kt`, `MediaRepository.kt`, `MediaReviewApi.kt`
- Test: playback serialization/security, URL resolver, state machine, Media3 error mapping, progress tests

**Interfaces:**

```json
{
  "direct": {"url":"...","headers":{}},
  "fallback_hls": {"url":"...","headers":{}},
  "duration_ms": 0,
  "resume_position_ms": 0,
  "stream_url": "legacy-one-version"
}
```

No response may contain the Server's long-lived Jellyfin API key. If Jellyfin requires authentication, implement a revocable, scoped client credential contract; do not proxy the full stream through FastAPI.

- [ ] **Step 1: Write Server RED security/serialization tests** for direct/HLS URLs, required headers, no server key in complete JSON/URL/error, loopback rewrite, unsupported media, and legacy field compatibility.
- [ ] **Step 2: Write Android state-machine RED tests** for Direct success, Direct datasource/container/decoder error → exactly one HLS transition, HLS error → terminal Chinese error, cancellation/switch media, and no fallback loop.
- [ ] **Step 3: Implement playback DTO and scoped credential strategy** with revocation and expiry documented; adversarially test URL userinfo/query/fragment/redirects.
- [ ] **Step 4: Implement Media3 source creation and state machine**. Preserve final progress report before backgrounding, switching media, retrying, or leaving.
- [ ] **Step 5: Complete controls**: play/pause, seek, single-tap chrome, double-tap ±10s, horizontal seek, left brightness, right volume, speed, fit mode, subtitles, audio tracks, rotation and lock. Every action has a Material icon and Chinese accessibility description.
- [ ] **Step 6: Verify format matrix** on emulator where possible, then real phone: MP4/H.264/AAC, MKV/HEVC, 1080p, 4K, portrait, multiple audio, embedded subtitles, transcode-only. Target Direct start `<3s`; HLS `<8s` or explicit error.
- [ ] **Step 7: Run full Server/Android gates and independent security review**. Do not enter Task D until no server credential can be serialized and state machine review is CLEAN.

Suggested commits: `feat(server): add secure playback contract`; `feat(android): add direct hls state machine`; `feat(android): complete player controls`.

---

### Task D: 批阅、收藏、安全删除与重复整理 (`1.1.0-beta1`)

**Files:**
- Modify: Android `feature/review`, `feature/favorites`, `feature/deletequeue`, `feature/duplicates`
- Create: `server/app/db/migrations/versions/0013_duplicate_groups.py`
- Modify: `server/app/db/models.py`, `services/delete_queue.py`, `services/duplicate_scanner.py`, `services/tasks.py`
- Modify: delete/duplicates/review APIs and tests

**Interfaces:**
- `POST /api/v1/delete-queue/commit/prepare` returns a single-use nonce, expiry UTC, count, bytes and media IDs summary; never paths.
- `POST /api/v1/delete-queue/commit` accepts only nonce and returns per-media `success|missing|failed`.
- Duplicate scan is a persisted background task with pause/cancel/resume/progress; no automatic deletion.

- [ ] **Step 1: Write RED tests** for settled-only playback, P0/P1 bandwidth arbitration, absolute index restore, seen uniqueness, idempotent favorite/delete/undo/progress, commit nonce expiry/reuse/tamper, per-item continuation, file identity recheck and audit.
- [ ] **Step 2: Finish review window behavior**: mixed images/video, stable pager, current P0/next P1, stop P1 while P0 buffers, portrait page keeps landscape video centered, failed delete stays on current item.
- [ ] **Step 3: Keep favorite state consistent** across Media/Player/Review/Favorites via the validated revision graph and successful mutation responses only.
- [ ] **Step 4: Implement two-phase permanent deletion**. Re-resolve media ID server-side, verify selected library, queue state, fingerprint and current file identity, execute each item independently, write audit for every item, reject client paths.
- [ ] **Step 5: Add migration `0013_duplicate_groups`** with `down_revision="0012"`. Cover old DB upgrade, rollback, failed migration rollback and backup restore. Never rename/rewrite 0012.
- [ ] **Step 6: Persist duplicate groups/members/fingerprints/tasks**. Exact uses size + duration + segmented quick fingerprint + combined SHA-256, with optional full SHA-256 before deletion. Suspected uses duration/size/resolution only; no pHash in 1.1.
- [ ] **Step 7: Build dual-column human comparison** and explicit keep selection. Exact and suspected duplicates never auto-delete.
- [ ] **Step 8: Full gates and destructive-safety independent review** must be CLEAN before any real-file test; real deletion test uses disposable fixtures only.

Suggested commits: `feat(review): close review consistency`; `feat(delete): add nonce commit`; `feat(duplicates): persist scan groups`.

---

### Task E: Windows 运维控制台

**Files:**
- Split/modify: `server/app/admin.py` into focused templates/static/controller modules if needed
- Modify: system, pairing, libraries, tasks, cache and duplicates APIs
- Test: auth, CSRF/origin, redaction, dangerous confirmation, task cancellation, diagnostic export

**Interfaces:** All panels consume the same `/api/v1` services as Android; LAN admin calls require paired credentials, while loopback policy remains explicit and tested.

- [ ] **Step 1: Write RED tests** for unauthenticated LAN denial, loopback behavior, device revoke, dangerous confirmation, token/key/path redaction, safe task cancel and diagnostic export.
- [ ] **Step 2: Implement status dashboard**: service/Jellyfin, LAN address/port/version, libraries, index count, last sync and stale/error state.
- [ ] **Step 3: Implement operations**: pairing code, device list/revoke, refresh, background/sprite/duplicate progress and cancel, cache usage/policy/clear.
- [ ] **Step 4: Implement recent redacted errors, log download and diagnostic ZIP**. Serialized output receives adversarial secret scanning.
- [ ] **Step 5: Verify at 360/desktop widths and with keyboard-only operation**; no media wall; full Server gate and independent security review CLEAN.

Suggested commit: `feat(admin): add operations console`.

---

### Task F: Windows 部署、升级、回滚与产物 (`1.1.0-rc1`)

**Files:**
- Modify: `server/packaging/mediareview_server.spec`, `scripts/build_deploy.py`
- Modify/create: `deployment/scripts/install.ps1`, `start.ps1`, `stop.ps1`, `restart.ps1`, `status.ps1`, `repair.ps1`, `diagnose.ps1`, `uninstall.ps1`
- Create/update: install, upgrade, rollback, troubleshooting, version and release docs

**Interfaces:** Final package root is `MediaReview_Migration_1.1.0`; executable is exactly `MediaReviewServer.exe`; uninstall preserves data unless explicit `-DeleteData`.

- [ ] **Step 1: Write PowerShell/package RED tests** for Windows PowerShell 5.1 compatibility, administrator check, port conflict, disk, Jellyfin, prior version, exact EXE name, FFmpeg copy/priority, backup, rollback, TCP 8766 + UDP 35001 firewall and uninstall data preservation. If PowerShell 7 becomes mandatory instead, add an explicit prerequisite check and supported acquisition path; do not depend on Codex runtime.
- [ ] **Step 2: Build self-contained Server EXE** and bundle `ffmpeg/bin/ffmpeg.exe` plus `ffprobe.exe`; before redistribution, record source/version/SHA-256 and complete license review with project `LICENSE` and `THIRD_PARTY_NOTICES.md`. Startup logs resolved paths and versions without secrets.
- [ ] **Step 3: Implement user controls** start/stop/restart/status with exact process ownership; do not kill unrelated listeners.
- [ ] **Step 4: Implement transactional upgrade**: stop owned service, online-backup config/DB, stage new files, migrate, health-check, atomically promote; on failure restore old binaries/config/DB and restart old version.
- [ ] **Step 5: Configure only TCP 8766 and UDP 35001 firewall rules** after revalidating AI Home port registry; do not change ports automatically.
- [ ] **Step 6: Build signed Release APK** with application name `家庭媒体管家`, version `1.1.0`, versionCode greater than 5, adaptive icon and upgrade-compatible applicationId. Pull the currently installed `com.mediareview.app` APK from the real phone and compare `apksigner --print-certs`; only matching fingerprints prove an in-place upgrade. If the original signing key is unavailable, stop and ask the user whether to accept uninstall/reinstall and possible local-data loss.
- [ ] **Step 7: Assemble package and SHA-256 checksums** with server, ffmpeg, web, eight scripts, APK and docs. Scan for secrets/malware and verify every checksum after extraction.
- [ ] **Step 8: Clean-machine install/upgrade/rollback/uninstall test** on a disposable Windows environment; full evidence and independent deployment review CLEAN.

Suggested commits: `feat(deploy): add transactional 1.1 upgrade`; `build(release): assemble rc1 artifacts`.

---

### Task G: 全量验收与正式发布

**Files:** update `docs/ACCEPTANCE.md`, release/test/performance reports, checksums and final handover; no new feature work.

- [ ] **Step 1: Run complete automated gate from clean checkout**: Server pytest/ruff/format/performance; Android JVM/instrumentation/Debug/Release/lint; migration upgrade/rollback; deployment contract; `git diff --check`.
- [ ] **Step 2: Back up production config and DB without plaintext secrets** and perform an upgrade/rollback rehearsal. Any failure restores the prior service and stops the release.
- [ ] **Step 3: Validate real 56k/100k behavior**: cached pagination P95 `<1s`, DB query target `<250ms`, refresh response `<500ms`, no list-time Jellyfin scan, sync failure leaves stale cache browsable.
- [ ] **Step 4: Validate real phone connection**: UDP discovery, manual IP, repeated pairing single record, restart reconnect, revoke/re-pair, no loopback media URL, Wi-Fi switch recovery.
- [ ] **Step 5: Validate media and organization**: image, sprite, Direct/HLS formats, review P0/P1, favorite consistency, nonce delete on disposable files, duplicates, server/Jellyfin/Windows restarts.
- [ ] **Step 6: Accessibility and layout**: 360×740, 390×844, 740×360, font scale ≥1.3, TalkBack, touch targets and Chinese labels.
- [ ] **Step 7: Final independent branch review**. Fix all Critical/Important, regenerate artifacts from the reviewed commit, verify checksums, tag `1.1.0` only after real phone gate passes.
- [ ] **Step 8: Deliver** `家庭媒体管家-1.1.0.apk`, `MediaReview_Migration_1.1.0.zip`, checksums, install/upgrade/rollback/troubleshooting, API/architecture, test/performance/phone reports and redacted diagnostic sample.
- [ ] **Step 9: Send the APK through Hermes** exactly as required by `docs/HERMES_APK_EMAIL_DELIVERY.md`: confirm recipient, hand files through AI Home shared, explicitly notify/wake Hermes and obtain receipt, require fixed non-image MIME/size/refusal/fail-closed behavior, and accept success only with SMTP accepted plus exact attachment filename, actual attached byte count and matching SHA-256. Never store mailbox credentials in the repository. If execution is outside AI Home, return the verified artifacts to AI Home for Hermes delivery instead of inventing SMTP.

---

## Final self-review checklist for the next Agent

- [ ] Every original requirement maps to Task 0A and A–G.
- [ ] No task contains placeholder behavior or an invented API result.
- [ ] Android delete outcomes exactly match Server `success|missing|failed`.
- [ ] Migration after 0012 is a single linear head.
- [ ] Server key never reaches Android or serialized diagnostics.
- [ ] FastAPI never becomes a full video proxy.
- [ ] Current HEAD is not called CLEAN until a new independent report says so.
- [ ] Build-only, emulator and physical-device evidence are reported separately.
