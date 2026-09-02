# Task G7 · MediaReview 1.1 发布前独立对抗式审查报告

- 审查人：独立安全/质量审查员（只读审查，未修改任何源代码，未提交）
- 日期：2026-09-02
- 分支：`feature/mediareview-1.1`
- 审查基线 HEAD：`49d45c59c0e7ba520020fb1cc1ebac0bfbe9819b`（`49d45c5 fix(server): ignore env proxy for jellyfin client (trust_env=False)`）
- 工作区：仅 3 个 G 阶段文档未提交（`TASKS.md`、`docs/ACCEPTANCE.md`、`docs/DEV_LOG.md`），符合 G7 交付流程（审查报告写回后由主线程提交）。

---

## 1. 审查范围与方法

### 1.1 Git diff 范围

- 已提交 diff：`git diff 93613ab..HEAD --stat` → 3 文件，+29/-2：
  - `android/app/src/test/java/com/mediareview/app/BrandingResourceTest.kt`（版本断言 5/1.1.0-alpha2 → 6/1.1.0）
  - `server/app/adapters/jellyfin/client.py`（新增 `trust_env=False` + 中文注释）
  - `server/tests/test_jellyfin_client.py`（新增回归测试 `test_client_ignores_env_proxy_trust_env_false`）
- 未提交 diff：`git diff -- TASKS.md docs/DEV_LOG.md docs/ACCEPTANCE.md`（G 阶段文档，诚实记录受限/已知限制/未 tag 1.1.0）

### 1.2 方法

对抗式核验，重点不是“读文档结论”，而是实测：

1. **trust_env 行为实证**（不修改源码）：用 server venv（httpx 0.28.1 / Python 3.12.13）分别在「真实 transport」与「MockTransport」两种路径下、配合 `all_proxy=socks5://127.0.0.1:33210` + `HTTP_PROXY` + `HTTPS_PROXY` 环境变量，验证旧代码（trust_env=True）的失败机制与新代码（trust_env=False）的行为。
2. **发布就绪抽查**：`rg` 扫描 API key 序列化/URL/日志路径、视频代理/转发路径；`git ls-files` 路径级扫描禁止工件；核对 `.gitignore`、日志配置、uvicorn 访问日志。
3. **诚实性验证**（亲自运行，真实输出见 §4）：
   - `server`：`pytest tests\test_jellyfin_client.py -q` → 15 passed
   - `server`：`ruff check .` → All checks passed!
   - 项目根：`git diff --check` → exit 0
4. 未运行需要真实 Jellyfin/真实媒体的测试；未运行全量 pytest（主线程已留 `review_meta/g1_server_pytest.txt`：351 tests, exit 0, no failures，予以采信）。

---

## 2. 逐项裁决

### 2.1 trust_env=False 安全与行为影响 — **PASS（安全性提升）**

- **不引入 SSRF**：目标 Jellyfin 主机为管理员配置值；`trust_env` 只控制是否采信环境代理/环境 CA 变量，不改变 URL 解析与重定向策略（`follow_redirects=False` 仍为 `False`）。请求仍只发往配置的 Jellyfin 地址。
- **实际是安全收窄**：旧行为（trust_env=True）下，若部署机存在 `all_proxy`/`HTTP_PROXY` 等环境代理，httpx 会把携带服务端 API key 的 `Authorization` 头经该代理转发——存在经意外代理外泄凭据的面。`trust_env=False` 强制局域网直连，消除该面。V1 局域网单用户模型下方向正确。
- **行为回归**：局域网/回环直连 Jellyfin 不受影响；行为变为确定性（不再隐含依赖部署机环境变量）。**生产根因实测确认**：真实 transport + trust_env=True + `all_proxy=socks5` → `httpx.AsyncClient(...)` 构造期即抛 `ImportError: Using SOCKS proxy, but the 'socksio' package is not installed`（缺 socksio），与 DEV_LOG 描述一致，修复正确。
- **次要影响**：`trust_env=False` 同时禁用 `SSL_CERT_FILE`/`SSL_CERT_DIR` 环境变量 CA 信任。代码中无任何 `verify=` 覆盖，默认仍用系统信任库校验（verify=True），正常 HTTPS 公共 CA 证书不受影响；仅依赖环境变量注入自定义 CA 的部署会静默失效。V1 局域网范围可接受，建议在部署文档注明（见 Minor-2）。

