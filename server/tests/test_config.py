"""配置系统测试。"""

from __future__ import annotations

import json
from pathlib import Path

import pytest
from pydantic import SecretStr, ValidationError

from app.core.config import AppConfig, ConfigLoadError, JellyfinConfig, load_config

SERVER_KEY = "server-only-config-test-key"


def _percent_encode_every_byte(value: str) -> str:
    return "".join(f"%{byte:02X}" for byte in value.encode("utf-8"))


def test_defaults_without_config_file(tmp_path: Path) -> None:
    config = load_config(tmp_path / "missing.json")
    assert config.server.port == 8766
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


def test_jellyfin_url_is_normalized_and_preserves_safe_base_path() -> None:
    config = JellyfinConfig(
        url="  HTTPS://JF.Example:9443/jellyfin%20home/  ",
        api_key=SecretStr("placeholder"),
    )

    assert config.url == "https://jf.example:9443/jellyfin%20home"
    assert config.host == config.url


@pytest.mark.parametrize(
    "url",
    (
        f"http://jf.local:8096/?api_key={SERVER_KEY}",
        f"http://{SERVER_KEY}@jf.local:8096/base",
        f"http://user:{SERVER_KEY}@jf.local:8096/base",
        f"http://jf.local:8096/base#{SERVER_KEY}",
    ),
)
def test_jellyfin_url_rejects_secret_bearing_query_userinfo_or_fragment(url: str) -> None:
    with pytest.raises(ValidationError) as caught:
        JellyfinConfig(url=url, api_key=SecretStr(SERVER_KEY))

    assert SERVER_KEY not in str(caught.value)


@pytest.mark.parametrize(
    "url",
    (
        "ftp://jf.local/media",
        "jf.local:8096",
        "http:///missing-host",
        "http://jf.local:99999",
        "http://jf.local/base/../admin",
        "http://jf.local/base//nested",
        "http://jf local:8096",
    ),
)
def test_jellyfin_url_rejects_invalid_scheme_authority_port_or_base_path(url: str) -> None:
    with pytest.raises(ValidationError):
        JellyfinConfig(url=url)


def test_jellyfin_url_assignment_uses_the_same_validation_boundary() -> None:
    config = JellyfinConfig()

    with pytest.raises(ValidationError):
        config.url = f"http://jf.local/?api_key={SERVER_KEY}"


def test_app_config_validation_error_does_not_echo_rejected_jellyfin_url() -> None:
    with pytest.raises(ValidationError) as caught:
        AppConfig.model_validate({"jellyfin": {"url": f"http://{SERVER_KEY}@jf.local"}})

    assert SERVER_KEY not in str(caught.value)


@pytest.mark.parametrize(
    "url",
    (
        f"http://jf.local:8096/base/{SERVER_KEY}",
        f"http://{SERVER_KEY}.jf.local:8096/base",
        f"http://jf.local:8096/base/{SERVER_KEY.upper()}",
        f"http://jf.local:8096/base/{_percent_encode_every_byte(SERVER_KEY)}",
    ),
)
def test_jellyfin_config_construction_rejects_api_key_in_any_url_component(url: str) -> None:
    with pytest.raises(ValidationError) as caught:
        JellyfinConfig(url=url, api_key=SecretStr(SERVER_KEY))

    assert SERVER_KEY.casefold() not in str(caught.value).casefold()


def test_jellyfin_url_assignment_rejects_existing_api_key_without_mutating_config() -> None:
    config = JellyfinConfig(
        url="https://jf.example:9443/safe-base",
        api_key=SecretStr(SERVER_KEY),
    )

    with pytest.raises(ValidationError) as caught:
        config.url = f"https://jf.example:9443/base/{SERVER_KEY}"

    assert SERVER_KEY not in str(caught.value)
    assert config.url == "https://jf.example:9443/safe-base"


def test_jellyfin_api_key_assignment_rejects_existing_url_without_mutating_config() -> None:
    contaminated_url = f"https://jf.example:9443/base/{SERVER_KEY}"
    config = JellyfinConfig(url=contaminated_url)

    with pytest.raises(ValidationError) as caught:
        config.api_key = SecretStr(SERVER_KEY)

    assert SERVER_KEY not in str(caught.value)
    assert config.api_key.get_secret_value() == ""
    assert config.url == contaminated_url


def test_jellyfin_config_keeps_valid_base_path_without_api_key() -> None:
    config = JellyfinConfig(
        url="HTTPS://JF.Example:9443/media%20server/",
        api_key=SecretStr(SERVER_KEY),
    )

    assert config.url == "https://jf.example:9443/media%20server"
    assert config.api_key.get_secret_value() == SERVER_KEY
