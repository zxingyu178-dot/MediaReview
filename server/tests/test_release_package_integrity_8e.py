"""ReleasePackageIntegrityTest：实际产物与新鲜构建门禁。"""

import datetime as dt
import hashlib
import importlib.util
import json
import re
import shutil
import subprocess
import sys
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


def test_release_build_reuses_cache_and_explicit_gradle(builder, tmp_path, monkeypatch):
    monkeypatch.setattr(builder, "ROOT", tmp_path)
    monkeypatch.setattr(builder, "APK_DIR", tmp_path / "android/app/build/outputs/apk")
    apk = builder.APK_DIR / "release/app-release.apk"
    apk.parent.mkdir(parents=True)
    marker = apk.parent / "incremental-marker"
    marker.write_text("keep", encoding="utf-8")
    executable = str(tmp_path / "registered-gradle.bat")
    monkeypatch.setenv("MEDIAREVIEW_GRADLE_EXECUTABLE", executable)
    calls = []
    def run(command, **kwargs):
        calls.append(command)
        apk.parent.mkdir(parents=True, exist_ok=True)
        apk.write_bytes(b"fixture-apk")
    monkeypatch.setattr(builder.subprocess, "run", run)
    assert builder.build_release_apk() == apk
    assert calls[0][0] == executable
    assert "clean" not in calls[0]
    assert marker.exists()


def test_server_build_uses_invoking_python_in_worktree(builder, tmp_path, monkeypatch):
    monkeypatch.setattr(builder, "SERVER", tmp_path / "server")
    monkeypatch.setattr(builder, "DIST", builder.SERVER / "dist/MediaReviewServer")
    builder.SERVER.mkdir()
    calls = []
    def run(command, **kwargs):
        calls.append(command)
        builder.DIST.mkdir(parents=True)
        (builder.DIST / "MediaReviewServer.exe").write_bytes(b"fixture-exe")
    monkeypatch.setattr(builder.subprocess, "run", run)
    builder.build_exe()
    assert calls[0][0] == sys.executable


