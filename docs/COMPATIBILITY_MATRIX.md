# MediaReview 兼容性矩阵（COMPATIBILITY_MATRIX）

> 每次协议升级更新本表。判定字段：**API Contract**（不是版本号字符串）。
> 规则见 `docs/SERVER_VERSION_POLICY.md`。

## 1. 当前矩阵

| Android | 要求 Contract | Server（示例） | 结果 |
| --- | --- | --- | --- |
| ≤ alpha4（code 11） | 无 Gate（旧） | 1.1（无 `api_contract`） | 直接尝试业务 API（可能 404） |
| alpha5（code 12） | **2** | 1.1（`api_contract` 缺失 → 视为 1） | **Incompatible**（提示"服务器版本过旧"，不清 Token） |
| alpha5（code 12） | **2** | 1.2.0（`api_contract = 2`） | Compatible |
| alpha5（code 12） | **2** | 未来 1.x（`api_contract ≥ 2`） | Compatible（向前兼容） |
| alpha6（code 13） | **2** | 1.2.0 / 1.2.1（`api_contract = 2`） | Compatible |
| alpha15（code 22） | **2** | 1.5.2（Contract 2，13 能力） | Compatible；不显示本地删除能力 |
| alpha16（code 23） | **2** | 1.5.2（Contract 2，缺 `native_delete_v1`） | 基础功能 Compatible；本地删除 fail-closed 提示升级 Server |
| alpha16（code 23） | **2** | 1.6.0（Contract 2，14 能力） | Compatible；仅显式 `can_delete=true` 的来源可删 |
| alpha17（code 24） | **2** | 1.5.2（Contract 2，缺 `native_delete_v1`） | 基础功能 Compatible；本地删除 fail-closed 提示升级 Server |
| alpha17（code 24） | **2** | 1.6.0（Contract 2，14 能力） | Compatible；UI 重构不改变协议，仅显式 `can_delete=true` 的来源可删 |

> Stage 8D.2：`1.2.0` 与 `1.2.1` **都属于 Contract 2**（1.2.1 只修 Bug，不升 Contract），
> 因此 alpha5 / alpha6 对两者都判定 Compatible。

> P5/Alpha17：1.5.2 与 1.6.0 仍同属 Contract 2。Alpha16/Alpha17 都不通过版本字符串放开删除；缺 `native_delete_v1` 或缺少/否定 `can_delete` 时均 fail-closed。

## 2. 行为矩阵

| 场景 | App 行为 |
| --- | --- |
| 新 App + 旧 Server | 禁止进入 Server Ready：health 判定 Incompatible，**不**继续请求业务 API（fail-fast）；设置页提示"服务器版本过旧"；**不清 Token** |
| 新 App + 新 Server | 正常：health → 兼容 → pairing / jellyfin / media |
| 旧 App + 新 Server | Server 1.2 保留 1.1 的基础接口（health / pairing / jellyfin / media / review / organize / duplicates / delete queue），旧 App 的既有调用路径继续可用；Server 只**新增** `api_contract`/`capabilities` 字段，不改旧字段语义 |

> 诚实声明：旧 App（≤ alpha4）无法识别 `api_contract`（忽略未知字段），
> 因此它对 1.2 Server 的行为与对 1.1 相同 —— 只要 Server 不删除/改变旧接口语义，
> 旧 App 继续可用。本阶段 Server **未**删除任何旧接口。

## 3. 升级路径

```text
Server 1.1 → 1.2    : App(alpha5) 由 Incompatible 变为 Compatible，Token 继续可用，无需重新配对
Server 1.2 → 1.2.x  : api_contract 不变，App 无感知
Server 1.2 → 1.5.2  : api_contract 保持 2，旧 App 保持基础兼容
Server 1.5.2 → 1.6.0: api_contract 保持 2；Alpha16/Alpha17 通过 capabilities + can_delete 渐进开启本地删除
Server 1.x → 2.0    : 若递增 api_contract，需同步提升 App REQUIRED_SERVER_API_CONTRACT
```
