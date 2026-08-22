"""配置系统测试。"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from app.core.config import ConfigLoadError, load_config


def test_defaults_without_config_file(tmp_path: Path) -> None:
    config = load_config(tmp_path / "missing.json")
    assert config.server.port == 8765
    assert config.server.host == "0.0.0.0"
    assert not config.jellyfin.is_configured()
    assert config.security.pairing_required is True
    assert config.features.sprites is True


def test_load_from_json_file(tmp_path: Path) -> None:
    config_file = tmp_path / "config.json"
    config_file.write_text(
        json.dumps(
            {
                "server": {"port": 9000},
                "jellyfin": {"url": "http://192.168.1.10:8096", "api_key": "secret123"},
                "storage": {"cache_max_gb": 5.5},
            }
        ),
        encoding="utf-8",
    )
    config = load_config(config_file)
    assert config.server.port == 9000
    assert config.jellyfin.url == "http://192.168.1.10:8096"
    assert config.jellyfin.api_key.get_secret_value() == "secret123"
    assert config.jellyfin.is_configured()
    assert config.storage.cache_max_gb == 5.5


def test_env_override(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("MEDIAREVIEW__SERVER__PORT", "9100")
    monkeypatch.setenv("MEDIAREVIEW__FEATURES__SPRITES", "false")
    config = load_config(tmp_path / "missing.json")
    assert config.server.port == 9100
    assert config.features.sprites is False


def test_env_override_keeps_secret(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """配置文件中的 API Key 不能被环境变量覆盖流程破坏成掩码。"""
    config_file = tmp_path / "config.json"
    config_file.write_text(json.dumps({"jellyfin": {"api_key": "real-key"}}), encoding="utf-8")
    monkeypatch.setenv("MEDIAREVIEW__SERVER__PORT", "9101")
    config = load_config(config_file)
    assert config.jellyfin.api_key.get_secret_value() == "real-key"


def test_masked_dict_hides_api_key(tmp_path: Path) -> None:
    config_file = tmp_path / "config.json"
    config_file.write_text(json.dumps({"jellyfin": {"api_key": "real-key"}}), encoding="utf-8")
    masked = load_config(config_file).masked_dict()
    assert masked["jellyfin"]["api_key"] == "********"
    assert "real-key" not in json.dumps(masked)


def test_corrupted_config_raises(tmp_path: Path) -> None:
    config_file = tmp_path / "config.json"
    config_file.write_text("{not json", encoding="utf-8")
    with pytest.raises(ConfigLoadError):
        load_config(config_file)


def test_non_object_config_raises(tmp_path: Path) -> None:
    config_file = tmp_path / "config.json"
    config_file.write_text("[1, 2, 3]", encoding="utf-8")
    with pytest.raises(ConfigLoadError):
        load_config(config_file)


def test_invalid_values_rejected(tmp_path: Path) -> None:
    config_file = tmp_path / "config.json"
    config_file.write_text(json.dumps({"server": {"port": 99999}}), encoding="utf-8")
    with pytest.raises(ValueError):
        load_config(config_file)
