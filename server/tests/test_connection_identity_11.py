"""MediaReview 1.1 Task 3: client URL and pairing identity contracts."""

from __future__ import annotations

import hashlib
from pathlib import Path

import httpx
import pytest
from alembic import command
from alembic.config import Config
from fastapi.testclient import TestClient
from pydantic import SecretStr, ValidationError
from sqlalchemy import create_engine, inspect, text

from app.adapters.jellyfin.client import JellyfinClient
from app.api.v1.jellyfin import jellyfin_client
from app.core.config import AppConfig, JellyfinConfig, SecurityConfig, StorageConfig
from app.db.migrate import _server_root
from app.db.models import MediaCacheIndex, PairedDevice
from app.main import create_app
from app.services import pairing


def _alembic_config(database_url: str) -> Config:
    root = _server_root()
    config = Config(str(root / "alembic.ini"))
    config.set_main_option("script_location", str(root / "app" / "db" / "migrations"))
    config.set_main_option("sqlalchemy.url", database_url)
    return config


def test_default_server_port_is_canonical_8766(tmp_path: Path) -> None:
    config = AppConfig(storage=StorageConfig(data_root=str(tmp_path)))

    assert config.server.port == 8766


def test_client_url_uses_same_validation_and_secret_exclusion() -> None:
    config = JellyfinConfig(
        url="http://127.0.0.1:8096/jellyfin",
        client_url=" HTTPS://Jellyfin.Lan:9443/jellyfin/ ",
        client_host_allowlist=["jellyfin.lan"],
        api_key=SecretStr("server-only-key"),
    )

    assert config.model_dump().get("client_url") == "https://jellyfin.lan:9443/jellyfin"

    with pytest.raises(ValidationError) as caught:
        JellyfinConfig(
            client_url="https://jellyfin.lan/base/server-only-key",
            api_key=SecretStr("server-only-key"),
        )
    assert "server-only-key" not in str(caught.value)

    for loopback in ("http://127.0.0.1:8096", "http://localhost:8096", "http://[::1]:8096"):
        with pytest.raises(ValidationError):
            JellyfinConfig(client_url=loopback)


@pytest.mark.parametrize(
    "client_url",
    (
        "http://192.168.31.20:0/jellyfin",
        "http://jellyfin:8096/jellyfin",
        "http://jellyfin.internal:8096/jellyfin",
        "http://jellyfin.local:8096/jellyfin",
    ),
)
def test_client_url_rejects_invalid_port_and_server_only_hosts_without_allowlist(
    client_url: str,
) -> None:
    with pytest.raises(ValidationError):
        JellyfinConfig(client_url=client_url)


def test_explicit_allowlist_controls_local_client_hostname_and_request_host() -> None:
    config = JellyfinConfig(
        url="http://127.0.0.1:8096/jellyfin",
        client_url="http://jellyfin.internal:8096/jellyfin",
        client_host_allowlist=["JELLYFIN.INTERNAL"],
    )
    assert config.client_url == "http://jellyfin.internal:8096/jellyfin"

    derived = JellyfinConfig(
        url="http://127.0.0.1:8096/jellyfin",
        client_host_allowlist=["jellyfin.internal"],
    )
    assert derived.client_base_url("JELLYFIN.INTERNAL") == (
        "http://jellyfin.internal:8096/jellyfin"
    )


def test_derived_client_url_rejects_loopback_or_unspecified_request_host() -> None:
    config = JellyfinConfig(url="http://127.0.0.1:8096/jellyfin")

    for host in ("127.0.0.1", "localhost", "::1", "0.0.0.0"):
        with pytest.raises(ValueError):
            config.client_base_url(host)


