"""Task E RED 测试：Windows 运维控制台（状态面板 / 缓存清理 / 最近错误 / 日志下载）。

覆盖验收要点（takeover plan Task E Step 1）：
- 未认证 LAN 拒绝（401）、loopback 放行（localhost-or-auth）
- 危险操作二次确认：缓存清理必须显式 confirm，否则拒绝（防误触 / CSRF 式误删）
- token / api_key / 配对码 / 媒体绝对路径 脱敏（对抗式密钥扫描）
- 诊断导出与日志下载同样脱敏，绝不外泄原始错误文本
"""

from __future__ import annotations

import io
import re
import zipfile
from pathlib import Path

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from pydantic import SecretStr

from app.core.config import AppConfig, SecurityConfig, StorageConfig
from app.db import models
from app.main import create_app

# 与系统日志脱敏约定一致的敏感形态
_MR_TOKEN = "mr_" + "Z" * 40
_API_KEY = "jellyfin-ops-secret-zz"
_PAIR_CODE = "987654"
_MEDIA_ABS_PATH = r"D:\Media\Family\secret-clip.mp4"


def _configured_app(data_root: Path, *, pairing_required: bool = True) -> FastAPI:
    config = AppConfig(
        storage=StorageConfig(data_root=str(data_root)),
        security=SecurityConfig(pairing_required=pairing_required),
    )
    config.jellyfin.api_key = SecretStr(_API_KEY)
    return create_app(config, run_db_migrations=True)


class _Loopback:
    """ASGI 包装：把 http 作用域 client 改写为 127.0.0.1，模拟本机回环请求。"""

    def __init__(self, app: FastAPI) -> None:
        self.app: FastAPI = app

    async def __call__(self, scope, receive, send) -> None:  # noqa: ANN001
        if scope["type"] == "http":
            scope["client"] = ("127.0.0.1", 50000)
        await self.app(scope, receive, send)


@pytest.fixture
def pair_client(tmp_path: Path):
    """本机/管理后台（pairing_required=True + 回环包装）。"""
    app = _configured_app(tmp_path)
    with TestClient(_Loopback(app)) as tc:
        yield tc


@pytest.fixture
def remote_client(tmp_path: Path):
    """局域网任意设备（pairing_required=True，非回环 host）。"""
    app = _configured_app(tmp_path)
    with TestClient(app) as tc:
        yield tc


def _inner_app(pair_client: TestClient) -> FastAPI:
    return pair_client.app.app


def _seed_media_with_abs_path(pair_client: TestClient) -> None:
    db = _inner_app(pair_client).state.database
    with db.session() as session:
        session.add(
            models.MediaCacheIndex(
                media_id="ops-media-001",
                jellyfin_id="jf-ops-1",
                library_id="lib-ops",
                name="secret-clip.mp4",
                media_type="video",
                fingerprint="fp-ops",
                media_path=_MEDIA_ABS_PATH,
            )
        )
        session.commit()


def _seed_logs(pair_client: TestClient) -> Path:
    logs_dir = _inner_app(pair_client).state.paths.logs_dir
    logs_dir.mkdir(parents=True, exist_ok=True)
    log_file = logs_dir / "server.log"
    log_file.write_text(
        "INFO 正常行 keep-me\n"
        f"ERROR 访问失败: {_MEDIA_ABS_PATH}\n"
        f"ERROR token: {_MR_TOKEN}\n"
        f'ERROR code="{_PAIR_CODE}" api_key="{_API_KEY}"\n',
        encoding="utf-8",
    )
    return log_file


# ---- E2 状态面板 ----


def test_dashboard_returns_aggregated_status(pair_client: TestClient) -> None:
    resp = pair_client.get("/api/v1/system/dashboard")
    assert resp.status_code == 200
    data = resp.json()["data"]
    assert data["version"]
    assert data["host"]
    assert data["port"] == 8766
    assert isinstance(data["lan_address"], str)
    assert data["jellyfin"]["configured"] is True
    assert data["jellyfin"]["reachable"] is False  # 未连真 Jellyfin，尽力而为探测
    assert data["libraries"]["selected"] == 0
    assert data["index"]["media_count"] == 0
    assert data["index"]["video_count"] == 0
    assert data["sync"]["state"] in {"idle", "pending", "running", "succeeded", "failed"}
    assert data["sync"]["has_error"] is False


def test_dashboard_remote_denied(remote_client: TestClient) -> None:
    """未配对局域网设备访问 dashboard 必须 401。"""
    assert remote_client.get("/api/v1/system/dashboard").status_code == 401