def configure_prepared_builder(builder, tmp_path, monkeypatch, *, historical=False):
    """只替代昂贵构建/二进制探针，保留真实 receipt、文件复制与 ZIP 门禁。"""
    for name, path in {
        "ROOT": tmp_path,
        "SERVER": tmp_path / "server",
        "DIST": tmp_path / "server/dist/MediaReviewServer",
        "DEPLOY": tmp_path / "deployment",
        "OUT": tmp_path / "deploy_handoff",
        "APK_DIR": tmp_path / "android/app/build/outputs/apk",
        "EVIDENCE": tmp_path / "deploy_handoff/stage8i-build-evidence.json",
    }.items():
        monkeypatch.setattr(builder, name, path)
    source_paths = (
        "server/app/__init__.py",
        "server/pyproject.toml",
        "android/app/build.gradle.kts",
    )
    package_paths = (
        *(f"deployment/scripts/{name}" for name in builder.ALL_SCRIPTS),
        "deployment/scripts/upgrade-aihome-managed.ps1",
        "deployment/version.ps1",
        "deployment/config.example.json",
        "deployment/README.md",
        "LICENSE",
        "THIRD_PARTY_NOTICES.md",
        "UPGRADE_ROLLBACK.md",
        "USER_INSTALL_GUIDE.md",
        "USER_LAN_VALIDATION.md",
    )
    for relative in set(source_paths + package_paths):
        target = tmp_path / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        original = ROOT / relative
        target.write_bytes(original.read_bytes() if original.is_file() else b"fixture")
    if historical:
        profile = {
            "VERSION": builder.VERIFIED_RC_SERVER_VERSION,
            "ANDROID_CODE": builder.VERIFIED_RC_ANDROID_CODE,
            "ANDROID_VERSION": builder.VERIFIED_RC_ANDROID_VERSION,
        }
        for key, value in profile.items():
            monkeypatch.setattr(builder, key, value)
        monkeypatch.setattr(
            builder,
            "REQUIRED_CAPABILITIES",
            tuple(cap for cap in builder.REQUIRED_CAPABILITIES if cap != "folder_tree_v1"),
        )
        declarations = {
            "server/app/__init__.py": (
                r'^__version__ = "[^"\n]+"',
                f'__version__ = "{builder.VERSION}"',
            ),
            "server/pyproject.toml": (r'^version = "[^"\n]+"', f'version = "{builder.VERSION}"'),
            "deployment/version.ps1": (
                r'^\$ExpectedServerVersion = "[^"\n]+"',
                f'$ExpectedServerVersion = "{builder.VERSION}"',
            ),
            "android/app/build.gradle.kts": (
                r"versionCode = \d+",
                f"versionCode = {builder.ANDROID_CODE}",
            ),
        }
        for relative, (pattern, replacement) in declarations.items():
            path = tmp_path / relative
            original = path.read_text(encoding="utf-8")
            changed, count = re.subn(
                pattern,
                lambda match, replacement=replacement: replacement,
                original,
                flags=re.MULTILINE,
            )
            assert count == 1
            path.write_text(changed, encoding="utf-8")
        gradle = tmp_path / "android/app/build.gradle.kts"
        original = gradle.read_text(encoding="utf-8")
        changed, count = re.subn(
            r'versionName = "[^"\n]+"',
            lambda match: f'versionName = "{builder.ANDROID_VERSION}"',
            original,
        )
        assert count == 1
        gradle.write_text(changed, encoding="utf-8")
    exe = builder.DIST / "MediaReviewServer.exe"
    exe.parent.mkdir(parents=True)
    exe.write_bytes(b"fresh-exe")
    (exe.parent / "_internal").mkdir()
    (exe.parent / "_internal/runtime.dll").write_bytes(b"runtime")
    apk = builder.APK_DIR / "release/app-release.apk"
    apk.parent.mkdir(parents=True)
    apk.write_bytes(b"fresh-apk")
    ffmpeg = tmp_path / "third_party/ffmpeg/ffmpeg.exe"
    ffmpeg.parent.mkdir(parents=True)
    ffmpeg.write_bytes(b"fixture-ffmpeg")
    monkeypatch.setattr(builder, "git_head", lambda **kwargs: "a" * 40)
    monkeypatch.setattr(
        builder,
        "source_inputs",
        lambda: {relative: builder.sha256(tmp_path / relative) for relative in source_paths},
    )
    monkeypatch.setattr(builder, "build_exe", lambda: None)
    monkeypatch.setattr(builder, "build_release_apk", lambda: apk)
    monkeypatch.setattr(
        builder,
        "verify_server_binary",
        lambda path: {
            "status": "ok",
            "version": builder.VERSION,
            "api_contract": builder.API_CONTRACT,
            "capabilities": list(builder.REQUIRED_CAPABILITIES),
        },
    )
    monkeypatch.setattr(
        builder,
        "verify_apk",
        lambda path: {
            "package": builder.ANDROID_PACKAGE,
            "version_code": builder.ANDROID_CODE,
            "version_name": builder.ANDROID_VERSION,
            "signature": "PASS",
        },
    )
    builder.prepare()
    return builder


@pytest.fixture
def prepared_builder(builder, tmp_path, monkeypatch):
    return configure_prepared_builder(builder, tmp_path, monkeypatch)


