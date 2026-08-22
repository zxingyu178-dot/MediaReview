"""阶段 3: 媒体库勾选接口测试。"""

from __future__ import annotations

import json
from pathlib import Path

from app.core.config import persist_jellyfin_user_id

# ---- user_id 持久化(独立单元) ----


def test_persist_user_id_writes_config(tmp_path: Path) -> None:
    config_file = tmp_path / "config.json"
    persist_jellyfin_user_id(config_file, "user-abc")
    payload = json.loads(config_file.read_text(encoding="utf-8"))
    assert payload["jellyfin"]["user_id"] == "user-abc"


def test_persist_user_id_preserves_other_settings(tmp_path: Path) -> None:
    config_file = tmp_path / "config.json"
    config_file.write_text(
        json.dumps({"jellyfin": {"api_key": "k", "url": "http://jf"}, "server": {"port": 9000}}),
        encoding="utf-8",
    )
    persist_jellyfin_user_id(config_file, "user-abc")
    payload = json.loads(config_file.read_text(encoding="utf-8"))
    assert payload["jellyfin"]["api_key"] == "k"
    assert payload["jellyfin"]["url"] == "http://jf"
    assert payload["server"]["port"] == 9000
    assert payload["jellyfin"]["user_id"] == "user-abc"


def test_persist_user_id_replaces_corrupt_file(tmp_path: Path) -> None:
    config_file = tmp_path / "config.json"
    config_file.write_text("{ not valid json", encoding="utf-8")
    persist_jellyfin_user_id(config_file, "user-abc")
    payload = json.loads(config_file.read_text(encoding="utf-8"))
    assert payload["jellyfin"]["user_id"] == "user-abc"


# ---- 接口 ----
# 使用 jellyfin_api_client: 已配置 api_key 与 user_id,并注入 mock transport


def test_list_libraries_with_explicit_user_id(jellyfin_api_client) -> None:
    client, _transport = jellyfin_api_client
    resp = client.get("/api/v1/libraries", params={"user_id": "user-0001"})
    assert resp.status_code == 200
    body = resp.json()
    assert body["success"] is True
    data = body["data"]
    assert [lib["jellyfin_id"] for lib in data] == ["lib-movies", "lib-photos"]
    assert all(lib["selected"] is True for lib in data)
    assert [lib["sort_order"] for lib in data] == [0, 1]
    assert data[0]["name"] == "电影"
    assert data[1]["collection_type"] == "homevideos"


def test_list_libraries_uses_configured_user_id(jellyfin_api_client) -> None:
    client, _transport = jellyfin_api_client
    # 未传 user_id,使用已持久化的 JellyfinConfig.user_id
    resp = client.get("/api/v1/libraries")
    assert resp.status_code == 200
    assert resp.json()["success"] is True
    assert len(resp.json()["data"]) == 2


def test_save_selection_updates_state(jellyfin_api_client) -> None:
    client, _transport = jellyfin_api_client
    # 先建立媒体库列表
    client.get("/api/v1/libraries", params={"user_id": "user-0001"})
    resp = client.put("/api/v1/libraries/selection", json={"selected": ["lib-movies"]})
    assert resp.status_code == 200
    by_id = {lib["jellyfin_id"]: lib for lib in resp.json()["data"]}
    assert by_id["lib-movies"]["selected"] is True
    assert by_id["lib-photos"]["selected"] is False


def test_save_selection_ignores_unknown_ids(jellyfin_api_client) -> None:
    client, _transport = jellyfin_api_client
    client.get("/api/v1/libraries", params={"user_id": "user-0001"})
    resp = client.put("/api/v1/libraries/selection", json={"selected": ["lib-movies", "not-exist"]})
    assert resp.status_code == 200
    by_id = {lib["jellyfin_id"]: lib for lib in resp.json()["data"]}
    assert by_id.get("not-exist") is None
    assert by_id["lib-movies"]["selected"] is True
    assert by_id["lib-photos"]["selected"] is False