def test_dashboard_reports_index_and_library_counts(pair_client: TestClient) -> None:
    db = _inner_app(pair_client).state.database
    with db.session() as session:
        session.add(models.LibrarySelection(jellyfin_id="lib-1", name="电影", selected=True))
        session.add(
            models.MediaCacheIndex(
                media_id="m1",
                jellyfin_id="j1",
                library_id="lib-1",
                name="a.mp4",
                media_type="video",
                fingerprint="fp1",
            )
        )
        session.add(
            models.MediaCacheIndex(
                media_id="m2",
                jellyfin_id="j2",
                library_id="lib-1",
                name="b.jpg",
                media_type="photo",
                fingerprint="fp2",
            )
        )
        session.commit()
    data = pair_client.get("/api/v1/system/dashboard").json()["data"]
    assert data["libraries"]["selected"] == 1
    assert data["index"]["media_count"] == 2
    assert data["index"]["video_count"] == 1
    assert data["index"]["photo_count"] == 1


def test_dashboard_never_serializes_secrets(pair_client: TestClient) -> None:
    """dashboard 即使配了 api_key / 存在媒体绝对路径，也不得外泄。"""
    _seed_media_with_abs_path(pair_client)
    resp = pair_client.get("/api/v1/system/dashboard")
    assert resp.status_code == 200
    text = resp.text
    assert _API_KEY not in text
    assert _MR_TOKEN not in text
    assert _PAIR_CODE not in text
    assert _MEDIA_ABS_PATH not in text
    assert "api_key" not in text


# ---- E3 缓存清理（危险二次确认） ----


def test_cache_clear_requires_dangerous_confirmation(pair_client: TestClient) -> None:
    """未携带 confirm 的缓存清理必须被拒绝（400），防误触。"""
    resp = pair_client.post("/api/v1/system/cache/clear")
    assert resp.status_code == 400
    assert resp.json()["success"] is False


def test_cache_clear_rejects_wrong_confirm(pair_client: TestClient) -> None:
    """confirm 必须是显式的 true/1，其余一律拒绝。"""
    for bad in ("false", "0", "yes", "no", ""):
        resp = pair_client.post("/api/v1/system/cache/clear", params={"confirm": bad})
        assert resp.status_code == 400, bad


def test_cache_clear_with_confirmation_clears_cache_only(
    pair_client: TestClient, tmp_path: Path
) -> None:
    """缓存清理只清 cache/ 下的可再生成文件，绝不触碰 DB、媒体、配置。"""
    cache = tmp_path / "cache"
    (cache / "thumbnails").mkdir(parents=True, exist_ok=True)
    (cache / "sprites").mkdir(exist_ok=True)
    (cache / "previews").mkdir(exist_ok=True)
    (cache / "temp").mkdir(exist_ok=True)
    (cache / "thumbnails" / "a.jpg").write_bytes(b"img")
    (cache / "sprites" / "b.jpg").write_bytes(b"sprite")
    (cache / "previews" / "c.jpg").write_bytes(b"preview")
    (cache / "temp" / "d.tmp").write_bytes(b"tmp")

    db_path = tmp_path / "database" / "mediareview.db"
    media_dir = tmp_path / "library"
    media_dir.mkdir(exist_ok=True)
    media_file = media_dir / "keep.mp4"
    media_file.write_bytes(b"video")

    resp = pair_client.post("/api/v1/system/cache/clear", params={"confirm": "true"})
    assert resp.status_code == 200
    data = resp.json()["data"]
    assert data["removed_files"] >= 4
    assert data["removed_bytes"] > 0
    assert not (cache / "thumbnails" / "a.jpg").exists()
    assert not (cache / "sprites" / "b.jpg").exists()
    # 非缓存内容保留
    assert db_path.exists()
    assert media_file.exists()
    assert (tmp_path / "config").is_dir()


def test_cache_clear_remote_denied(remote_client: TestClient) -> None:
    assert (
        remote_client.post("/api/v1/system/cache/clear", params={"confirm": "true"}).status_code
        == 401
    )


# ---- E4 最近脱敏错误 + 日志下载 ----


def test_recent_errors_returns_redacted_lines(pair_client: TestClient) -> None:
    _seed_logs(pair_client)
    resp = pair_client.get("/api/v1/system/errors")
    assert resp.status_code == 200
    text = resp.text
    # 只暴露脱敏后的错误行
    assert "ERROR" in text
    assert _MR_TOKEN not in text
    assert _API_KEY not in text
    assert _PAIR_CODE not in text
    assert _MEDIA_ABS_PATH not in text


def test_recent_errors_remote_denied(remote_client: TestClient) -> None:
    assert remote_client.get("/api/v1/system/errors").status_code == 401


def test_log_download_returns_redacted_text(pair_client: TestClient) -> None:
    _seed_logs(pair_client)
    resp = pair_client.get("/api/v1/system/logs")
    assert resp.status_code == 200
    assert resp.headers["content-type"].startswith("text/plain")
    body = resp.text
    assert "正常行 keep-me" in body
    assert _MR_TOKEN not in body
    assert _API_KEY not in body
    assert _PAIR_CODE not in body
    assert _MEDIA_ABS_PATH not in body