### 2.2 回归测试有效性 — **PASS（有效；文档描述不精确，Minor-1）**

- 实测：**MockTransport + trust_env=True（旧代码）+ 代理环境变量 → 构造与请求均不抛 ImportError**（httpx 显式 transport 分支不构建代理 mount）。因此测试 docstring 所述“旧代码构造期抛 ImportError”机制在 MockTransport 路径下并不成立。
- 但测试断言 `assert jf._http.trust_env is False` 在旧代码（trust_env 默认 True）下必然失败 → **测试仍能有效捕获回归**。作为回归护栏有效，只是机制描述不准确。
- 建议：更正 docstring，或用真实默认 transport（trust_env=False 时构造必成功）强化。

### 2.3 版本断言一致性 — **PASS**

- `BrandingResourceTest.kt`：`versionCode = 6`、`versionName = "1.1.0"`。
- `android/app/build.gradle.kts`（L30-31）：`versionCode = 6`、`versionName = "1.1.0"`。一致。

### 2.4 API key 不进序列化/URL/日志 — **PASS**

- `app/api/v1/media.py`：playback 端点中 server_key 仅用于 `ensure_jellyfin_url_excludes_api_key(url, server_key)` 校验；下发的 `direct.headers`/`fallback_hls.headers` 携带的是设备级可撤销 `X-Emby-Token`（命名 key），不等于服务端 API key，且只进 headers 不进 URL。
- `app/adapters/jellyfin/client.py`：`video_stream_url`/`hls_stream_url` 构造后二次 `ensure_jellyfin_url_excludes_api_key` 复核；API key 仅存在于 `Authorization` header。
- `app/core/config.py`：`masked_dict()` 将 `api_key` 脱敏为 `"********"`，并将 `url`/`client_url` 也脱敏为 `"configured"`（连主机都不泄露）。
- 日志面：`client.py`/`media.py` **零 logger 调用**；`run_server.py` 设 `access_log=False`；日志格式仅含时间/级别/request_id/消息，无 URL/query/header 记录。

### 2.5 无视频流代理 — **PASS**

- 全仓 `proxy|forward|/stream` 扫描：唯一 `/stream` 为 `item_stream_url` 构造 Jellyfin 直连 URL 字符串（返回 URL，绝不转发字节流）；`PlaybackInfo` 仅下发直连 URL + headers；无任何流代理代码。

### 2.6 禁止工件未被跟踪 — **PASS**

- `git ls-files` 路径级扫描（含 `.apk/.aab/.exe/.keystore/.jks/.p12/.pfx/key.properties/.env/.sqlite/.db/.log/build/.gradle/dist/.venv/.pytest_cache/__pycache__/.dll/.so/.bin`）：**无匹配**。
- 跟踪文件中出现的 `key.properties`/`*.jks` 等字样均为文档/测试/脚本说明文字，非实际文件。
- `.gitignore` 覆盖完整：`.venv/ build/ *.apk *.aab *.exe *.jks *.keystore key.properties *.db *.env config.json secrets.env logs/ review_handoff/ dist/` 等。
- 非 md 跟踪代码文件无机器绝对路径（`E:\aihome\...` 仅出现在规则/交付文档）。

### 2.7 文档诚实性 — **PASS**

- `TASKS.md`：G1-G6 状态如实；G4/G6 明确标注「受限（无设备）」，并说明服务端对应能力由 pytest 覆盖；G5 记录真实 Jellyfin 冒烟（1967 条同步 + Direct/HLS 播放合同）与 `/Auth/Keys` 500 已知限制；明确「真机门未过 → 产物 rc1，不 tag 1.1.0」。
- `docs/ACCEPTANCE.md`：P0 逐条标注「服务端已 pytest/冒烟」vs「待真机」，设备依赖项保持未勾选，未虚报全过。
- `docs/DEV_LOG.md`：详细记录 G1-G6 与 trust_env 修复，明确 `/Auth/Keys` 500 为 known limitation，符合诚实原则。
- 全量测试证据 `review_meta/g1_server_pytest.txt`：`351 tests, exit 0, no failures`。

