"""Static security contracts for the offline diagnostics bundle."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def test_diagnostics_never_serializes_jellyfin_urls_or_raw_exception_text() -> None:
    script = (ROOT / "deployment" / "scripts" / "diagnose.ps1").read_text(encoding="utf-8-sig")
    assert "url_configured -Value ([bool]$masked.jellyfin.url)" in script
    assert "client_url_configured -Value ([bool]$masked.jellyfin.client_url)" in script
    assert "$masked.jellyfin.PSObject.Properties.Remove('url')" in script
    assert "$masked.jellyfin.PSObject.Properties.Remove('client_url')" in script
    assert "Jellyfin 不可达: $_" not in script
    assert "health 不可达: $_" not in script


def test_active_plan_uses_canonical_mediareview_port() -> None:
    plan = (ROOT / "docs" / "DEVELOPMENT_PLAN.md").read_text(encoding="utf-8")
    assert "检测端口 8765" not in plan
    assert "检测端口 8766" in plan
