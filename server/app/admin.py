"""Web 管理后台(Windows 运维控制台): 轻量单页,复用现有 /api/v1 接口,不复制业务逻辑。

面板:
- 状态总览: 服务 / Jellyfin / 媒体库 / 媒体索引 / 同步状态(来自 /system/dashboard)
- 配对与设备: 生成一次性配对码、设备列表与撤销、清理已用配对码
- 媒体库与索引: 媒体库勾选、保存选择、编排媒体刷新
- 缓存管理: 占用统计与二次确认清理
- 后台任务: 最近任务列表,暂停/恢复/取消,重复扫描编排
- 错误与日志: 最近脱敏错误、日志下载、诊断导出

约束:
- 无媒体墙(运维控制台不做媒体浏览)。
- 危险操作(清理缓存/撤销设备/清理已用码/取消任务)必须二次确认。
- 键盘可操作: 全部使用原生 button/input/链接与 <dialog>,带 focus-visible 样式。
- 360px 移动宽度与桌面宽度自适应。
- 所有用户可见文案为中文。
"""

from __future__ import annotations

from fastapi import Depends, FastAPI
from fastapi.responses import HTMLResponse

from app.api.v1.auth import require_localhost_or_auth

# 必须使用原始字符串: 页面内联 JS 里的 HTML 属性引号写作 \" ,
# 若用普通三引号字符串, Python 会把 \" 解析成 " , 输出 "{attr="value"" 提前闭合 JS 字符串,
# 导致整个 <script> 语法错误、脚本完全不执行, 页面永远停在"加载中…"。
_PAGE = r"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>MediaReview 运维控制台</title>
<style>
  :root{
    --bg:#0B1118; --surface:#141D27; --surface2:#1B2735;
    --accent:#47D7E8; --text:#F4F7FA; --muted:#A9B4C0;
    --ok:#39D98A; --danger:#FF6B6B; --border:#263447;
  }
  *{box-sizing:border-box}
  html,body{margin:0;padding:0}
  body{font-family:system-ui,"Microsoft YaHei",-apple-system,"Segoe UI",sans-serif;background:var(--bg);color:var(--text);line-height:1.6}
  a{color:var(--accent)}
  .skip{position:absolute;left:-9999px;top:0;background:var(--accent);color:#06222a;padding:8px 14px;border-radius:0 0 8px 0;z-index:99}
  .skip:focus{left:0}
  .wrap{max-width:1200px;margin:0 auto;padding:16px}
  header.top{display:flex;flex-wrap:wrap;gap:12px;align-items:center;justify-content:space-between;padding:12px 0;border-bottom:1px solid var(--border);margin-bottom:16px}
  header.top h1{font-size:20px;margin:0;color:var(--accent)}
  .meta{color:var(--muted);font-size:13px}
  .tokenbar{display:flex;flex-wrap:wrap;gap:8px;align-items:center}
  .grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,340px),1fr));gap:16px}
  .card{background:var(--surface);border:1px solid var(--border);border-radius:12px;padding:16px;min-width:0}
  .card h2{font-size:15px;margin:0 0 10px;color:var(--accent);display:flex;align-items:center;justify-content:space-between;gap:8px;flex-wrap:wrap}
  .card .sub{font-size:13px;color:var(--muted);margin:12px 0 6px}
  table{width:100%;border-collapse:collapse;font-size:13px}
  th,td{text-align:left;padding:7px 8px;border-bottom:1px solid var(--border);vertical-align:top}
  th{color:var(--muted);font-weight:600;white-space:nowrap}
  .mono{font-family:ui-monospace,Consolas,monospace;font-size:12px;word-break:break-all}
  .muted{color:var(--muted)}
  .nowrap{white-space:nowrap}
  .scroll{overflow-x:auto}
  .btn{background:var(--surface2);color:var(--text);border:1px solid var(--border);border-radius:8px;padding:8px 14px;font-size:13px;cursor:pointer;font-family:inherit}
  .btn:hover{border-color:var(--accent)}
  .btn.primary{background:var(--accent);color:#06222a;border-color:var(--accent);font-weight:600}
  .btn.danger{background:var(--danger);color:#2a0808;border-color:var(--danger);font-weight:600}
  .btn.small{padding:4px 9px;font-size:12px}
  .btn:focus-visible, input:focus-visible, a:focus-visible, dialog:focus-visible{outline:2px solid var(--accent);outline-offset:2px}
  .row{display:flex;flex-wrap:wrap;gap:8px;align-items:center}
  .code{font-size:26px;letter-spacing:6px;color:var(--accent);font-weight:700;font-family:ui-monospace,Consolas,monospace}
  pre.log{background:#0a0f16;border:1px solid var(--border);border-radius:8px;padding:10px;font-size:12px;overflow:auto;max-height:260px;white-space:pre-wrap;word-break:break-all}
  .libs{list-style:none;margin:0 0 10px;padding:0}
  .libs li{padding:4px 0}
  .check input{margin-right:8px;accent-color:var(--accent)}
  .status{font-size:13px;color:var(--muted);margin:6px 0}
  input[type=password], input[type=text]{background:var(--surface2);color:var(--text);border:1px solid var(--border);border-radius:8px;padding:8px 10px;font-size:13px;font-family:inherit}
  #tokenInput{width:220px}
  dialog{background:var(--surface);color:var(--text);border:1px solid var(--accent);border-radius:12px;padding:20px;max-width:420px;width:calc(100% - 32px)}
  dialog::backdrop{background:rgba(0,0,0,.55)}
  dialog h2{margin:0 0 8px;color:var(--accent);font-size:16px}
  .dlg-actions{display:flex;justify-content:flex-end;gap:8px;margin-top:16px}
  .toast{position:fixed;left:50%;bottom:20px;transform:translateX(-50%) translateY(120%);background:var(--surface2);border:1px solid var(--border);color:var(--text);padding:10px 18px;border-radius:10px;font-size:14px;transition:transform .25s;z-index:100;max-width:90vw}
  .toast.show{transform:translateX(-50%) translateY(0)}
  .toast.ok{border-color:var(--ok)}
  .toast.err{border-color:var(--danger)}
  @media (min-width:900px){ .full{grid-column:1/-1} }
  @media (max-width:480px){
    .grid{grid-template-columns:1fr}
    header.top{flex-direction:column;align-items:stretch}
    .tokenbar{width:100%}
    #tokenInput{flex:1;width:auto}
    .card h2 .row .btn{flex:1}
    table{font-size:12px}
    th,td{padding:6px}
  }
</style>
</head>
<body>
<a class="skip" href="#main">跳到主要内容</a>
<div class="wrap">
  <header class="top">
    <div>
      <h1>MediaReview 运维控制台</h1>
      <div class="meta" id="lastUpdate">加载中…</div>
    </div>
    <div class="tokenbar">
      <label for="tokenInput">设备令牌</label>
      <input type="password" id="tokenInput" autocomplete="off" placeholder="可选:已配对设备令牌" aria-describedby="tokenStatus">
      <span class="meta" id="tokenStatus" role="status"></span>
      <button class="btn" id="tokenClearBtn" type="button">清除</button>
      <button class="btn" id="autoPairBtn" type="button">本机自动配对</button>
      <button class="btn primary" id="refreshBtn" type="button">刷新全部</button>
    </div>
  </header>
  <main id="main">
    <div class="grid">
      <section class="card full" aria-label="状态总览">
        <h2>状态总览</h2>
        <div class="scroll"><table><tbody id="dashBody"></tbody></table></div>
      </section>
      <section class="card" aria-label="配对与设备">
        <h2>配对与设备<span class="row"><button class="btn small" id="genCodeBtn" type="button">生成配对码</button><button class="btn small" id="cleanCodesBtn" type="button">清理已用码</button></span></h2>
        <p class="status" id="pairSummary" role="status"></p>
        <div id="codeArea" class="status" role="status"></div>
        <h3 class="sub">已配对设备</h3>
        <div class="scroll"><table><thead><tr><th>名称</th><th>设备 ID</th><th>最近在线</th><th>操作</th></tr></thead><tbody id="deviceRows"></tbody></table></div>
      </section>
      <section class="card" aria-label="媒体库与索引">
        <h2>媒体库与索引<span class="row"><button class="btn small" id="refreshMediaBtn" type="button">刷新媒体库</button></span></h2>
        <div id="libBox"></div>
        <p class="status" id="refreshMsg" role="status"></p>
      </section>
      <section class="card" aria-label="缓存管理">
        <h2>缓存管理<span class="row"><button class="btn small danger" id="clearCacheBtn" type="button">清理缓存</button></span></h2>
        <div class="scroll"><table><tbody id="cacheRows"></tbody></table></div>
      </section>
      <section class="card" aria-label="后台任务">
        <h2>后台任务<span class="row"><button class="btn small" id="dupScanBtn" type="button">开始重复扫描</button></span></h2>
        <p class="status" id="dupStatus" role="status"></p>
        <div class="scroll"><table><thead><tr><th>类型</th><th>状态</th><th>进度</th><th>创建时间</th><th>操作</th></tr></thead><tbody id="taskRows"></tbody></table></div>
      </section>
      <section class="card full" aria-label="错误与日志">
        <h2>错误与日志<span class="row"><button class="btn small" id="downloadLogBtn" type="button">下载日志</button><button class="btn small" id="downloadDiagBtn" type="button">导出诊断包</button></span></h2>
        <div id="errorBox"></div>
      </section>
    </div>
  </main>
</div>
<dialog id="confirmDlg" aria-labelledby="dlgTitle">
  <h2 id="dlgTitle">确认危险操作</h2>
  <p id="dlgText"></p>
  <div class="dlg-actions">
    <button class="btn" id="confirmNo" type="button">取消</button>
    <button class="btn danger" id="confirmYes" type="button">确认执行</button>
  </div>
</dialog>
<div class="toast" id="toast" role="status" aria-live="polite"></div>
<script>
"use strict";
const $ = (id) => document.getElementById(id);
const TOKEN_KEY = "mr_admin_token";
let token = sessionStorage.getItem(TOKEN_KEY) || "";
const $token = $("tokenInput");
$token.value = token;

function setToken(t) {
  token = (t || "").trim();
  if (token) sessionStorage.setItem(TOKEN_KEY, token);
  else sessionStorage.removeItem(TOKEN_KEY);
  $token.value = token;
  renderTokenStatus();
}
function renderTokenStatus() {
  $("tokenStatus").textContent = token ? "已设置:" + token.slice(0, 8) + "…" : "未设置(本机可点自动配对)";
}
function authHeaders(json) {
  const h = { "Accept": "application/json" };
  if (json) h["Content-Type"] = "application/json";
  if (token) h["Authorization"] = "Bearer " + token;
  return h;
}
class ApiError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}
async function parse(res) {
  const ct = res.headers.get("content-type") || "";
  let body = null;
  if (ct.includes("application/json")) { try { body = await res.json(); } catch (e) {} }
  else { body = await res.text(); }
  if (res.status === 401) throw new ApiError(401, "需要设备令牌(LAN 请在上方填入已配对设备令牌,本机可点自动配对)");
  if (!res.ok) {
    const msg = body && body.error && body.error.message ? body.error.message : "请求失败(" + res.status + ")";
    throw new ApiError(res.status, msg);
  }
  return body;
}
async function jget(url) { return parse(await fetch(url, { headers: authHeaders(false) })); }
async function jpost(url, body) {
  return parse(await fetch(url, {
    method: "POST",
    headers: authHeaders(body !== undefined),
    body: body === undefined ? undefined : JSON.stringify(body),
  }));
}
async function jput(url, body) {
  return parse(await fetch(url, { method: "PUT", headers: authHeaders(true), body: JSON.stringify(body) }));
}
function esc(s) {
  return String(s == null ? "" : s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}
function fmtTime(iso) {
  if (!iso) return "-";
  try { const d = new Date(iso); return isNaN(d.getTime()) ? iso : d.toLocaleString("zh-CN", { hour12: false }); } catch (e) { return iso; }
}
function fmtBytes(n) {
  n = Number(n) || 0;
  const units = ["B", "KB", "MB", "GB", "TB"];
  let i = 0;
  while (n >= 1024 && i < units.length - 1) { n /= 1024; i++; }
  return (i ? n.toFixed(1) : n) + " " + units[i];
}
const TYPE_NAMES = { media_refresh: "媒体刷新", sprite: "雪碧图", duplicate_scan: "重复扫描", duplicate_hash: "媒体哈希" };
const STATUS_NAMES = { pending: "等待中", running: "运行中", succeeded: "已完成", failed: "失败", cancelled: "已取消", paused: "已暂停" };
function typeName(t) { return TYPE_NAMES[t] || t || "-"; }
function statusName(s) { return STATUS_NAMES[s] || s || "-"; }

function toast(msg, ok) {
  const el = $("toast");
  el.textContent = msg;
  el.className = "toast show " + (ok === false ? "err" : "ok");
  clearTimeout(el._t);
  el._t = setTimeout(() => { el.className = "toast"; }, 3200);
}

let confirmSettled = false;
function askConfirm(title, text, danger) {
  return new Promise(resolve => {
    confirmSettled = false;
    $("dlgTitle").textContent = title;
    $("dlgText").textContent = text;
    const yes = $("confirmYes"), no = $("confirmNo");
    yes.className = "btn " + (danger ? "danger" : "primary");
    const finish = (v) => { if (confirmSettled) return; confirmSettled = true; $("confirmDlg").close(); resolve(v); };
    yes.onclick = () => finish(true);
    no.onclick = () => finish(false);
    $("confirmDlg").onclose = () => finish(false);
    $("confirmDlg").showModal();
    no.focus();
  });
}
$("confirmDlg").addEventListener("keydown", (e) => {
  if (e.key === "Escape") { e.preventDefault(); $("confirmDlg").close(); }
  if (e.key === "Tab") {
    const f = $("confirmDlg").querySelectorAll("button");
    const first = f[0], last = f[f.length - 1];
    if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
    else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
  }
});

// ---- 状态总览 ----
async function loadDashboard() {
  const p = $("dashBody");
  try {
    const d = (await jget("/api/v1/system/dashboard")).data;
    const jf = d.jellyfin || {};
    const jfTxt = !jf.configured ? "未配置"
      : (jf.reachable ? ("可达 · " + (jf.name || "") + " " + (jf.version || "")) : ("不可达 · " + (jf.error || "未知")));
    const sync = d.sync || {};
    const rows = [
      ["服务状态", "运行中"],
      ["版本", esc(d.version)],
      ["监听地址", esc(d.host + ":" + d.port)],
      ["局域网地址", esc(d.lan_address || "未知")],
      ["Jellyfin", esc(jfTxt)],
      ["媒体库", "共 " + (d.libraries.total || 0) + " 个,已勾选 " + (d.libraries.selected || 0)],
      ["媒体索引", "共 " + (d.index.media_count || 0) + " 个(视频 " + (d.index.video_count || 0) + " / 图片 " + (d.index.photo_count || 0) + ")"],
      ["同步状态", esc(sync.message || sync.state || "-") + (sync.has_error ? " 有错误" : "")],
      ["上次同步", esc(fmtTime(sync.last_success_at))],
    ];
    p.innerHTML = rows.map(r => "<tr><th scope=\"row\">" + r[0] + "</th><td>" + r[1] + "</td></tr>").join("");
  } catch (e) {
    p.innerHTML = "<tr><td colspan=\"2\">" + esc(e.message) + "</td></tr>";
  }
}

// ---- 配对与设备 ----
async function loadPairing() {
  try {
    const st = (await jget("/api/v1/pairing/status")).data;
    $("pairSummary").textContent = "配对要求:" + (st.pairing_required ? "开启" : "关闭") + " · 已配对设备:" + st.device_count + " 台";
  } catch (e) { $("pairSummary").textContent = e.message; }
  try {
    const rows = (await jget("/api/v1/pairing/devices")).data || [];
    $("deviceRows").innerHTML = rows.length ? rows.map(d => {
      const op = d.revoked
        ? "<span class=\"muted\">已撤销</span>"
        : "<button class=\"btn small danger\" type=\"button\" data-revoke=\"" + esc(d.device_id) + "\" data-name=\"" + esc(d.name || d.device_id) + "\">撤销</button>";
      return "<tr><td>" + esc(d.name || "未命名") + "</td><td class=\"mono\">" + esc(d.device_id) + "</td><td>" + esc(fmtTime(d.last_seen_at)) + "</td><td class=\"nowrap\">" + op + "</td></tr>";
    }).join("") : "<tr><td colspan=\"4\" class=\"muted\">暂无已配对设备</td></tr>";
  } catch (e) {
    $("deviceRows").innerHTML = "<tr><td colspan=\"4\">" + esc(e.message) + "</td></tr>";
  }
}
$("deviceRows").addEventListener("click", (e) => {
  const b = e.target.closest("button");
  if (!b || !b.dataset.revoke) return;
  revokeDevice(b.dataset.revoke, b.dataset.name || b.dataset.revoke);
});
async function revokeDevice(id, name) {
  const yes = await askConfirm("撤销设备", "确定撤销设备\u201c" + name + "\u201d?撤销后其令牌立即失效,需重新配对。", true);
  if (!yes) return;
  try {
    await jpost("/api/v1/pairing/revoke", { device_id: id });
    toast("设备已撤销");
    loadPairing();
  } catch (e) { toast(e.message, false); }
}
async function genCode() {
  const area = $("codeArea");
  area.textContent = "正在生成…";
  try {
    const r = await jpost("/api/v1/pairing/code");
    const mins = Math.round(r.data.expires_in_seconds / 60);
    area.innerHTML = "配对码:<br><span class=\"code\">" + esc(r.data.code) + "</span><br>有效期 " + mins + " 分钟,请在手机 App 内输入。";
    toast("配对码已生成");
  } catch (e) { area.textContent = e.message; }
}
async function cleanCodes() {
  const yes = await askConfirm("清理已用配对码", "将删除所有已使用或已过期的配对码。", true);
  if (!yes) return;
  try {
    const r = await jpost("/api/v1/pairing/codes/clean");
    toast("已清理 " + r.data.removed + " 个配对码");
  } catch (e) { toast(e.message, false); }
}

// ---- 媒体库与索引 ----
async function loadLibraries() {
  const box = $("libBox");
  box.innerHTML = "<p class=\"status\">加载中…</p>";
  try {
    const rows = (await jget("/api/v1/libraries")).data || [];
    if (!rows.length) { box.innerHTML = "<p class=\"status\">暂无媒体库,请先配置 Jellyfin 后再刷新。</p>"; return; }
    box.innerHTML = "<ul class=\"libs\" id=\"libList\">" + rows.map(l =>
      "<li><label class=\"check\"><input type=\"checkbox\" data-id=\"" + esc(l.jellyfin_id) + "\"" + (l.selected ? " checked" : "") + "> " + esc(l.name) + "</label></li>"
    ).join("") + "</ul><button class=\"btn primary\" id=\"saveLibBtn\" type=\"button\">保存勾选</button>";
    $("saveLibBtn").addEventListener("click", saveSelection);
  } catch (e) { box.innerHTML = "<p class=\"status\">" + esc(e.message) + "</p>"; }
}
function selectedLibs() {
  return Array.from(document.querySelectorAll("#libList input:checked")).map(i => i.dataset.id);
}
async function saveSelection() {
  try {
    await jput("/api/v1/libraries/selection", { selected: selectedLibs() });
    toast("媒体库勾选已保存");
    loadLibraries();
    loadDashboard();
  } catch (e) { toast(e.message, false); }
}
async function refreshMedia() {
  $("refreshMsg").textContent = "正在编排…";
  try {
    const t = (await jpost("/api/v1/media/refresh", { force: false })).data;
    $("refreshMsg").textContent = "已编排刷新任务,状态:" + statusName(t.status);
    toast("媒体刷新已开始");
    setTimeout(loadTasks, 800);
  } catch (e) { $("refreshMsg").textContent = e.message; toast(e.message, false); }
}

// ---- 缓存管理 ----
async function loadCache() {
  try {
    const st = (await jget("/api/v1/cache/statistics")).data;
    const sd = (await jget("/api/v1/system/storage")).data;
    const cb = sd.cache_breakdown || {};
    const rows = [
      ["雪碧图", st.sprite_count + " 张 / " + fmtBytes(st.sprite_bytes)],
      ["缓存策略", "上限 " + st.max_gb + " GB · 已用 " + st.used_gb + " GB"],
      ["待处理任务", st.pending_tasks],
      ["磁盘剩余", fmtBytes(sd.disk_free_bytes) + " / " + fmtBytes(sd.disk_total_bytes)],
      ["缩略图", fmtBytes(cb.thumbnails || 0)],
      ["雪碧图缓存", fmtBytes(cb.sprites || 0)],
      ["预览图", fmtBytes(cb.previews || 0)],
      ["临时文件", fmtBytes(cb.temp || 0)],
    ];
    $("cacheRows").innerHTML = rows.map(r => "<tr><th scope=\"row\">" + r[0] + "</th><td>" + r[1] + "</td></tr>").join("");
  } catch (e) {
    $("cacheRows").innerHTML = "<tr><td colspan=\"2\">" + esc(e.message) + "</td></tr>";
  }
}
async function clearCache() {
  const yes = await askConfirm("清理缓存", "将删除全部可再生成的缓存(缩略图/雪碧图/预览/临时文件),不影响数据库与媒体原文件。此操作不可撤销。", true);
  if (!yes) return;
  try {
    const r = await jpost("/api/v1/system/cache/clear?confirm=true");
    toast("缓存清理完成:移除 " + r.data.removed_files + " 个文件," + fmtBytes(r.data.removed_bytes));
    loadCache();
    loadDashboard();
  } catch (e) { toast(e.message, false); }
}

// ---- 后台任务 ----
async function loadTasks() {
  const tb = $("taskRows");
  try {
    const rows = (await jget("/api/v1/tasks?limit=15")).data || [];
    tb.innerHTML = rows.length ? rows.map(t => {
      let actions = "";
      if (t.status === "pending" || t.status === "running") {
        actions = "<button class=\"btn small\" type=\"button\" data-pause=\"" + esc(t.task_id) + "\">暂停</button>" +
                  "<button class=\"btn small danger\" type=\"button\" data-cancel=\"" + esc(t.task_id) + "\">取消</button>";
      } else if (t.status === "paused") {
        actions = "<button class=\"btn small\" type=\"button\" data-resume=\"" + esc(t.task_id) + "\">恢复</button>" +
                  "<button class=\"btn small danger\" type=\"button\" data-cancel=\"" + esc(t.task_id) + "\">取消</button>";
      }
      return "<tr><td>" + typeName(t.type) + "</td><td>" + statusName(t.status) + "</td><td>" +
        esc(t.progress != null ? t.progress + "%" : "-") + "</td><td class=\"mono\">" + esc(fmtTime(t.created_at)) +
        "</td><td class=\"nowrap\">" + actions + "</td></tr>";
    }).join("") : "<tr><td colspan=\"5\" class=\"muted\">暂无后台任务</td></tr>";
  } catch (e) {
    tb.innerHTML = "<tr><td colspan=\"5\">" + esc(e.message) + "</td></tr>";
  }
}
$("taskRows").addEventListener("click", (e) => {
  const b = e.target.closest("button");
  if (!b) return;
  if (b.dataset.cancel) taskAction("cancel", b.dataset.cancel, true);
  else if (b.dataset.pause) taskAction("pause", b.dataset.pause, false);
  else if (b.dataset.resume) taskAction("resume", b.dataset.resume, false);
});
async function taskAction(action, id, dangerous) {
  if (dangerous) {
    const yes = await askConfirm("取消任务", "确定取消该后台任务?取消后进度将丢弃。", true);
    if (!yes) return;
  }
  const names = { cancel: "取消", pause: "暂停", resume: "恢复" };
  try {
    await jpost("/api/v1/tasks/" + encodeURIComponent(id) + "/" + action);
    toast("任务已" + names[action]);
    loadTasks();
  } catch (e) { toast(e.message, false); }
}
async function loadDuplicateStatus() {
  try {
    const t = (await jget("/api/v1/duplicates/status")).data;
    $("dupStatus").textContent = t.task_id
      ? "最近扫描:" + statusName(t.status) + " · 进度 " + (t.progress != null ? t.progress + "%" : "-")
      : "尚未执行过重复扫描";
  } catch (e) { $("dupStatus").textContent = e.message; }
}
async function startDuplicateScan() {
  const yes = await askConfirm("开始重复扫描", "将创建后台任务对已索引媒体进行重复检测,不会自动删除任何文件。", false);
  if (!yes) return;
  try {
    await jpost("/api/v1/duplicates/scan");
    toast("重复扫描已开始");
    loadDuplicateStatus();
    setTimeout(loadTasks, 800);
  } catch (e) { toast(e.message, false); }
}

// ---- 错误与日志 ----
async function loadErrors() {
  const box = $("errorBox");
  try {
    const lines = (await jget("/api/v1/system/errors?limit=60")).data || [];
    box.innerHTML = lines.length
      ? "<pre class=\"log\">" + lines.map(l => esc(l)).join("\n") + "</pre>"
      : "<p class=\"status\">暂无错误日志</p>";
  } catch (e) { box.innerHTML = "<p class=\"status\">" + esc(e.message) + "</p>"; }
}
async function download(url, filename) {
  try {
    const res = await fetch(url, { headers: authHeaders(false) });
    if (!res.ok) throw new ApiError(res.status, "下载失败(" + res.status + ")");
    const blob = await res.blob();
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(a.href), 1000);
  } catch (e) { toast(e.message, false); }
}

// ---- 本机自动配对(仅回环可用) ----
async function autoPair() {
  try {
    const code = (await jpost("/api/v1/pairing/code")).data.code;
    const deviceId = "console-" + Math.random().toString(36).slice(2, 10);
    const r = await jpost("/api/v1/pairing/verify", { device_id: deviceId, code });
    if (!r.data.token) throw new Error("配对失败");
    setToken(r.data.token);
    toast("已在本机完成管理令牌配对");
    refreshAll();
  } catch (e) {
    toast("自动配对失败:" + e.message + "(仅回环本机可用)", false);
  }
}

// ---- 聚合 ----
async function refreshAll() {
  await Promise.all([
    loadDashboard(), loadPairing(), loadLibraries(), loadTasks(),
    loadCache(), loadErrors(), loadDuplicateStatus(),
  ]);
  $("lastUpdate").textContent = "更新于 " + new Date().toLocaleTimeString("zh-CN", { hour12: false });
}
function pollLight() {
  loadDashboard(); loadCache(); loadTasks(); loadErrors(); loadDuplicateStatus();
}

// ---- 初始化 ----
renderTokenStatus();
$("refreshBtn").addEventListener("click", refreshAll);
$("genCodeBtn").addEventListener("click", genCode);
$("cleanCodesBtn").addEventListener("click", cleanCodes);
$("refreshMediaBtn").addEventListener("click", refreshMedia);
$("clearCacheBtn").addEventListener("click", clearCache);
$("dupScanBtn").addEventListener("click", startDuplicateScan);
$("downloadLogBtn").addEventListener("click", () => download("/api/v1/system/logs", "mediareview-server.log"));
$("downloadDiagBtn").addEventListener("click", () => download("/api/v1/system/diagnostics/export", "mediareview-diagnostics.zip"));
$("autoPairBtn").addEventListener("click", autoPair);
$("tokenClearBtn").addEventListener("click", () => setToken(""));
$token.addEventListener("input", () => setToken($token.value));
refreshAll();
setInterval(pollLight, 15000);
</script>
</body>
</html>
"""


def register_admin(app: FastAPI) -> None:
    """挂载运维控制台页面(复用 API,不复制业务逻辑)。"""

    @app.get("/admin", response_class=HTMLResponse, include_in_schema=False)
    def admin_page(_auth=Depends(require_localhost_or_auth)) -> str:
        return _PAGE
