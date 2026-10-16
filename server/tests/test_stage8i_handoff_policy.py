"""Stage8I official handoff must contain approved evidence only."""

from __future__ import annotations

import importlib.util
from pathlib import Path

import pytest

SCRIPT = Path(__file__).resolve().parents[2] / "scripts/deliver_stage_handoff.py"
spec = importlib.util.spec_from_file_location("stage8i_delivery", SCRIPT)
delivery = importlib.util.module_from_spec(spec)
assert spec and spec.loader
spec.loader.exec_module(delivery)


def test_stage8i_source_policy_rejects_media_binary_and_secrets() -> None:
    assert delivery.stage8i_approved_source("server/app/api/v1/system.py")
    assert not delivery.stage8i_approved_source("android/app/src/androidTest/assets/clip.mp4")
    assert not delivery.stage8i_approved_source("android/app/src/androidTest/assets/stream.ts")
    assert not delivery.stage8i_approved_source("server/config/secrets.env")
    assert not delivery.stage8i_approved_source("server/dist/MediaReviewServer.exe")


def test_stage8i_requires_exactly_twelve_top_level_documents(tmp_path: Path) -> None:
    for name in delivery.STAGE8I_DOCS:
        (tmp_path / name).write_text("approved\n", encoding="utf-8")
    delivery.validate_stage8i_payload(tmp_path)

    (tmp_path / "12_EXTRA.md").write_text("extra\n", encoding="utf-8")
    with pytest.raises(ValueError, match="top-level"):
        delivery.validate_stage8i_payload(tmp_path)


def test_stage8i_payload_rejects_binary_even_outside_source(tmp_path: Path) -> None:
    for name in delivery.STAGE8I_DOCS:
        (tmp_path / name).write_text("approved\n", encoding="utf-8")
    media = tmp_path / "logs" / "recording.mp4"
    media.parent.mkdir()
    media.write_bytes(b"fake media")
    with pytest.raises(ValueError, match="approved|binary"):
        delivery.validate_stage8i_payload(tmp_path)


def test_stage8i_payload_rejects_private_key_inside_text(tmp_path: Path) -> None:
    for name in delivery.STAGE8I_DOCS:
        (tmp_path / name).write_text("approved\n", encoding="utf-8")
    (tmp_path / "logs").mkdir()
    marker = "-" * 5
    (tmp_path / "logs" / "result.txt").write_text(
        f"{marker}BEGIN PRIVATE KEY{marker}\nnot-real-key\n{marker}END PRIVATE KEY{marker}\n",
        encoding="utf-8",
    )
    with pytest.raises(ValueError, match="secret"):
        delivery.validate_stage8i_payload(tmp_path)


@pytest.mark.parametrize(
    "suffix,assignment",
    [
        ("properties", "api_key=stage8iSyntheticCredential123"),
        ("json", '"token": "stage8iSyntheticCredential123"'),
        ("txt", "password: stage8iSyntheticCredential123"),
    ],
)
def test_stage8i_payload_rejects_plaintext_credentials_without_echoing_values(
    tmp_path: Path, suffix: str, assignment: str
) -> None:
    for name in delivery.STAGE8I_DOCS:
        (tmp_path / name).write_text("approved\n", encoding="utf-8")
    evidence = (
        tmp_path / "logs" / "result.txt"
        if suffix == "txt"
        else tmp_path / "source" / "server" / f"result.{suffix}"
    )
    evidence.parent.mkdir(parents=True)
    evidence.write_text(f"safe line\n{assignment}\n", encoding="utf-8")
    with pytest.raises(ValueError) as error:
        delivery.validate_stage8i_payload(tmp_path)
    assert evidence.relative_to(tmp_path).as_posix() in str(error.value)
    assert ":2" in str(error.value)
    assert "stage8iSyntheticCredential123" not in str(error.value)


@pytest.mark.parametrize(
    "assignment",
    [
        "api_key=<REDACTED>",
        '"token": "***"',
        "password: ${PASSWORD}",
    ],
)
def test_stage8i_payload_allows_explicit_redaction(tmp_path: Path, assignment: str) -> None:
    for name in delivery.STAGE8I_DOCS:
        (tmp_path / name).write_text("approved\n", encoding="utf-8")
    evidence = tmp_path / "logs" / "result.txt"
    evidence.parent.mkdir()
    evidence.write_text(assignment + "\n", encoding="utf-8")
    delivery.validate_stage8i_payload(tmp_path)


