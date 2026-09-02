# Task E Windows 运维控制台独立安全审查报告

- 审查对象:Task E git diff(8acb7f4..HEAD,分支 feature/mediareview-1.1)
- 审查性质:对抗性运维安全(ops-security)审查——认证边界、危险操作确认、敏感数据脱敏、
  诊断导出、键盘可访问性、无媒体墙
- 审查日期:2026-09-02
- 工作树状态:提交前确认(见第 4 节)

---

## 1. 审查范围与方法

### 范围(全部为本阶段已提交/待提交改动)
- 服务端:server/app/admin.py(运维控制台单页,全量重写)、
  server/app/api/v1/system.py(dashboard 聚合状态 / cache clear 危险确认 / errors / logs
  脱敏读取;info/storage/diagnostics 既有接口核查)
- 服务端测试:server/tests/test_ops_console_11.py(新增 21 例)、
  server/tests/test_system_api.py(管理后台标题断言更新)
- 为上下文额外阅读:auth.py(require_auth / require_localhost_or_auth 全文)、
  pairing.py(配对码回环限制全文)、tasks.py、cache.py、libraries.py、duplicates.py、
  media.py 的认证依赖、media_index.py 的 `library_selection_rows`/`media_sync_view`、
  sprite.py 的 `invalidate_manifest`、既有 test_stage7_fix.py 的诊断导出认证用例。

### 方法
1. `git diff 8acb7f4 --stat` 确认改动面。
2. 全文通读 admin.py 与 system.py(非仅看 diff),逐面板核对 JS 调用的每个 API 端点
   是否存在、认证依赖是否正确、危险操作是否二次确认、输出是否脱敏。
3. 检索服务端全部 `os.remove/unlink/rmtree`,确认缓存清理只触碰 cache/ 下可再生成目录。
4. 检索测试是否使用真实媒体目录、是否伪造"全通过"。
5. 检索仓库内禁止工件。
6. 实际运行三项强制验证命令(见第 4 节)。

---

## 2. 运维安全清单逐项裁决

### 2.1 未认证 LAN 拒绝 + 回环放行(localhost-or-auth)— CLEAN
- `/admin` 页面与 `/system/dashboard`、`/system/storage`、`/system/info`、
  `/system/diagnostics/export`、`/system/cache/clear`、`/system/errors`、`/system/logs`
  全部经 `require_localhost_or_auth`(`system.py:155/170/192/276/346/383/393`,`admin.py:537`)。
  `auth.py:47-57`:host=`::1` 或 `127.*` 放行,其余走 `require_auth`(Bearer token 解析
  `token_hash`,失败 401;pairing_required=False 开发模式放行)。
- 测试实测:未配对局域网设备访问 dashboard / errors / logs / cache clear / `/admin`
  全部 401(`test_ops_console_11.py` 多个 `_remote_denied` 用例);回环包装可全量访问。
- 结论:无"对局域网裸奔"的敏感接口。

### 2.2 危险操作二次确认(缓存清理/撤销设备/清理已用码/取消任务)— CLEAN
- **服务端硬门槛**:`POST /system/cache/clear` 必须携带 `confirm=true|1`,否则 400 拒绝
  (`system.py:354-355`)。`false/0/yes/no/空` 全部拒绝(`test_cache_clear_rejects_wrong_confirm`
  表驱动实测)。这既防误触,也构成对"无确认客户端/脚本"的 CSRF 式防护。
- **UI 二次确认**:admin.py 使用原生 `<dialog id="confirmDlg">`,`askConfirm()` 在调用
  清理缓存/撤销设备/清理已用码/取消任务前弹出明确中文确认,`确认执行/取消` 双按钮
  (`admin.py:232-256`)。`onclose` 兜底默认按取消,`confirmSettled` 防重复 resolve。
- **撤销设备**不经服务端额外 confirm(经 Bearer 认证即可,符合既有配对合同;
  UI 仍二次确认)。
- 结论:危险操作在 UI 与服务端双层确认,无单次点击即执行破坏性动作的路径。

