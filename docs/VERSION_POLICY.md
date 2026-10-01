# MediaReview 版本管理规则（VERSION_POLICY）

> 生效时间：Stage 8C.1 起（2026-10）。
> 本文件是 Android 版本号与更新日志的**唯一规则来源**；开发阶段编号（8C / 8C.1 / 8D）
> 与产品版本号（2.0.0-alpha2 等）**分开管理，禁止混用**。

## 1. 版本号位置

- `android/app/build.gradle.kts` → `defaultConfig.versionCode` / `versionName`；
- 更新日志目录：`android/app/src/main/java/com/mediareview/app/feature/v2/releasenotes/`。

## 2. versionCode

- 每次定义为"新的 App 版本"的变更：`+1`；
- **只能递增，绝不能回退**；
- 小版本即使不生成用户 APK，源码中的 versionCode 也照常递增（下一次用户 APK
  可能从 8 直接升到 11，这是正常的）。

## 3. versionName

当前 2.0 开发期：

```text
2.0.0-alpha1
2.0.0-alpha2
2.0.0-alpha3
...
```

准备进入 Beta：`2.0.0-beta1`；正式发布：`2.0.0`。

禁止把开发阶段编号直接当版本名（如 `versionName = "8C.1"`）。

## 4. 版本与更新日志强制联动（自动 Gate）

> 每一次产品版本更新，必须同步更新 versionCode / versionName **并**在
> `ReleaseNotesCatalog` 中补充对应 `ReleaseNote`。

- `CurrentVersionHasReleaseNotesTest` 会校验 `BuildConfig.VERSION_CODE` 必须能在
  `ReleaseNotesCatalog` 中找到条目：改了版本号却忘记写更新日志 → **测试直接失败**；
- 更新日志面向真实用户，保持 3~6 条，禁止出现 Stage 编号 / Repository / DTO /
  Hilt / MockWebServer 等开发内部词（另有源码合同测试禁止 ✓ 等符号占位）。

## 5. 新版本首次启动行为

- App 启动读取 DataStore `last_seen_version_code`；
- 与 `BuildConfig.VERSION_CODE` 不一致 → 展示一次「本次更新」Sheet；
- **跨版本升级**（小版本不发 APK 时常见，如 9 → 11）：Sheet **一次性**展示
  `(last_seen, current]` 区间内**所有**未读版本的更新日志，但**始终只弹一个** Sheet，
  绝不逐版本连续弹窗（按 versionCode 升序、分区标注来源版本）；
- **首次安装**（`last_seen` 为 null）：只展示**当前版本**，不倾倒历史全部日志；
- 用户关闭后写入当前 versionCode（表示"到当前版本为止都已看过"），同版本再次启动不再弹出；
- 设置页「本次更新」**只展示当前版本**（与自动弹窗的多版本聚合区分开）。

## 6. 阶段 Handoff 要求

每次交接的 `00_HANDOFF.md` 固定记录：

```text
App Version Base: versionCode / versionName
App Version Head: versionCode / versionName
Version Bumped:   YES / NO
Release Notes Present: PASS / FAIL
```

大阶段用户 APK 文件名带版本，例如 `MediaReview_2.0.0-alpha3_stage8d.apk`。

## 7. Handoff 交付一致性（Stage 8C.2 §36~§39）

- Handoff **只能在最终 commit + push 之后**生成，顺序固定：
  代码 → 测试 → version bump → release notes → commit → push → FINAL HEAD →
  Handoff 文档 → git evidence → ZIP；
- 所有文档与文件名中的 short SHA **统一从 `git rev-parse HEAD` 取得**，
  禁止在文档里硬编码旧 SHA；
- 打包脚本必须执行 **Delivery Integrity Check**：校验
  `00_HANDOFF` 的 Head、ZIP 文件名中的 short SHA、`git/LOG.txt` 首行三者一致，
  不一致即 `PACKAGING FAIL`，不得产出交付包。