def test_remote_playback_replaces_only_configured_loopback_host(tmp_path: Path) -> None:
    settings = AppConfig(
        jellyfin=JellyfinConfig(
            url="http://127.0.0.1:8096/jellyfin",
            api_key=SecretStr("test-server-key"),
            user_id="user-1",
        ),
        storage=StorageConfig(data_root=str(tmp_path / "data")),
        security=SecurityConfig(pairing_required=False, pairing_code_remote_allowed=True),
    )
    app = create_app(settings)

    async def override_client():
        async with JellyfinClient(
            settings.jellyfin, transport=httpx.MockTransport(lambda _: httpx.Response(404))
        ) as client:
            yield client

    app.dependency_overrides[jellyfin_client] = override_client
    with TestClient(app, base_url="http://192.168.31.20:8766") as client:
        with client.app.state.database.session() as session:
            session.add(
                MediaCacheIndex(
                    media_id="lan-playback",
                    jellyfin_id="video/one",
                    library_id="library-1",
                    name="One.mp4",
                    media_type="video",
                    fingerprint="fp-lan-playback",
                )
            )
            session.commit()

        response = client.get("/api/v1/media/lan-playback/playback")

    assert response.status_code == 200
    payload = response.json()["data"]
    assert payload["stream_url"] == (
        "http://192.168.31.20:8096/jellyfin/Videos/video%2Fone/stream?static=true"
    )
    assert payload["requires_jellyfin_auth"] is True
    assert "test-server-key" not in response.text
    assert "127.0.0.1" not in response.text


def test_configured_client_url_wins_over_request_host(tmp_path: Path) -> None:
    settings = AppConfig(
        jellyfin=JellyfinConfig(
            url="http://127.0.0.1:8096/jellyfin",
            client_url="https://jellyfin.home:9443/jellyfin",
            api_key=SecretStr("test-server-key"),
            user_id="user-1",
        ),
        storage=StorageConfig(data_root=str(tmp_path / "data")),
        security=SecurityConfig(pairing_required=False, pairing_code_remote_allowed=True),
    )
    app = create_app(settings)

    async def override_client():
        async with JellyfinClient(
            settings.jellyfin, transport=httpx.MockTransport(lambda _: httpx.Response(404))
        ) as client:
            yield client

    app.dependency_overrides[jellyfin_client] = override_client
    with TestClient(app, base_url="http://192.168.31.20:8766") as client:
        with client.app.state.database.session() as session:
            session.add(
                MediaCacheIndex(
                    media_id="client-url-playback",
                    jellyfin_id="video-2",
                    library_id="library-1",
                    name="Two.mp4",
                    media_type="video",
                    fingerprint="fp-client-url-playback",
                )
            )
            session.commit()
        response = client.get("/api/v1/media/client-url-playback/playback")

    assert response.status_code == 200
    assert response.json()["data"]["stream_url"].startswith(
        "https://jellyfin.home:9443/jellyfin/Videos/video-2/stream"
    )


def test_diagnostics_config_does_not_expose_server_only_jellyfin_host(tmp_path: Path) -> None:
    settings = AppConfig(
        jellyfin=JellyfinConfig(
            url="http://127.0.0.1:8096/jellyfin",
            api_key=SecretStr("test-server-key"),
        ),
        storage=StorageConfig(data_root=str(tmp_path / "data")),
        security=SecurityConfig(pairing_required=False),
    )
    with TestClient(create_app(settings)) as client:
        response = client.get("/api/v1/system/info")

    assert response.status_code == 200
    assert "127.0.0.1" not in response.text
    assert "test-server-key" not in response.text


def test_three_pairings_for_same_normalized_installation_rotate_one_row(tmp_path: Path) -> None:
    settings = AppConfig(
        storage=StorageConfig(data_root=str(tmp_path / "data")),
        security=SecurityConfig(pairing_required=False, pairing_code_remote_allowed=True),
    )
    app = create_app(settings)
    with TestClient(app) as client:
        tokens: list[str] = []
        for submitted_id in (" Install-ABC ", "install-abc", "INSTALL-ABC"):
            code = client.post("/api/v1/pairing/code").json()["data"]["code"]
            response = client.post(
                "/api/v1/pairing/verify",
                json={"device_id": submitted_id, "code": code},
            )
            assert response.status_code == 200
            tokens.append(response.json()["data"]["token"])

        with client.app.state.database.session() as session:
            devices = session.query(PairedDevice).all()
            assert len(devices) == 1
            assert devices[0].installation_id == "install-abc"
            assert devices[0].token_hash == hashlib.sha256(tokens[-1].encode()).hexdigest()
            assert pairing.authenticate(session, tokens[0]) is None
            assert pairing.authenticate(session, tokens[1]) is None
            assert pairing.authenticate(session, tokens[2]) is not None


