# MediaReview Server 版本策略（SERVER_VERSION_POLICY）

> 生效时间：Stage 8D.1（2026-10）。
> 本文档定义 **Server 版本号** 与 **API Contract** 的分工与升级规则。
> Android 侧的版本规则见 `docs/VERSION_POLICY.md`。

## 1. 两个数字，各司其职

| 名称 | 字段 | 用途 |
| --- | --- | --- |
| Server Version | `app.__version__` / health `version` | 给人看：部署、回滚、排查、更新说明 |
| API Contract | `app.SERVER_API_CONTRACT` / health `api_contract` | 给 App 判断兼容性 |

**硬规则**：App 只依据 `api_contract` 判断兼容，**绝不**写
`if (version == "1.2.0")` 之类的字符串硬判断。

## 2. API Contract 规则

- 当前值：`SERVER_API_CONTRACT = 2`（Android `REQUIRED_SERVER_API_CONTRACT = 2`）；
- 递增时机：**新增/修改 App 依赖的接口语义**（例如新的 Organize API、分页合同变化）；
- 不递增：只修 Bug、性能优化、日志、内部重构；
- 因此 `1.2.0 → 1.2.1 → 1.2.2` 可以一直保持 `api_contract = 2`，
  不强制 App 升级。

## 3. Capabilities

Health 同时返回 `capabilities`（本契约下明确提供的能力清单）：

```text
review_session
review_nearest
organize
delete_nonce
duplicates_paged
library_selection
```

`capabilities` 供**未来渐进功能判断**；当前兼容 Gate 仍以 `api_contract` 为主，
**不实现**复杂 Feature Flag 系统。

## 4. Health 响应合同

```json
{
  "status": "ok",
  "service": "...",
  "version": "1.2.0",
  "instance_mode": "...",
  "lifecycle_target": "...",
  "components": { "database": "ok", "jellyfin": "configured" },
  "api_contract": 2,
  "capabilities": ["review_session", "..."]
}
```

旧 Server 缺少 `api_contract` / `capabilities`：App 兼容读取为
`api_contract = 1`、空清单，**不崩溃**，并判定为"版本过旧"（Incompatible）。

## 5. 版本历史

### 1.2.1（Stage 8D.2 / 2026-10）
- **只修 Bug，API Contract 保持 2**（正是本策略要验证的"版本与 Contract 分离"）；
- 部署脚本引入版本 + Contract Gate：安装/升级后的 Server 必须
  `version == 1.2.1` 且 `api_contract >= 2` 且能力清单齐全，否则视为部署失败
  （升级自动回滚、首次安装标记 INSTALL FAILED）。

### 1.2.0（Stage 8D.1 / 2026-10）
- 新增 `api_contract = 2` 与 `capabilities`（Health）；
- Review Session V2、Organize APIs、duplicate pagination/detail、
  stable duplicate identity（`exact:<digest>` / `high:<digest>`）自 1.1.0 起已提供，
  本次通过 Contract 2 正式对外承诺；
- 旧序号 group_id → 稳定 ID 的 Keep 安全迁移（Server 侧运行时迁移，无 schema 变更）。

### 1.1.0
- Review Session / Organize / duplicate pagination / stable identity 等能力实现，
  但未通过 Contract 字段对外承诺（App 只能靠版本号猜测）。这是本阶段要修复的核心问题。

## 6. 与部署的关系

Server 版本号同时用于部署包命名（`MediaReview_Migration_<version>`）与
`CURRENT_VERSION` 标记，见 `UPGRADE_ROLLBACK.md`；升级/回滚不改变 API Contract 语义时，
App 无需重新配对。
