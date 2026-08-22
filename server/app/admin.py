"""Web 管理后台: 轻量单页,复用现有 /api/v1 接口,不复制业务逻辑。

- 状态面板: 健康 / 版本 / Jellyfin 状态 / 缓存占用 / 待处理任务
- 配对: 生成一次性配对码(仅本机/本机浏览器调用,安全约束在 pairing 接口内)
- 诊断: 一键导出诊断 ZIP
"""

from __future__ import annotations

from fastapi import Depends, FastAPI
from fastapi.responses import HTMLResponse

from app.api.v1.auth import require_localhost_or_auth

_PAGE = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>MediaReview 管理后台</title>
<style>
  body { font-family: system-ui, "Microsoft YaHei", sans-serif; background:#faf6f2; color:#3a2f2b; margin:0; padding:24px; }
  h1 { color:#e06c85; }
  .card { background:#fff; border-radius:16px; padding:16px 20px; margin-bottom:16px; box-shadow:0 1px 4px rgba(0,0,0,.08); }
  .row { display:flex; gap:12px; flex-wrap:wrap; }
  .btn { background:#e06c85; color:#fff; border:0; border-radius:10px; padding:10px 18px; font-size:15px; cursor:pointer; }
  .btn.secondary { background:#8a7569; }
  pre { background:#f4ece7; padding:10px; border-radius:8px; overflow:auto; font-size:13px; }
  .code { font-size:28px; letter-spacing:6px; color:#e06c85; font-weight:bold; }
  table { border-collapse:collapse; width:100%; }
  td,th { text-align:left; padding:6px 10px; border-bottom:1px solid #eee; font-size:14px; }
  .msg { margin-top:8px; color:#7a5b50; font-size:14px; }
</style>
</head>
<body>
<h1>MediaReview 管理后台</h1>

<div class="card">
  <h3>服务器状态</h3>
  <table id="status"><tr><td>加载中…</td></tr></table>
</div>

<div class="card">
  <h3>配对码</h3>
  <button class="btn" onclick="genCode()">生成一次性配对码</button>
  <div id="codeArea" class="msg"></div>
</div>

<div class="card">
  <h3>缓存 / 任务</h3>
  <pre id="cache"></pre>
</div>

<div class="card">
  <h3>诊断</h3>
  <button class="btn secondary" onclick="location.href='/api/v1/system/diagnostics/export'">下载诊断包</button>
</div>

<script>
async function jget(path) {
  const r = await fetch(path);
  return r.json();
}
async function refresh() {
  try {
    const health = await jget('/api/v1/system/health');
    const info = await jget('/api/v1/system/info');
    let jellyfinTxt = '未配置';
    try { const jf = await jget('/api/v1/jellyfin/status'); jellyfinTxt = jf.data.server_name + ' ' + jf.data.version; } catch(e) {}
    const pairing = await jget('/api/v1/pairing/status');
    const h = health.data || {};
    const rows = [
      ['状态', h.status || '-'],
      ['版本', h.version || '-'],
      ['Jellyfin', jellyfinTxt],
      ['配对要求', String(pairing.data ? pairing.data.pairing_required : '-')],
      ['已配对设备', String(pairing.data ? pairing.data.device_count : '-')],
      ['数据目录', (info.data && info.data.data_root) || '-'],
    ];
    document.getElementById('status').innerHTML =
      rows.map(r => '<tr><td><b>'+r[0]+'</b></td><td>'+r[1]+'</td></tr>').join('');
  } catch(e) {
    document.getElementById('status').innerHTML = '<tr><td>读取失败:' + e + '</td></tr>';
  }
  try {
    const cache = await jget('/api/v1/cache/statistics');
    document.getElementById('cache').textContent = JSON.stringify(cache.data, null, 2);
  } catch(e) {}
}
async function genCode() {
  const area = document.getElementById('codeArea');
  try {
    const r = await fetch('/api/v1/pairing/code', {method:'POST'});
    const body = await r.json();
    if (!body.success) { area.textContent = '生成失败:' + (body.error && body.error.message); return; }
    area.innerHTML = '配对码:<div class="code">' + body.data.code + '</div>有效期 ' +
      Math.round(body.data.expires_in_seconds/60) + ' 分钟,请在手机 App 内输入。';
  } catch(e) { area.textContent = '生成失败:' + e; }
}
refresh();
</script>
</body>
</html>
"""


def register_admin(app: FastAPI) -> None:
    """挂载管理后台页面(复用 API,不复制业务逻辑)。"""

    @app.get("/admin", response_class=HTMLResponse, include_in_schema=False)
    def admin_page(_auth=Depends(require_localhost_or_auth)) -> str:
        return _PAGE