class TestReleasePackageInputs8E1:
    @staticmethod
    def assert_no_delivery(builder):
        assert set(builder.OUT.iterdir()) == {builder.EVIDENCE}

    @pytest.mark.parametrize(
        "relative",
        [
            "android/app/build/outputs/apk/release/app-release.apk",
            "server/dist/MediaReviewServer/_internal/runtime.dll",
            "server/app/__init__.py",
            "USER_INSTALL_GUIDE.md",
        ],
    )
    def test_input_change_after_zip_verification_blocks_delivery(
        self, prepared_builder, monkeypatch, relative
    ):
        builder = prepared_builder
        verify = builder.verify_zip

        def drift(path, name):
            count = verify(path, name)
            (builder.ROOT / relative).write_bytes(b"REPLACED-AFTER-ZIP")
            return count

        monkeypatch.setattr(builder, "verify_zip", drift)
        with pytest.raises(ValueError, match="输入|证据"):
            builder.package_verified()
        self.assert_no_delivery(builder)

    @pytest.mark.parametrize(
        "relative",
        ["MediaReviewServer/MediaReviewServer.exe", "app-release.apk", "USER_INSTALL_GUIDE.md"],
    )
    def test_staging_change_before_checksums_blocks_delivery(
        self, prepared_builder, monkeypatch, relative
    ):
        builder = prepared_builder
        write = builder.write_sha256

        def drift(package):
            (package / relative).write_bytes(b"REPLACED-STAGING")
            write(package)

        monkeypatch.setattr(builder, "write_sha256", drift)
        with pytest.raises(ValueError, match="receipt|证据"):
            builder.package_verified()
        self.assert_no_delivery(builder)

    def test_independent_apk_exports_verified_staging_and_rejects_copy_tampering(
        self, prepared_builder, monkeypatch
    ):
        builder = prepared_builder
        copy = builder.shutil.copy2
        exported = []

        def tamper(source, target):
            result = copy(source, target)
            if target.name == builder.current_apk_name():
                exported.append(source)
                target.write_bytes(b"REPLACED-EXPORTED-APK")
            return result

        monkeypatch.setattr(builder.shutil, "copy2", tamper)
        with pytest.raises(ValueError, match="APK|receipt|证据"):
            builder.package_verified()
        assert len(exported) == 1
        assert exported[0].parent.parent == builder.OUT
        assert exported[0].name == "app-release.apk"
        self.assert_no_delivery(builder)

    def test_copy_failure_cleans_partial_delivery(self, prepared_builder, monkeypatch):
        builder = prepared_builder
        copy = builder.shutil.copy2

        def failed(source, target):
            copy(source, target)
            raise OSError("injected copy failure")

        monkeypatch.setattr(builder.shutil, "copy2", failed)
        with pytest.raises(OSError, match="injected"):
            builder.package_verified()
        self.assert_no_delivery(builder)

    @pytest.mark.parametrize(
        "relative",
        ["MediaReviewServer/MediaReviewServer.exe", "RELEASE_MANIFEST.json", "new-file.txt"],
    )
    def test_rehashed_zip_change_during_apk_export_blocks_delivery(
        self, prepared_builder, monkeypatch, relative
    ):
        builder = prepared_builder
        copy = builder.shutil.copy2

        def tamper(source, target):
            result = copy(source, target)
            if target.name == builder.current_apk_name():
                archive_path = next(builder.OUT.glob("*.zip"))
                prefix = archive_path.stem + "/"
                with zipfile.ZipFile(archive_path) as archive:
                    members = {name: archive.read(name) for name in archive.namelist()}
                members[prefix + relative] = b"REPLACED-FINAL-ZIP"
                members[prefix + "SHA256SUMS.txt"] = "".join(
                    f"{hashlib.sha256(content).hexdigest()}  {name.removeprefix(prefix)}\n"
                    for name, content in members.items()
                    if name != prefix + "SHA256SUMS.txt"
                ).encode()
                with zipfile.ZipFile(archive_path, "w") as archive:
                    for name, content in members.items():
                        archive.writestr(name, content)
            return result

        monkeypatch.setattr(builder.shutil, "copy2", tamper)
        with pytest.raises(ValueError, match="receipt|证据"):
            builder.package_verified()
        self.assert_no_delivery(builder)

    def test_prepare_records_separate_build_and_package_inputs(self, prepared_builder):
        builder = prepared_builder
        receipt = json.loads(builder.EVIDENCE.read_text(encoding="utf-8"))
        assert receipt["build_inputs"] == builder.source_inputs()
        assert receipt["package_inputs"] == builder.package_inputs()
        assert "deployment/scripts/upgrade-aihome-managed.ps1" in receipt["package_inputs"]

    def test_failed_rebuild_invalidates_receipt_but_keeps_cached_apk(self, prepared_builder, monkeypatch):
        builder = prepared_builder
        apk = builder.APK_DIR / "release/app-release.apk"
        before = apk.read_bytes()
        def failed():
            raise subprocess.CalledProcessError(1, ["gradle"])
        monkeypatch.setattr(builder, "build_release_apk", failed)
        with pytest.raises(subprocess.CalledProcessError):
            builder.prepare()
        assert apk.read_bytes() == before
        assert not builder.EVIDENCE.exists()
        with pytest.raises(OSError):
            builder.package_verified()

    @pytest.mark.parametrize(
        "relative",
        [
            "deployment/scripts/install.ps1",
            "deployment/version.ps1",
            "deployment/config.example.json",
            "USER_INSTALL_GUIDE.md",
            "deployment/scripts/upgrade-aihome-managed.ps1",
            "third_party/ffmpeg/ffmpeg.exe",
            "server/dist/MediaReviewServer/MediaReviewServer.exe",
            "server/dist/MediaReviewServer/_internal/runtime.dll",
            "android/app/build/outputs/apk/release/app-release.apk",
            "server/app/__init__.py",
        ],
    )
    def test_prepare_then_input_change_blocks_before_packaging(self, prepared_builder, relative):
        builder = prepared_builder
        path = builder.ROOT / relative
        path.write_bytes(path.read_bytes() + b"\nchanged")
        with pytest.raises(ValueError, match="证据|输入"):
            builder.package_verified()
        assert not list(builder.OUT.glob("*.zip"))
        assert not list(builder.OUT.glob(builder.current_package_name()))

    @pytest.mark.parametrize("mutation", ["legacy", "expired", "future"])
    def test_old_receipt_is_rejected(self, prepared_builder, mutation):
        builder = prepared_builder
        receipt = json.loads(builder.EVIDENCE.read_text(encoding="utf-8"))
        if mutation == "legacy":
            receipt["inputs"] = receipt.get("build_inputs", receipt.get("inputs"))
            receipt.pop("build_inputs", None)
            receipt.pop("package_inputs", None)
        else:
            delta = dt.timedelta(hours=-7 if mutation == "expired" else 1)
            receipt["verified_at"] = (dt.datetime.now(dt.UTC) + delta).isoformat()
        builder.EVIDENCE.write_text(json.dumps(receipt), encoding="utf-8")
        with pytest.raises(ValueError, match="证据|输入"):
            builder.package_verified()
        assert not list(builder.OUT.glob("*.zip"))

    @pytest.mark.parametrize(
        "relative",
        [
            "deployment/README.md",
            "deployment/scripts/install.ps1",
            "third_party/ffmpeg/ffmpeg.exe",
            "server/dist/MediaReviewServer/_internal/runtime.dll",
        ],
    )
    def test_package_input_deletion_blocks(self, prepared_builder, relative):
        builder = prepared_builder
        (builder.ROOT / relative).unlink()
        with pytest.raises((ValueError, OSError)):
            builder.package_verified()
        assert not list(builder.OUT.glob("*.zip"))

    @pytest.mark.parametrize(
        "relative",
        [
            "third_party/ffmpeg/ffprobe.exe",
            "server/dist/MediaReviewServer/_internal/new.dll",
        ],
    )
    def test_package_input_addition_blocks(self, prepared_builder, relative):
        builder = prepared_builder
        (builder.ROOT / relative).write_bytes(b"new-input")
        with pytest.raises(ValueError, match="证据"):
            builder.package_verified()
        assert not list(builder.OUT.glob("*.zip"))

    @pytest.mark.parametrize("relative", ["USER_INSTALL_GUIDE.md", "server/app/__init__.py"])
    def test_input_change_during_build_rejects_receipt(
        self, prepared_builder, monkeypatch, relative
    ):
        builder = prepared_builder
        monkeypatch.setattr(
            builder, "build_exe", lambda: (builder.ROOT / relative).write_bytes(b"drift")
        )
        with pytest.raises(ValueError, match="输入|源码"):
            builder.prepare()
        assert not builder.EVIDENCE.exists()

    def test_input_change_during_probe_blocks(self, prepared_builder, monkeypatch):
        builder = prepared_builder
        probe = builder.verify_server_binary

        def drift(path):
            (builder.ROOT / "USER_INSTALL_GUIDE.md").write_bytes(b"drift")
            return probe(path)

        monkeypatch.setattr(builder, "verify_server_binary", drift)
        with pytest.raises(ValueError, match="输入"):
            builder.package_verified()
        assert not list(builder.OUT.glob("*.zip"))

    def test_input_change_during_copy_blocks(self, prepared_builder, monkeypatch):
        builder = prepared_builder
        copy = builder.shutil.copy2

        def drift(source, target):
            source.write_bytes(source.read_bytes() + b"drift")
            return copy(source, target)

        monkeypatch.setattr(builder.shutil, "copy2", drift)
        with pytest.raises(ValueError, match="输入"):
            builder.package_verified()
        assert not list(builder.OUT.glob("*.zip"))

    def test_all_copied_sources_are_receipted_and_manifest_preserves_inputs(self, prepared_builder):
        builder = prepared_builder
        receipt = json.loads(builder.EVIDENCE.read_text(encoding="utf-8"))
        archive_path = builder.package_verified()
        assert archive_path.name == f"MediaReview_Server_{builder.VERSION}_stage9.zip"
        assert builder.current_apk_name() == f"MediaReview_{builder.ANDROID_VERSION}_stage9.apk"
        with zipfile.ZipFile(archive_path) as archive:
            prefix = archive_path.stem + "/"
            manifest = json.loads(archive.read(prefix + "RELEASE_MANIFEST.json"))
            assert manifest["server_exe_sha256"] == receipt["server_sha256"]
            assert manifest["apk_sha256"] == receipt["apk_sha256"]
            assert manifest["build_input_sha256"] == receipt["build_inputs"]
            assert manifest["package_input_sha256"] == receipt["package_inputs"]
            copied = set()
            for destination, source in builder.package_sources().items():
                relative = source.relative_to(builder.ROOT).as_posix()
                assert builder.sha256(source) == receipt["package_inputs"][relative]
                assert archive.read(prefix + destination) == source.read_bytes()
                copied.add(prefix + destination)
            assert set(archive.namelist()) == copied | {
                prefix + name for name in ("HANDOVER.md", "RELEASE_MANIFEST.json", "SHA256SUMS.txt")
            }
            exported = builder.OUT / builder.current_apk_name()
            assert builder.sha256(exported) == receipt["apk_sha256"]
            assert exported.read_bytes() == archive.read(prefix + "app-release.apk")

    def test_version_ssot_matches_source_deployment_and_builder(self, builder):
        builder.verify_version_ssot()

    @pytest.mark.parametrize(
        "relative",
        [
            "server/app/__init__.py",
            "deployment/version.ps1",
            "server/pyproject.toml",
            "android/app/build.gradle.kts",
        ],
    )
    def test_version_ssot_drift_blocks_prepare(self, prepared_builder, relative):
        builder = prepared_builder
        old, new = {
            "server/app/__init__.py": (
                f'__version__ = "{builder.VERSION}"',
                '__version__ = "0.0.0-drift"',
            ),
            "deployment/version.ps1": (
                f"$RequiredApiContract = {builder.API_CONTRACT}",
                "$RequiredApiContract = 0",
            ),
            "server/pyproject.toml": (f'version = "{builder.VERSION}"', 'version = "0.0.0-drift"'),
            "android/app/build.gradle.kts": (
                f"versionCode = {builder.ANDROID_CODE}",
                "versionCode = 0",
            ),
        }[relative]
        path = builder.ROOT / relative
        original = path.read_text(encoding="utf-8")
        assert old in original
        changed = original.replace(old, new)
        assert changed != original
        path.write_text(changed, encoding="utf-8")
        with pytest.raises(ValueError, match="版本|SSOT"):
            builder.prepare()


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

    def test_cached_apk_is_preserved_and_release_build_failure_propagates(self, builder, tmp_path, monkeypatch):
        android = tmp_path / "android"
        monkeypatch.setattr(builder, "ROOT", tmp_path)
        monkeypatch.setattr(builder, "APK_DIR", android / "app/build/outputs/apk")
        apk = builder.APK_DIR / "release/app-release.apk"
        apk.parent.mkdir(parents=True)
        apk.write_bytes(b"OLD")

        def failed(command, **kwargs):
            assert apk.read_bytes() == b"OLD"
            assert "clean" not in command and command.count(":app:assembleRelease") == 1
            assert "--offline" in command and "--max-workers=1" in command
            assert "-Pkotlin.compiler.execution.strategy=in-process" in command
            raise subprocess.CalledProcessError(1, command)

        monkeypatch.setattr(builder.subprocess, "run", failed)
        with pytest.raises(subprocess.CalledProcessError):
            builder.build_release_apk()
        assert apk.read_bytes() == b"OLD"

    @pytest.mark.parametrize(
        "field,value",
        [("version", "1.1.0"), ("api_contract", 1), ("status", "error"), ("capabilities", [])],
    )
    def test_wrong_binary_health_blocks_packaging(self, builder, field, value):
        health = {
            "status": "ok",
            "version": builder.VERSION,
            "api_contract": builder.API_CONTRACT,
            "capabilities": list(builder.REQUIRED_CAPABILITIES),
        }
        health[field] = value
        with pytest.raises(ValueError):
            builder.validate_binary_health(health)

    @pytest.mark.parametrize("field", ["package", "code", "name"])
    def test_wrong_apk_manifest_blocks_packaging(self, builder, field):
        values = {
            "package": builder.ANDROID_PACKAGE,
            "code": builder.ANDROID_CODE,
            "name": builder.ANDROID_VERSION,
        }
        values[field] = {
            "package": "com.other.app",
            "code": builder.ANDROID_CODE - 1,
            "name": "0.0.0-drift",
        }[field]
        with pytest.raises(ValueError):
            builder.parse_apk_badging(
                f"package: name='{values['package']}' versionCode='{values['code']}' versionName='{values['name']}'"
            )

    def test_manifest_and_handover_use_verified_separate_versions(self, builder, tmp_path):
        exe, apk = tmp_path / "MediaReviewServer.exe", tmp_path / "app-release.apk"
        exe.write_bytes(b"fresh-exe")
        apk.write_bytes(b"fresh-apk")
        evidence = {
            "server": {
                "version": builder.VERSION,
                "api_contract": builder.API_CONTRACT,
                "capabilities": list(builder.REQUIRED_CAPABILITIES),
            },
            "android": {
                "package": builder.ANDROID_PACKAGE,
                "version_code": builder.ANDROID_CODE,
                "version_name": builder.ANDROID_VERSION,
                "signature": "PASS",
            },
        }
        manifest = builder.release_manifest(evidence, "a" * 40, exe, apk)
        assert manifest["server_version"] == builder.VERSION
        assert manifest["android_version_name"] == builder.ANDROID_VERSION
        assert manifest["android_version_code"] == builder.ANDROID_CODE
        assert manifest["apk_sha256"] == builder.sha256(apk)
        handover = builder.render_handover(manifest)
        assert f"Android: {builder.ANDROID_VERSION}" in handover
        assert f"Android versionCode: {builder.ANDROID_CODE}" in handover
        assert f"Android: {builder.VERSION}" not in handover

    def test_unsigned_apk_fails_verification(self, builder, tmp_path, monkeypatch):
        def run(command, **kwargs):
            if "dump" in command:
                return SimpleNamespace(
                    stdout=(
                        f"package: name='{builder.ANDROID_PACKAGE}' versionCode='{builder.ANDROID_CODE}' "
                        f"versionName='{builder.ANDROID_VERSION}'"
                    )
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
            "& { function Get-Service { [CmdletBinding()]param([string]$Name); "
            "Write-Error 'SCM unavailable' }; "
            f"& {quote(script)} -InstallDir {quote(tmp_path / 'install')} "
            f"-DataRoot {quote(data_root)} }}; exit $LASTEXITCODE"
        )
        result = subprocess.run(  # noqa: S603 固定 PowerShell 与隔离测试脚本
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
