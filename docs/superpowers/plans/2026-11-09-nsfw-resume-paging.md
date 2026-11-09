# 生图续审序号与双向分页实施计划

> **For agentic workers:** 按executing-plans逐项执行；NSFW项目明确要求的后端子任务交由现有子智能体，主Agent审查并统一验证/提交。

**Goal:** 继续上次恢复原范围内图片和序号，前后按需翻页且新图不打断。

**Architecture:** 复用只读gallery、原scope/watermark与双向keyset。后端新增previous_page_cursor/start_index和mobile_gallery_bidirectional_v1能力；手机按方向合并、保存当前图片ID与滚动锚点，范围已知删除后不继续显示过期精确序号。

**Tech Stack:** 现有Python/FastAPI/SQLAlchemy、Kotlin/Compose/协程，不新增依赖。

## Global Constraints

- 用户已确认完整恢复方案；不改审核/评分提交、原图缓存、数据库结构与服务生命周期。
- 不动真实媒体/审核/评分数据，不停生成，不部署；重型pytest/Gradle串行。
- 旧桥无新能力时保留原行为，明确已加载窗口；旧客户端继续使用现有next游标。
- 单页最多100张，手机复用50张；不为续审回放全部历史。
- 真实删除可改变仍存活范围，已知REMOVE后提示位置待校验，不用保存旧数字假装精确；重新查询/取页校验恢复计数。

### Task 1: 后端只读双向页（NSFW现有工作树）

文件：backend/app/services/mobile_gallery_service.py、backend/app/api/mobile/system.py、既有图库测试。

- [ ] 添加正反序/同时间戳/首尾/原scope新增隔离/删除计数最小回归，先确认RED。
- [ ] 实现previous_page_cursor与0起始start_index。page_cursor携带可选direction=previous；scope移除方向，保持旧scope/next兼容。反向取最近前页，按原顺序返回；双向页都返回正确前后游标。
- [ ] 仅运行受影响gallery目标；审查不写数据、不改鉴权/迁移、无BOM或敏感输出。

### Task 2: 手机恢复/合并/工具栏

文件：NsfwGalleryQuery.kt DTO，NsfwFeedStore.kt，NsfwReviewViewModel.kt，NsfwReviewScreen.kt，NsfwGallery.kt及对应既有JVM测试。

- [ ] 增加恢复中间序号、前页合并保持当前图、前后边界、失败重试/晚回包与旧桥用例，先RED。
- [ ] 增加新能力标志、previous cursor/total/start状态；前页只将新的合法ID放在前面，不覆盖更高revision/tombstone；分页保留另一端游标及当前图。
- [ ] 上一张/下一张在边界加载后再选择相邻图；图库前页显式入口，加载不自动换图；原LazyGrid稳定key/锚点保持。
- [ ] 有效全局序号使用start+currentIndex+1/total；已知范围删除时显示位置待校验；旧桥显示已加载窗口。
- [ ] 后端pytest完成后只跑受影响JVM/合同测试，编译Compose；不重跑全套或模拟器，不覆盖已批准候选发布指针。

### Task 3: 交付

- [ ] 更新两项目日志/状态及精简结果，列明测试命令与限制。
- [ ] 审查两端字段、游标和能力一致，无越界文件/新依赖/生产操作。
- [ ] 分别commit/push；Android需要手机验收时再制作单个签名候选，不称真机/LAN已通过。