def test_log_download_remote_denied(remote_client: TestClient) -> None:
    assert remote_client.get("/api/v1/system/logs").status_code == 401


# ---- 对抗式密钥扫描：所有序列化输出 ----


def test_adversarial_secret_scan_on_all_serialized_output(
    pair_client: TestClient,
) -> None:
    """dashboard / errors / logs / 诊断 ZIP 任一输出都不得含敏感值。"""
    _seed_media_with_abs_path(pair_client)
    _seed_logs(pair_client)

    outputs = {
        "dashboard": pair_client.get("/api/v1/system/dashboard").text,
        "errors": pair_client.get("/api/v1/system/errors").text,
        "logs": pair_client.get("/api/v1/system/logs").text,
        "diagnostics": "",
    }
    z = zipfile.ZipFile(io.BytesIO(pair_client.get("/api/v1/system/diagnostics/export").content))
    for name in z.namelist():
        outputs["diagnostics"] += z.read(name).decode("utf-8", errors="replace")

    for label, text in outputs.items():
        assert text, label
        assert _MR_TOKEN not in text, label
        assert _API_KEY not in text, label
        assert _PAIR_CODE not in text, label
        assert _MEDIA_ABS_PATH not in text, label


# ---- E5 运维控制台页面 ----

_PANEL_LABELS = ("状态总览", "配对与设备", "媒体库与索引", "缓存管理", "后台任务", "错误与日志")


def test_admin_page_loopback_allowed(pair_client: TestClient) -> None:
    """本机回环可打开运维控制台,包含全部中文面板,且无媒体墙。"""
    resp = pair_client.get("/admin")
    assert resp.status_code == 200
    assert resp.headers["content-type"].startswith("text/html")
    html = resp.text
    assert 'lang="zh-CN"' in html
    assert "MediaReview 运维控制台" in html
    for label in _PANEL_LABELS:
        assert label in html, label
    # 无媒体墙: 不渲染媒体网格/缩略图/媒体条目
    assert "媒体墙" not in html
    assert "media-grid" not in html
    assert "media-card" not in html


def test_admin_page_remote_denied(remote_client: TestClient) -> None:
    """未配对局域网设备访问运维控制台必须 401。"""
    assert remote_client.get("/admin").status_code == 401


def test_admin_page_has_secondary_confirmation(pair_client: TestClient) -> None:
    """危险操作必须经 <dialog> 二次确认,且有明确的取消/确认按钮。"""
    html = pair_client.get("/admin").text
    assert "<dialog" in html
    assert "确认危险操作" in html
    assert 'id="confirmYes"' in html and "确认执行" in html
    assert 'id="confirmNo"' in html and "取消" in html


def test_admin_page_keyboard_accessible(pair_client: TestClient) -> None:
    """全部交互使用原生 button/input/链接(可键盘聚焦),并带 focus-visible 样式。"""
    html = pair_client.get("/admin").text
    assert ":focus-visible" in html
    # 禁用纯 onclick 的 div/span 式伪按钮: 交互元素必须是原生控件
    assert 'onclick="' not in html
    assert "<button" in html and "<input" in html and "<a " in html


def test_admin_page_redacts_no_secrets(pair_client: TestClient) -> None:
    """控制台页面本身不内嵌任何敏感值(token/api_key/配对码/媒体路径)。"""
    _seed_media_with_abs_path(pair_client)
    _seed_logs(pair_client)
    html = pair_client.get("/admin").text
    assert _MR_TOKEN not in html
    assert _API_KEY not in html
    assert _PAIR_CODE not in html
    assert _MEDIA_ABS_PATH not in html


def test_admin_page_inline_script_keeps_attribute_quotes_escaped(
    pair_client: TestClient,
) -> None:
    """回归: 内联 JS 中 HTML 属性引号必须保持转义(\\"), 否则整个脚本无法解析。

    历史缺陷: `_PAGE` 曾使用普通三引号字符串, Python 把 `\\"` 解析成 `"`,
    实际输出形如 `"<tr><th scope="row">"` —— 提前闭合 JS 字符串,
    整个 `<script>` 语法错误(SyntaxError: missing ) after argument list),
    脚本完全不执行, 页面永远停在"加载中…", 所有面板空白。
    """
    html = pair_client.get("/admin").text
    script = html.split("<script>", 1)[1].split("</script>", 1)[0]
    # 属性引号必须保留反斜杠转义
    assert 'scope=\\"row\\"' in script
    assert 'class=\\"muted\\"' in script
    # 不得出现被吞掉转义的裸属性引号(会破坏所在 JS 字符串字面量)
    bare = re.findall(r"[A-Za-z][A-Za-z0-9-]*=\"", script)
    assert bare == [], f"内联 JS 存在未转义的属性引号: {bare[:5]}"
    # join() 的换行必须是转义形式, 不能是真实换行(否则字符串未闭合)
    assert 'join("\\n")' in script
