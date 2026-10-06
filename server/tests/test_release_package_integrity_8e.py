"""ReleasePackageIntegrityTest：实际产物与新鲜构建门禁。"""

import importlib.util
import json
import shutil
import subprocess
import zipfile
from pathlib import Path
from types import SimpleNamespace

import pytest

ROOT = Path(__file__).resolve().parents[2]


@pytest.fixture
def builder():
    spec = importlib.util.spec_from_file_location(
        "release_builder", ROOT / "scripts/build_deploy.py"
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class TestReleasePackageIntegrity:
    def test_binary_fixture_storage_never_falls_back_to_production(
        self, builder, tmp_path, monkeypatch
    ):
        monkeypatch.setattr(builder, "ROOT", tmp_path)

        def inspect_fixture(*args, **kwargs):
            root = Path(kwargs["env"]["MEDIAREVIEW_DATA_ROOT"])
            config = json.loads((root / "config/config.json").read_text())
            assert config["storage"]["data_root"] == str(root)
            raise RuntimeError("fixture inspected before process startup")

        monkeypatch.setattr(builder.subprocess, "Popen", inspect_fixture)
        with pytest.raises(RuntimeError, match="fixture inspected"):
            builder.verify_server_binary(tmp_path / "MediaReviewServer.exe")

    def test_old_server_exe_and_work_are_removed_before_rebuild(
        self, builder, tmp_path, monkeypatch
    ):
        monkeypatch.setattr(builder, "SERVER", tmp_path / "server")
        monkeypatch.setattr(builder, "DIST", tmp_path / "server/dist/MediaReviewServer")
        exe = builder.DIST / "MediaReviewServer.exe"
        exe.parent.mkdir(parents=True)
        exe.write_bytes(b"OLD")
        work = builder.SERVER / "build/pyinstaller"
        work.mkdir(parents=True)
        (work / "old.toc").write_text("OLD")

        def fresh(command, **kwargs):
            assert not exe.exists()
            assert not work.exists()
            assert "--clean" in command
            exe.parent.mkdir(parents=True)
            exe.write_bytes(b"FRESH")

        monkeypatch.setattr(builder.subprocess, "run", fresh)
        builder.build_exe()
        assert exe.read_bytes() == b"FRESH"

    def test_old_apk_is_removed_even_when_release_build_fails(self, builder, tmp_path, monkeypatch):
        android = tmp_path / "android"
        monkeypatch.setattr(builder, "ROOT", tmp_path)
        monkeypatch.setattr(builder, "APK_DIR", android / "app/build/outputs/apk")
        apk = builder.APK_DIR / "release/app-release.apk"
        apk.parent.mkdir(parents=True)
        apk.write_bytes(b"OLD")

        def failed(command, **kwargs):
            assert not apk.exists()
            assert "clean" in command and ":app:assembleRelease" in command
            raise subprocess.CalledProcessError(1, command)

        monkeypatch.setattr(builder.subprocess, "run", failed)
        with pytest.raises(subprocess.CalledProcessError):
            builder.build_release_apk()
        assert not apk.exists()

    @pytest.mark.parametrize(
        "field,value",
        [("version", "1.1.0"), ("api_contract", 1), ("status", "error"), ("capabilities", [])],
    )
    def test_wrong_binary_health_blocks_packaging(self, builder, field, value):
        health = {
            "status": "ok",
            "version": "1.2.2",
            "api_contract": 2,
            "capabilities": list(builder.REQUIRED_CAPABILITIES),
        }
        health[field] = value
        with pytest.raises(ValueError):
            builder.validate_binary_health(health)

    @pytest.mark.parametrize(
        "package,code,name",
        [
            ("com.mediareview.app", 14, "2.0.0-alpha7"),
            ("com.other.app", 15, "2.0.0-alpha8"),
            ("com.mediareview.app", 15, "1.2.2"),
        ],
    )
    def test_wrong_apk_manifest_blocks_packaging(self, builder, package, code, name):
        with pytest.raises(ValueError):
            builder.parse_apk_badging(
                f"package: name='{package}' versionCode='{code}' versionName='{name}'"
            )

    def test_manifest_and_handover_use_verified_separate_versions(self, builder, tmp_path):
        exe, apk = tmp_path / "MediaReviewServer.exe", tmp_path / "app-release.apk"
        exe.write_bytes(b"fresh-exe")
        apk.write_bytes(b"fresh-apk")
        evidence = {
            "server": {
                "version": "1.2.2",
                "api_contract": 2,
                "capabilities": list(builder.REQUIRED_CAPABILITIES),
            },
            "android": {
                "package": "com.mediareview.app",
                "version_code": 15,
                "version_name": "2.0.0-alpha8",
                "signature": "PASS",
            },
        }
        manifest = builder.release_manifest(evidence, "a" * 40, exe, apk)
        assert manifest["server_version"] == "1.2.2"
        assert manifest["android_version_name"] == "2.0.0-alpha8"
        assert manifest["android_version_code"] == 15
        assert manifest["apk_sha256"] == builder.sha256(apk)
        handover = builder.render_handover(manifest)
        assert "Android: 2.0.0-alpha8" in handover
        assert "Android versionCode: 15" in handover
        assert "Android: 1.2.2" not in handover

    def test_unsigned_apk_fails_verification(self, builder, tmp_path, monkeypatch):
        def run(command, **kwargs):
            if "dump" in command:
                return SimpleNamespace(
                    stdout="package: name='com.mediareview.app' versionCode='15' versionName='2.0.0-alpha8'"
                )
            raise subprocess.CalledProcessError(1, command)

        monkeypatch.setattr(builder.subprocess, "run", run)
        monkeypatch.setattr(builder, "android_build_tools", lambda: tmp_path)
        with pytest.raises(subprocess.CalledProcessError):
            builder.verify_apk(tmp_path / "app-release.apk")

    def test_cleanup_rejects_path_outside_expected_directory(self, builder, tmp_path):
        protected = tmp_path / "source"
        protected.mkdir()
        with pytest.raises(ValueError):
            builder.clean_generated(protected, tmp_path / "build")
        assert protected.is_dir()

    def test_all_zip_hashes_are_verified_and_corruption_is_rejected(self, builder, tmp_path):
        package = tmp_path / "rc"
        package.mkdir()
        (package / "app-release.apk").write_bytes(b"fresh-fixture")
        builder.write_sha256(package)
        archive_path = tmp_path / "rc.zip"
        with zipfile.ZipFile(archive_path, "w") as archive:
            for path in package.iterdir():
                archive.write(path, f"rc/{path.name}")
        assert builder.verify_zip(archive_path, "rc") == 1
        with zipfile.ZipFile(archive_path, "w") as archive:
            archive.write(package / "SHA256SUMS.txt", "rc/SHA256SUMS.txt")
            archive.writestr("rc/app-release.apk", b"tampered")
        with pytest.raises(ValueError):
            builder.verify_zip(archive_path, "rc")

    def test_build_input_fingerprint_includes_migrations_config_and_wrapper(self, builder):
        inputs = builder.source_inputs()
        for path in ("server/alembic.ini", "android/gradlew.bat", "android/gradlew"):
            assert inputs[path] == builder.sha256(ROOT / path)

    def test_scm_query_failure_stops_before_writing_data(self, tmp_path):
        script_dir = tmp_path / "scripts"
        script_dir.mkdir()
        original = (ROOT / "deployment/scripts/install.ps1").read_text(encoding="utf-8")
        script = script_dir / "install.ps1"
        script.write_text(
            original.replace("#requires -RunAsAdministrator", ""), encoding="utf-8-sig"
        )
        shutil.copy2(ROOT / "deployment/version.ps1", tmp_path / "version.ps1")
        data_root = tmp_path / "data"
        data_root.mkdir()

        def quote(path):
            return "'" + str(path).replace("'", "''") + "'"

        command = (
            "& { function Get-Service { [CmdletBinding()]param([string]$Name); Write-Error 'SCM unavailable' }; "
            f"& {quote(script)} -InstallDir {quote(tmp_path / 'install')} -DataRoot {quote(data_root)} }}; exit $LASTEXITCODE"
        )
        result = subprocess.run(
            [
                r"C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                command,
            ],
            capture_output=True,
            timeout=30,
        )
        assert result.returncode != 0
        assert not (data_root / "deploy_report.txt").exists()