---

## 3. 发现清单

| 级别 | 位置 | 说明 | 建议 |
|---|---|---|---|
| C | — | 无 | — |
| I | — | 无 | — |
| M-1 | `server/tests/test_jellyfin_client.py:85-101` | 回归测试 docstring 声称“旧代码构造期抛 ImportError”；实测 MockTransport 路径下旧代码构造与请求均不抛错（httpx 显式 transport 分支不构建代理 mount）。断言 `assert trust_env is False` 仍能在旧代码下失败，测试有效，仅机制描述不精确。 | 更正 docstring；或用真实默认 transport 构造（trust_env=False 时必成功）强化测试。 |
| M-2 | `server/app/adapters/jellyfin/client.py:84-86` | `trust_env=False` 同时禁用 `SSL_CERT_FILE`/`SSL_CERT_DIR` 环境 CA 信任。默认系统信任库校验不受影响（全仓无 `verify=` 覆盖），仅依赖环境变量注入自定义 CA 的部署会静默失效。V1 局域网范围可接受。 | 在部署文档注明：Jellyfin 若使用非系统信任库 CA 证书需改用系统证书库导入。 |

---

## 4. 已执行验证命令与真实输出

### 4.1 `server`：focused pytest
```
$env:MEDIAREVIEW_DATA_ROOT = Join-Path $env:TEMP 'mediareview-g-review'
.\.venv\Scripts\python.exe -m pytest tests\test_jellyfin_client.py -q
→ ............... [100%]  (15 passed; 仅 1 条第三方 starlette httpx 弃用警告,非本仓)
```

### 4.2 `server`：ruff
```
.\.venv\Scripts\python.exe -m ruff check .
→ All checks passed!
```

### 4.3 项目根：`git diff --check`
```
git diff --check  →  exit 0（无空白错误）
```

### 4.4 trust_env 行为实证（临时脚本，不改源码）
```
[真实 transport + trust_env=True + all_proxy=socks5]
httpx.AsyncClient(...) 构造期抛 ImportError: Using SOCKS proxy, but the 'socksio' package is not installed
→ 证实生产 bug 根因与修复方向

[MockTransport + trust_env=True + 代理环境变量]
construct OK, trust_env = True; request OK, status 200（无 ImportError）
→ 证实回归测试 docstring 机制描述不精确，但断言仍能捕获回归

[真实 JellyfinClient(现代码 trust_env=False) + MockTransport]
construct OK, trust_env = False; system_info OK
```

### 4.5 辅助核验（发布就绪）
- `git ls-files` 禁止工件路径扫描：NO forbidden artifacts tracked
- `rg` API key 路径：playback 端点 key 仅用于校验；headers 用设备级 X-Emby-Token；`masked_dict` 全脱敏
- `rg` 视频代理：无（唯一 /stream 为直连 URL 构造）
- 日志：`access_log=False`；client.py/media.py 零日志调用
- 全量测试证据（采信主线程）：`review_meta/g1_server_pytest.txt` → 351 tests, exit 0, no failures

---

## 5. 结论

- 本阶段新增改动（trust_env=False + 回归测试 + 版本断言 + 文档）经对抗式核验：方向正确、安全性收窄而非引入风险、回归测试有效、版本断言与实际构建配置一致。
- 全分支发布就绪抽查：无 API key 序列化/URL/日志泄露、无视频流代理、无禁止工件被跟踪、文档诚实（设备侧受限、`/Auth/Keys` 500 已知限制、rc1 不 tag 1.1.0 均如实记录）。
- 发现：0 Critical / 0 Important / 2 Minor（测试 docstring 机制描述不精确；trust_env 禁用环境 CA 变量需在部署文档注明）。

**阶段结论：合格（CLEAN，0C/0I）**
