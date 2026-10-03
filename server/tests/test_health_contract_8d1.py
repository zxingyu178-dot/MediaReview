"""Stage 8D.1 §4~§10: Server Health 的 API Contract 合同。

- `/api/v1/system/health` 必须返回 `api_contract` 与 `capabilities`；
- `api_contract` 是 App 判断兼容性的**正式字段**(不是 `version` 字符串比较)；
- Server 版本与 API Contract 分开管理：版本给人看,Contract 给 App 判断。
"""

from __future__ import annotations

from fastapi.testclient import TestClient

from app import SERVER_API_CONTRACT, SERVER_CAPABILITIES, __version__


def test_server_version_is_121() -> None:
    """Stage 8D.2 §33: Server 1.2.0 → 1.2.1（**Contract 保持 2**，只修 Bug）。"""
    assert __version__ == "1.2.1"


def test_api_contract_is_2() -> None:
    """Stage 8D.1 §6: 当前契约 = 2。"""
    assert SERVER_API_CONTRACT == 2


def test_health_exposes_api_contract_and_capabilities(client: TestClient) -> None:
    resp = client.get("/api/v1/system/health")
    assert resp.status_code == 200
    data = resp.json()["data"]
    assert data["version"] == __version__
    assert data["api_contract"] == SERVER_API_CONTRACT
    assert isinstance(data["capabilities"], list)
    # §10 建议能力必须登记(不实现复杂 Feature Flag,只暴露清单)
    for expected in (
        "review_session",
        "review_nearest",
        "organize",
        "delete_nonce",
        "duplicates_paged",
        "library_selection",
    ):
        assert expected in data["capabilities"], f"缺少能力 {expected}"


def test_capabilities_are_all_strings_and_unique() -> None:
    assert all(isinstance(c, str) and c for c in SERVER_CAPABILITIES)
    assert len(set(SERVER_CAPABILITIES)) == len(SERVER_CAPABILITIES)


# ---------------------------------------------------------------- Stage 8D.2 §34/§54
def test_deployment_scripts_declare_expected_version_and_contract() -> None:
    """部署脚本的版本 / Contract 必须与 Server 一致（单一事实源，禁止散落魔法数字）。"""
    from pathlib import Path

    root = Path(__file__).resolve().parent.parent.parent
    version_ps1 = root / "deployment" / "version.ps1"
    assert version_ps1.is_file(), "缺少 deployment/version.ps1（统一版本来源）"
    body = version_ps1.read_text(encoding="utf-8")
    assert f'$ExpectedServerVersion = "{__version__}"' in body
    assert f"$RequiredApiContract = {SERVER_API_CONTRACT}" in body


def test_install_and_status_scripts_reference_shared_version_source() -> None:
    """install/status/diagnose 必须引用统一版本来源，而不是各自写死版本号。"""
    from pathlib import Path

    root = Path(__file__).resolve().parent.parent.parent / "deployment" / "scripts"
    for name in ("install.ps1", "status.ps1", "diagnose.ps1", "start.ps1", "restart.ps1", "repair.ps1"):
        body = (root / name).read_text(encoding="utf-8")
        assert "version.ps1" in body, f"{name} 必须 dot-source deployment/version.ps1"
        assert "RequiredApiContract" in body, f"{name} 必须校验 api_contract"


if __name__ == "__main__":  # pragma: no cover
    import pytest

    raise SystemExit(pytest.main([__file__, "-q"]))
