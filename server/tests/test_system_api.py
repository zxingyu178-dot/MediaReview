"""system 接口测试: health / info / storage / 管理后台 / 诊断导出。"""

from __future__ import annotations

import io
import zipfile

from fastapi.testclient import TestClient

from app import __version__


def test_health_ok(client: TestClient) -> None:
    resp = client.get("/api/v1/system/health")
    assert resp.status_code == 200
    body = resp.json()
    assert body["success"] is True
    assert body["error"] is None
    assert body["request_id"]
    data = body["data"]
    assert data["status"] == "ok"
    assert data["version"] == __version__
    assert data["components"]["database"] == "ok"
    assert data["components"]["jellyfin"] == "not_configured"


def test_health_request_id_matches_header(client: TestClient) -> None:
    resp = client.get("/api/v1/system/health")
    assert resp.headers["X-Request-ID"] == resp.json()["request_id"]


def test_info_masks_sensitive_config(client: TestClient) -> None:
    resp = client.get("/api/v1/system/info")
    assert resp.status_code == 200
    body = resp.json()["data"]
    assert body["version"] == __version__
    assert body["config"]["jellyfin"]["api_key"] == ""


def test_storage_reports_cache_dirs(client: TestClient) -> None:
    resp = client.get("/api/v1/system/storage")
    assert resp.status_code == 200
    data = resp.json()["data"]
    assert set(data["cache_breakdown"]) == {"thumbnails", "sprites", "previews", "temp"}
    assert data["cache_total_bytes"] == 0
    assert data["disk_total_bytes"] > 0
    assert data["disk_free_bytes"] > 0


def test_openapi_available(client: TestClient) -> None:
    resp = client.get("/api/openapi.json")
    assert resp.status_code == 200
    assert "/api/v1/system/health" in resp.json()["paths"]


def test_admin_page_served(client: TestClient) -> None:
    resp = client.get("/admin")
    assert resp.status_code == 200
    assert "MediaReview 管理后台" in resp.text


def test_diagnostics_export_zip(client: TestClient) -> None:
    resp = client.get("/api/v1/system/diagnostics/export")
    assert resp.status_code == 200
    assert resp.headers["content-type"].startswith("application/zip")
    z = zipfile.ZipFile(io.BytesIO(resp.content))
    names = z.namelist()
    assert "system_info.json" in names
    assert "storage.json" in names
    assert "table_counts.json" in names
    info = z.read("system_info.json").decode()
    assert "MediaReview Server" in info
    # 脱敏: 不含真实 api key(值为空)
    assert '"api_key": ""' in info


def test_diagnostics_export_masks_sensitive_logs(client: TestClient) -> None:
    """诊断包内日志必须对 token/配对码/api_key 等敏感字段脱敏。"""
    logs_dir = client.app.state.paths.logs_dir
    logs_dir.mkdir(parents=True, exist_ok=True)
    secret_token = "mr_" + "A" * 40
    secret_code = "654321"
    (logs_dir / "server.log").write_text(
        "Bearer " + secret_token + " ok\n"
        'generated code="' + secret_code + '"\n'
        'api_key="jellyfin-secret-key-xyz"\n'
        "normal log line\n",
        encoding="utf-8",
    )

    resp = client.get("/api/v1/system/diagnostics/export")
    assert resp.status_code == 200
    z = zipfile.ZipFile(io.BytesIO(resp.content))
    log_content = z.read("logs/server.log").decode("utf-8")
    assert secret_token not in log_content
    assert secret_code not in log_content
    assert "jellyfin-secret-key-xyz" not in log_content
    assert "normal log line" in log_content