@pytest.mark.parametrize(
    "suffix,template",
    [
        ("json", '{{"token":"{}"}}'),
        ("json", '{{"settings":{{"api_key":"{}"}}}}'),
        ("kt", 'val token = "{}"'),
        ("txt", "Authorization: Bearer {}"),
    ],
)
def test_stage8i_payload_rejects_embedded_and_header_credentials(
    tmp_path: Path, suffix: str, template: str
) -> None:
    marker = "stage8iSynthetic" + "Credential123"
    for name in delivery.STAGE8I_DOCS:
        (tmp_path / name).write_text("approved\n", encoding="utf-8")
    evidence = (
        tmp_path / "logs" / "result.txt"
        if suffix == "txt"
        else tmp_path / "source" / "server" / f"result.{suffix}"
    )
    evidence.parent.mkdir(parents=True)
    evidence.write_text(template.format(marker) + "\n", encoding="utf-8")
    with pytest.raises(ValueError) as error:
        delivery.validate_stage8i_payload(tmp_path)
    assert evidence.relative_to(tmp_path).as_posix() + ":1" in str(error.value)
    assert marker not in str(error.value)


def test_stage8i_payload_allows_runtime_token_expressions(tmp_path: Path) -> None:
    for name in delivery.STAGE8I_DOCS:
        (tmp_path / name).write_text("approved\n", encoding="utf-8")
    source = tmp_path / "source" / "server" / "runtime.py"
    source.parent.mkdir(parents=True)
    source.write_text(
        'token = response.json()["token"]\n'
        'token = _pair(client, device_id="fixture-device")\n'
        'api_key = SecretStr("mock-api-key")\n',
        encoding="utf-8",
    )
    delivery.validate_stage8i_payload(tmp_path)


def test_stage8i_payload_rejects_source_dictionary_literal(tmp_path: Path) -> None:
    marker = "stage8iSynthetic" + "Credential123"
    key = "to" + "ken"
    for name in delivery.STAGE8I_DOCS:
        (tmp_path / name).write_text("approved\n", encoding="utf-8")
    source = tmp_path / "source" / "server" / "config.py"
    source.parent.mkdir(parents=True)
    source.write_text('config = {"' + key + '": "' + marker + '"}\n', encoding="utf-8")
    with pytest.raises(ValueError) as error:
        delivery.validate_stage8i_payload(tmp_path)
    assert "source/server/config.py:1" in str(error.value)
    assert marker not in str(error.value)


@pytest.mark.parametrize(
    "suffix,template",
    [
        ("kt", 'private val apiKey = "{}"'),
        ("kt", 'private const val API_KEY = "{}"'),
        ("java", 'private static final String PASSWORD = "{}";'),
        ("py", 'headers = {{"Authorization": "Bearer {}"}}'),
        ("txt", "2026-10-16T12:00:00Z INFO Authorization: Bearer {}"),
        ("txt", "INFO request api_key={}"),
        ("txt", "GET /api/v1/media?api_key={} HTTP/1.1"),
    ],
)
def test_stage8i_payload_rejects_prefixed_credentials(
    tmp_path: Path, suffix: str, template: str
) -> None:
    marker = "stage8iSynthetic" + "Credential123"
    for name in delivery.STAGE8I_DOCS:
        (tmp_path / name).write_text("approved\n", encoding="utf-8")
    evidence = (
        tmp_path / "logs" / "result.txt"
        if suffix == "txt"
        else tmp_path / "source" / "server" / f"result.{suffix}"
    )
    evidence.parent.mkdir(parents=True)
    evidence.write_text(template.format(marker) + "\n", encoding="utf-8")
    with pytest.raises(ValueError) as error:
        delivery.validate_stage8i_payload(tmp_path)
    assert evidence.relative_to(tmp_path).as_posix() + ":1" in str(error.value)
    assert marker not in str(error.value)


@pytest.mark.parametrize(
    "line",
    [
        "INFO request api_key=<REDACTED>",
        "2026-10-16T12:00:00Z Authorization: Bearer ***",
        "仅说明 `?api_key=` 参数名",
    ],
)
def test_stage8i_payload_allows_prefixed_redaction(tmp_path: Path, line: str) -> None:
    for name in delivery.STAGE8I_DOCS:
        (tmp_path / name).write_text("approved\n", encoding="utf-8")
    evidence = tmp_path / "logs" / "result.txt"
    evidence.parent.mkdir()
    evidence.write_text(line + "\n", encoding="utf-8")
    delivery.validate_stage8i_payload(tmp_path)


def test_stage8i_payload_allows_dynamic_and_redacted_source_declarations(tmp_path: Path) -> None:
    for name in delivery.STAGE8I_DOCS:
        (tmp_path / name).write_text("approved\n", encoding="utf-8")
    source = tmp_path / "source" / "android" / "Runtime.kt"
    source.parent.mkdir(parents=True)
    source.write_text(
        'private val apiKey = BuildConfig.API_KEY\nprivate const val API_KEY = "<REDACTED>"\n',
        encoding="utf-8",
    )
    delivery.validate_stage8i_payload(tmp_path)
