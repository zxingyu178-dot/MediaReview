# MediaReview 2.0 — Stage 1 离线体验基线报告

> 日期：2026-09-19 ｜ 分支：`feature/mediareview-v2-stage1-offline`

## 一、完成了什么

**一个安装即可用的完全离线 Demo APK**：内置模拟媒体数据库、6 个本地测试视频、14 张本地测试图片、6 个文件夹与雪碧图测试资源，无需连接 Server / Jellyfin / 配对即可直接使用。

交付内容：

- 新首页：顶部大圆角搜索（页内展开，真实过滤 Demo 数据 + 最近搜索/历史/清除）
- 媒体 / 书架双模式：媒体模式固定双列卡片（圆角、轻阴影、时长胶囊、标题一行、次信息弱化）；书架模式文件夹即"书"（单封面/四宫格封面、项计数）
- 文件夹分类胶囊横滚：全部 / 最近 / 各文件夹 / 更多（Bottom Sheet 展示全部文件夹）
- 排序 / 筛选（Bottom Sheet）：最近添加 / 文件名 / 时长 / 大小 × 正序/倒序 × 全部/视频/图片，真实作用于 Demo 数据
- 文件夹内部页：返回 + 标题/计数 + 排序下拉 + 双列网格
- 雪碧图长按预览：长按卡片约 500ms（Android 系统长按阈值，见已知问题）进入预览，触觉反馈、卡片 1.03 缩放、左右拖动选时间、底部时间条 `00:14 / 00:32`、松手恢复；处理 DOWN/长按/MOVE/UP/CANCEL，滚动不误触发、移出卡片结束、松手恢复
- V2 播放器（Next Player 风格核心）：单击显隐控制层、双击左/右 1/3 快退快进 10s、双击中央播放暂停、左半屏上下滑亮度、右半屏上下滑音量、横向拖动 Seek（`+00:15 / 01:26/03:42`）、底部进度条、倍速、画面适应/填满、锁定（双击解锁）、返回
- 图片查看器：上下文队列（文件夹/排序/当前 index）、左右滑翻页（预加载前 1 后 2）、单击显隐 UI、双击 2x、双指缩放、放大后拖动、顶部编号/文件名、底部 ♡ 收藏 / ⓘ 信息 / 🗑 待删除（仅改本地 Demo State，不真删 APK 内资源）
- 底部主导航：首页 / 批阅（占位 "Stage 2"）/ 收藏（直接显示 Demo 收藏内容）/ 整理（待删除/重复媒体/已批阅/媒体库管理 Mock 卡片）

## 二、哪些是 Demo（临时）

- `feature/v2/data/DemoMediaCatalog.kt`：60 条 Mock 媒体（名称/编号/文件夹/日期/时长/大小/收藏/已批阅状态均为模拟）
- `feature/v2/data/DemoMediaRepository.kt`：内存态收藏/已批阅切换（不持久化，重启恢复初始）
- 内置媒体资源（`assets/demo_media`）：FFmpeg 合成测试内容
- 播放器/查看器的本地状态切换

## 三、哪些代码未来可直接复用（正式）

- **`MediaRepository` 接口**（`feature/v2/data/MediaRepository.kt`）：UI 只依赖该接口取数；接入真实 Server 时替换为 `MediaReviewServerRepository` 实现即可
- **UI 全部页面**：`HomeScreen` / `MediaCard` / `ShelfGrid` / `FolderScreen` / `V2PlayerScreen` / `V2ImageViewer` / `SearchPanel` / `SortFilterSheet` / 底部导航，均通过接口 + ViewModel 取数，无需重做
- 播放器手势层（`PlayerGestureDetector.kt`）、雪碧图预览（`SpritePreview.kt`）、缩放图片（`ZoomableImage`）为独立可复用组件
- `V2AppMode`（DEMO / PRODUCTION）切换机制：MainActivity 按模式进入 V2 或 1.1 流程

## 四、本地媒体来源

见 `docs/DEMO_MEDIA_SOURCES.md`：全部由 FFmpeg 内置公开测试源（testsrc2/smptebars/testsrc/mandelbrot/gradients/sine 等）本地生成，非商业影视内容，无版权与隐私风险。

- 6 视频：横 16:9 ×2、竖 9:16、短 10s、长 40s、近 4:3；10~40s；720p；H.264 + AAC（带音轨）
- 14 图片：横/竖/方/高清（2560×1440）+ 图案
- 6 雪碧图（webp + json manifest，每 2s 一帧 160×90，tile 5×5）
- 媒体总量 21.85 MB

## 五、APK 大小

`android/app/build/outputs/apk/debug/app-debug.apk`：**43.96 MB**（含全部内置媒体与资源）

## 六、测试结果

- `assembleDebug`：**BUILD SUCCESSFUL**
- `testDebugUnitTest`：**154 tests / 0 failures / 0 errors**（33 suites，含既有 1.1 测试全量回归）
- `lintDebug`：**0 errors**（26 warnings，均为既有项目级提示）

## 七、已知问题

1. 雪碧图长按触发使用系统标准长按时长（`detectDragGesturesAfterLongPress` 无 250ms 可配参数，固定约 500ms）；需求 250ms 待后续自定义手势精确实现
2. 播放器双击中央判定为播放/暂停、左右 1/3 快退快进；单击有约 300ms 延迟（用于区分双击）
3. 图片查看器放大后拖动无边界回弹约束（可拖出画面边缘再松手回弹简化处理）；当前缩放/翻页冲突处理：缩放 >1 时禁用 Pager 滑动，双指捏合可缩放
4. 亮度调节直接改当前 Activity 窗口亮度（未持久化到系统设置）；音量走 STREAM_MUSIC
5. 收藏/待删除状态仅内存态，重启恢复初始 Demo 状态
6. 整理页"待删除/重复媒体"为 Mock 数字，未接真实数据
7. 未做真机/模拟器人工体验验收（本阶段门禁），需用户真机测试

## 八、Commit

`feat: establish MediaReview 2.0 offline interactive baseline`

（Commit SHA 见 Git 历史，提交后填写）
