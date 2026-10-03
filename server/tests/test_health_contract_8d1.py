"""Stage 8D.1 §4~§10: Server Health 的 API Contract 合同。

- `/api/v1/system/health` 必须返回 `api_contract` 与 `capabilities`；
- `api_contract` 是 App 判断兼容性的**正式字段**(不是 `version` 字符串比较)；
- Server 版本与 API Contract 分开管理：版本给人看,Contract 给 App 判断。
"""

from __future__ import annotations

from fastapi.testclient import TestClient

from app import SERVER_API_CONTRACT, SERVER_CAPABILITIES, __version__


def test_server_version_is_120() -> None:
    """Stage 8D.1 §8: Server 从 1.1.0 升到 1.2.0。"""
    assert __version__ == "1.2.0"


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


if __name__ == "__main__":  # pragma: no cover
    import pytest

    raise SystemExit(pytest.main([__file__, "-q"]))