def test_0012_upgrade_merges_only_exact_normalized_identity_and_keeps_newest_token(
    tmp_path: Path,
) -> None:
    database_url = f"sqlite:///{tmp_path / 'pairing-upgrade.db'}"
    config = _alembic_config(database_url)
    command.upgrade(config, "0010")
    engine = create_engine(database_url)
    with engine.begin() as connection:
        connection.execute(
            text(
                """
                INSERT INTO paired_device
                    (device_id, name, paired_at, token_hash, revoked, last_seen_at)
                VALUES
                    (' Install-ABC ', '旧手机', '2026-01-01 00:00:00', :old_hash, 0, NULL),
                    ('install-abc', '新手机', '2026-02-01 00:00:00', :new_hash, 0, NULL),
                    ('historical-id-1', '历史一', '2026-03-01 00:00:00', :h1, 0, NULL),
                    ('historical-id-2', '历史二', '2026-03-02 00:00:00', :h2, 0, NULL),
                    ('historical-id-3', '历史三', '2026-03-03 00:00:00', :h3, 0, NULL)
                """
            ),
            {
                "old_hash": "a" * 64,
                "new_hash": "b" * 64,
                "h1": "c" * 64,
                "h2": "d" * 64,
                "h3": "e" * 64,
            },
        )

    command.upgrade(config, "0012")

    assert "installation_id" in {
        column["name"] for column in inspect(engine).get_columns("paired_device")
    }
    with engine.connect() as connection:
        rows = connection.execute(
            text(
                "SELECT device_id, installation_id, token_hash "
                "FROM paired_device ORDER BY installation_id"
            )
        ).all()
    assert len(rows) == 4
    merged = next(row for row in rows if row.installation_id == "install-abc")
    assert merged.device_id == "install-abc"
    assert merged.token_hash == "b" * 64
    assert {
        row.installation_id for row in rows if row.installation_id.startswith("historical-")
    } == {
        "historical-id-1",
        "historical-id-2",
        "historical-id-3",
    }


def test_0012_upgrade_does_not_let_newer_malformed_hash_replace_valid_token(
    tmp_path: Path,
) -> None:
    database_url = f"sqlite:///{tmp_path / 'pairing-malformed-upgrade.db'}"
    config = _alembic_config(database_url)
    command.upgrade(config, "0010")
    engine = create_engine(database_url)
    with engine.begin() as connection:
        connection.execute(
            text(
                "INSERT INTO paired_device "
                "(device_id, name, paired_at, token_hash, revoked, last_seen_at) VALUES "
                "(' Install-Valid ', 'valid', '2026-01-01', :valid, 0, NULL), "
                "('install-valid', 'malformed', '2026-12-01', 'bad', 0, NULL), "
                "('INSTALL-VALID', 'revoked', '2027-01-01', :revoked, 1, NULL)"
            ),
            {"valid": "a" * 64, "revoked": "b" * 64},
        )

    command.upgrade(config, "0012")
    with engine.connect() as connection:
        row = connection.execute(text("SELECT name, token_hash, revoked FROM paired_device")).one()
    assert row == ("valid", "a" * 64, 0)

    command.downgrade(config, "0010")
    assert "installation_id" not in {
        column["name"] for column in inspect(engine).get_columns("paired_device")
    }


def test_0012_downgrade_preserves_retained_device_data(tmp_path: Path) -> None:
    database_url = f"sqlite:///{tmp_path / 'pairing-downgrade.db'}"
    config = _alembic_config(database_url)
    command.upgrade(config, "0012")
    engine = create_engine(database_url)
    with engine.begin() as connection:
        connection.execute(
            text(
                "INSERT INTO paired_device "
                "(device_id, installation_id, name, paired_at, token_hash, revoked) "
                "VALUES ('install-stable', 'install-stable', '手机', CURRENT_TIMESTAMP, :digest, 0)"
            ),
            {"digest": "f" * 64},
        )

    command.downgrade(config, "0010")

    assert "installation_id" not in {
        column["name"] for column in inspect(engine).get_columns("paired_device")
    }
    with engine.connect() as connection:
        row = connection.execute(
            text("SELECT device_id, name, token_hash FROM paired_device")
        ).one()
    assert row == ("install-stable", "手机", "f" * 64)