### 2.3 敏感数据脱敏(对抗式密钥扫描)— CLEAN
- `system.py:31-48` `_SENSITIVE_PATTERNS` 覆盖:项目 bearer token(`mr_` 前缀)、
  Authorization Bearer 明文、Jellyfin API Key 多种形态、JSON 字段 `api_key`/`code`、
  `code=6 位`、Windows 盘符绝对路径、UNC 路径、POSIX 挂载点路径。
- `_recent_log_lines`(`system.py:114-133`)在 `/errors` 与 `/logs` 输出前逐行脱敏;
  `diagnostics_export`(`system.py:261-262`)对日志先 `_mask_log_text` 再入 ZIP;
  `config.masked_dict()` 不含真实 api_key。
- 对抗式测试 `test_adversarial_secret_scan_on_all_serialized_output`:向 DB/日志注入
  token、api_key、配对码、媒体绝对路径,断言 dashboard / errors / logs / 诊断 ZIP
  四路输出全部不含任何敏感值(`test_ops_console_11.py:271-293`)。
- `/admin` 页面本身为静态 HTML,无服务端渲染的动态内容(数据全部经鉴权 JS 拉取),
  `test_admin_page_redacts_no_secrets` 实测页面不含注入敏感值。
- 结论:所有序列化输出经对抗扫描,无 token/api_key/配对码/绝对路径外泄。

### 2.4 诊断导出与日志下载安全 — CLEAN
- 诊断 ZIP 仅含:脱敏 `system_info.json`、`storage.json`、白名单表计数
  (`system.py:236-255`,表名来自固定元组,S608 加 noqa 且无用户输入拼接)、脱敏日志。
  不含真实 DB、配置密钥、媒体原文件。
- 日志下载为纯文本脱敏行(`PlainTextResponse`),`Content-Disposition` 仅固定文件名。
- 既有 `test_diagnostics_export_masks_sensitive_logs` 与新增对抗扫描双重覆盖。

### 2.5 缓存清理范围(绝不触碰 DB/配置/媒体原文件)— CLEAN
- `cache_clear`(`system.py:356-376`)只遍历 `paths.cache_dir / {thumbnails,sprites,
  previews,temp}` 下的文件(`rglob` + `is_file()`),逐项 unlink;随后对 ready 雪碧图
  清单调 `sprite.invalidate_manifest` 同步失效,避免"清单 ready 但文件缺失"。
- `test_cache_clear_with_confirmation_clears_cache_only` 实测:DB、媒体原文件、config
  目录在清理后全部保留,仅 cache/ 下文件被移除。
- 检索确认服务端唯一媒体原文件删除点仍在 delete_queue 非ce 提交路径(既有 Task D
  结论),本阶段未新增任何对媒体原文件的删除代码。

### 2.6 配对码生成回环限制 — CLEAN
- `/pairing/code`(`pairing.py:97-111`)默认仅本机回环可生成(远程 403),需
  `security.pairing_code_remote_allowed=True` 才开放——配对码生成入口未被运维控制台
  放开给局域网。
- 控制台"本机自动配对"按钮(`admin.py:487-499`)只在回环本机可用(生成码+verify 自配对),
  失败提示"仅回环本机可用"。LAN 场景走顶部令牌输入。
- 结论:未破坏"一次配对码"安全模型。

### 2.7 键盘可操作 + 无媒体墙 — CLEAN
- 全部交互为原生 `<button>/<input>/<a>` 与 `<dialog>`;全局 `:focus-visible` 样式
  (`admin.py:65`);测试断言无 `onclick="` 伪按钮、含 button/input/a 与 focus-visible
  (`test_admin_page_keyboard_accessible`)。
- 页面仅六个运维面板(状态总览/配对与设备/媒体库与索引/缓存管理/后台任务/错误与日志),
  测试断言无"媒体墙"/media-grid/media-card,响应式 `grid-template-columns:
  repeat(auto-fit,minmax(min(100%,340px),1fr))` + 480px 断点适配 360px 移动宽度。
- 结论:符合"Windows 后台只做运维,不复制 Android 媒体墙"与键盘全操作约束。

### 2.8 测试诚实性 + 禁止工件 — CLEAN
- 所有缓存/日志测试使用 `tmp_path` 一次性夹具与临时数据根,未触碰真实媒体/生产数据。
- 本审查实际运行并记录真实命令输出(见第 4 节):Server 全量 326 passed、ruff check
  All checks passed、ruff format 93 files already formatted,无伪造"全通过"。
