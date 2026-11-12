# 手机审核状态筛选实施计划

> For agentic workers: REQUIRED SUB-SKILL: subagent-driven-development or executing-plans，按任务验证。

**Goal:** 全库筛选未审核和排除淘汰，保持分页、恢复及同步一致。

**Architecture:** 复用status与selection/cursor；新能力门禁，旧桥保持现状。

**Tech Stack:** Kotlin Compose/JUnit，Python FastAPI/SQLAlchemy/pytest。

## 约束

不动真实媒体；不合并PR39；不覆盖Studio既有dirty文件；不新增服务或数据库；只跑相关验证。

## 任务

- [x] 后端子任务：图库status新增NOT_REJECTED及能力声明，先定向RED再实现GREEN，验证total、分页、条件绑定及状态变更。
- [x] 手机：NsfwGalleryQueryTest新增排除淘汰/序列化测试RED；NsfwGalleryQuery成员匹配、状态菜单与VM能力门禁实现GREEN。
- [x] 定向检查保存恢复和审核后成员移除，修复实际缺口，不改无关架构。
- [x] 复查diff，定向JVM测试与界面编译；更新结果/项目状态并提交手机代码，后端按规则交Lead、不commit。
- [ ] 匹配后端可用后再部署及实机验证；若部署被未合并PR39边界限制，如实交付可审查结果与待办，不声称功能已上线。