- `git ls-files` 按禁止工件模式检索无 APK/build/.tmp/.env/密钥/DB/log 被跟踪。

---

## 3. 发现清单

| # | 级别 | 位置 | 说明 | 建议 |
|---|------|------|------|------|
| M1 | M=Minor | server/app/api/v1/system.py:114-133 | `_recent_log_lines` 对 `*.log` 逐个取尾部 512KB 再拼接,"最近 N 行"可能混入非主日志文件的旧内容;且 `levels` 为子串匹配(`"ERROR" in raw`),含 "SERVER_ERROR" 之类的行也会被算入错误 | 按 mtime 取最新日志文件、改用严格级别前缀匹配 |
| M2 | M=Minor | server/app/api/v1/system.py:96-111 | `_lan_ipv4()` 用 UDP `connect(("8.8.8.8",80))` 取本地出站地址(不发包,仅查路由表);依赖外网可达性假设,离线/无默认路由时可能取不到地址 | 可改为主机名 getaddrinfo 优先或读取本机路由表;属可用性而非安全 |
| M3 | M=Minor | server/app/admin.py:487-499 | "本机自动配对"每次点击以 `console-<随机>` 新设备 ID 自配对,重复点击会在设备表累积 `console-*` 记录 | 复用/轮换固定 console 设备 ID 或在配对后清理旧 console-* 记录 |

另附非问题备注(记录为 CLEAN 佐证,不需整改):
- `/admin` 使用 Bearer 头(非 Cookie)鉴权,FastAPI 未开 CORS,跨源站点无法带
  Authorization 头发起状态变更请求——无 CSRF 面(服务端 `confirm` 门槛进一步兜底)。
- 令牌存 sessionStorage(标签页级,关闭即清),非 localStorage,持久化面最小。
- `/admin` 页面不缓存:数据全部经鉴权 JS 实时拉取,静态 HTML 无敏感值。

---

## 4. 已执行验证命令与真实输出

### 4.1 Server 全量测试(工作目录 server,隔离数据根)
```
MEDIAREVIEW_DATA_ROOT=<TEMP>/mediareview-e6-gate
.venv\Scripts\python.exe -m pytest -o addopts= -p no:cacheprovider -v
```
输出(末行,真实):
```
================= 326 passed, 1 warning in 195.82s (0:03:15) ==================
```
- 结果:326 passed、exit code 0(完整输出见 `review_meta/server_tests.txt`)。
- 注:`-o addopts=` 为关闭工程默认 `-q` 以在非 TTY 捕获计数行;`-q` 模式同集结果一致
  (三次全量运行均 exit 0)。

### 4.2 Server ruff(工作目录 server)
```
.venv\Scripts\python.exe -m ruff check .
```
输出(真实):
```
All checks passed!
```
- 结果:exit code 0,无告警(见 `review_meta/server_lint.txt`)。

### 4.3 Server ruff format(工作目录 server)
```
.venv\Scripts\python.exe -m ruff format --check .
```
输出(真实):
```
93 files already formatted
```
- 结果:exit code 0(见 `review_meta/server_format.txt`)。

### 4.4 辅助核验
- `git diff --check`:通过(仅 LF→CRLF 提示,无空白错误)。
- `git ls-files | grep -iE "apk|build/|\.tmp|\.env|key\.properties|jks|keystore|\.db$|\.log$"`:
  无禁止工件被跟踪。
- 服务端 `os.remove/unlink/rmtree` 检索:本阶段唯一删除点在 `system.py` 缓存清理
  (仅 cache/ 下可再生成文件);媒体原文件删除点仍为既有 delete_queue 非ce 路径。

---

## 5. 结论

**CLEAN(0 Critical / 0 Important / 3 Minor)**

无需关闭任何 Critical/Important 项。3 项 Minor 均不构成运维安全缺陷(无未认证访问、
无单步破坏性操作、无敏感值外泄、无媒体墙)。M1/M2 为日志展示可用性,可在后续阶段顺手
处理;M3 为控制台设备表累积,可选。建议进入下一阶段。
