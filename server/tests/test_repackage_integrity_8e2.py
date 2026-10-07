import json
import zipfile

import pytest
from test_release_package_integrity_8e import builder, prepared_builder  # noqa: F401, F811


@pytest.fixture
def old_rc(prepared_builder, monkeypatch):  # noqa: F811
    b = prepared_builder
    evidence = json.loads(b.EVIDENCE.read_text())
    evidence["build_git_head"] = "d" * 40
    b.EVIDENCE.write_text(json.dumps(evidence))
    old_zip = b.package_verified()
    old = b.OUT / old_zip.stem
    manifest = json.loads((old / "RELEASE_MANIFEST.json").read_text())
    monkeypatch.setattr(b, "VERIFIED_RUNTIME_HEAD", manifest["git_head"], raising=False)
    monkeypatch.setattr(b, "VERIFIED_APK_SHA256", manifest["apk_sha256"], raising=False)
    monkeypatch.setattr(b, "VERIFIED_EXE_SHA256", manifest["server_exe_sha256"], raising=False)
    monkeypatch.setattr(
        b, "VERIFIED_RC_INVENTORY_SHA256", b.sha256(old / "SHA256SUMS.txt"), raising=False
    )
    monkeypatch.setattr(b, "git_head", lambda **kwargs: "b" * 40)
    return b, old


def test_repackage_same_binaries_and_exact_zip(old_rc):
    b, old = old_rc
    receipt = b.prepare_repackage(old, old / "RELEASE_MANIFEST.json")
    output = b.repackage_verified(old, old / "RELEASE_MANIFEST.json", receipt)
    assert b.verify_zip(output, output.stem) > 0
    with zipfile.ZipFile(output) as z:
        m = json.loads(z.read(output.stem + "/RELEASE_MANIFEST.json"))
        assert m["runtime_build_git_head"] == "d" * 40
        assert m["runtime_release_git_head"] == "a" * 40
        assert m["package_git_head"] == "b" * 40
        assert m["build_git_head"] == "d" * 40
        for p in [
            "app-release.apk",
            "MediaReviewServer/MediaReviewServer.exe",
            "MediaReviewServer/_internal/runtime.dll",
        ]:
            assert z.read(output.stem + "/" + p) == (old / p).read_bytes()
    assert len(list(b.OUT.glob("*stage8e.apk"))) == 1


@pytest.mark.parametrize("change", ["manifest", "apk", "exe", "extra", "missing"])
def test_invalid_old_rc_blocks(old_rc, change):
    b, old = old_rc
    paths = {
        "manifest": "RELEASE_MANIFEST.json",
        "apk": "app-release.apk",
        "exe": "MediaReviewServer/MediaReviewServer.exe",
        "missing": "MediaReviewServer/_internal/runtime.dll",
    }
    if change in ("manifest", "missing"):
        (old / paths[change]).unlink()
    else:
        (old / paths.get(change, "unexpected")).write_bytes(b"tampered")
    with pytest.raises((ValueError, OSError)):
        b.prepare_repackage(old, old / "RELEASE_MANIFEST.json")


@pytest.mark.parametrize("change", ["script", "new_script", "runtime", "old_binary"])
def test_receipt_drift_blocks(old_rc, change):
    b, old = old_rc
    receipt = b.prepare_repackage(old, old / "RELEASE_MANIFEST.json")
    path = {
        "script": b.DEPLOY / "scripts/install.ps1",
        "new_script": b.DEPLOY / "scripts/verified-rc.ps1",
        "runtime": b.ROOT / "server/app/__init__.py",
        "old_binary": old / "app-release.apk",
    }[change]
    path.write_bytes(b"changed")
    with pytest.raises(ValueError):
        b.repackage_verified(old, old / "RELEASE_MANIFEST.json", receipt)
    assert not list(b.OUT.glob("*bbbbbbb.zip"))


def test_runtime_provenance_change_blocks_prepare(old_rc):
    b, old = old_rc
    (b.ROOT / "server/app/__init__.py").write_bytes(b"changed-runtime")
    with pytest.raises(ValueError):
        b.prepare_repackage(old, old / "RELEASE_MANIFEST.json")


@pytest.mark.parametrize("change", ["staging", "zip", "head", "source"])
def test_repackage_export_drift_cleanup(old_rc, monkeypatch, change):
    b, old = old_rc
    receipt = b.prepare_repackage(old, old / "RELEASE_MANIFEST.json")
    verify = b.verify_zip

    def drift(path, name):
        count = verify(path, name)
        if change == "staging":
            (b.OUT / name / "app-release.apk").write_bytes(b"tampered")
        elif change == "zip":
            with zipfile.ZipFile(path, "a") as archive:
                archive.writestr(name + "/extra", b"tampered")
        elif change == "head":
            monkeypatch.setattr(b, "git_head", lambda **kwargs: "c" * 40)
        else:
            (old / "MediaReviewServer/_internal/runtime.dll").write_bytes(b"tampered")
        return count

    monkeypatch.setattr(b, "verify_zip", drift)
    with pytest.raises(ValueError):
        b.repackage_verified(old, old / "RELEASE_MANIFEST.json", receipt)
    assert not list(b.OUT.glob("*bbbbbbb*"))


@pytest.mark.parametrize(
    "relative", ["MediaReviewServer/_internal/runtime.dll", "RELEASE_MANIFEST.json"]
)
def test_rewritten_old_checksums_cannot_authorize_runtime_tamper(old_rc, relative):
    b, old = old_rc
    (old / relative).write_bytes(b"tampered")
    b.write_sha256(old)
    with pytest.raises(ValueError):
        b.prepare_repackage(old, old / "RELEASE_MANIFEST.json")


@pytest.mark.parametrize(
    "target", ["manifest", "apk", "sums", "source", "historical", "outside", "nested", "existing"]
)
def test_receipt_unsafe_destination_preserves_bytes(old_rc, target):
    b, old = old_rc
    paths = {
        "manifest": old / "RELEASE_MANIFEST.json",
        "apk": old / "app-release.apk",
        "sums": old / "SHA256SUMS.txt",
        "source": b.ROOT / "server/app/__init__.py",
        "historical": b.OUT / "historical.apk",
        "outside": b.ROOT / "outside.json",
        "nested": b.OUT / "nested" / "receipt.json",
        "existing": b.OUT / "receipt.json",
    }
    destination = paths[target]
    if target == "nested":
        destination.parent.mkdir()
    if target in ("historical", "outside", "existing"):
        destination.write_bytes(b"preserve-existing")
    before = {p: p.read_bytes() for p in b.ROOT.rglob("*") if p.is_file()}
    with pytest.raises((ValueError, OSError)):
        b.prepare_repackage(old, old / "RELEASE_MANIFEST.json", destination)
    assert {p: p.read_bytes() for p in b.ROOT.rglob("*") if p.is_file()} == before
    assert not list(b.OUT.glob("*bbbbbbb*"))


def test_default_receipt_retry_rejects_without_overwrite(old_rc):
    b, old = old_rc
    receipt = b.prepare_repackage(old, old / "RELEASE_MANIFEST.json")
    original = receipt.read_bytes()
    with pytest.raises((ValueError, OSError)):
        b.prepare_repackage(old, old / "RELEASE_MANIFEST.json")
    assert receipt.read_bytes() == original


def test_receipt_creation_race_uses_exclusive_open(old_rc, monkeypatch):
    b, old = old_rc
    receipt = b.OUT / "race.json"
    real_open = type(receipt).open

    def competing_open(path, mode="r", *args, **kwargs):
        if path == receipt and mode in ("w", "x"):
            with real_open(path, "wb") as stream:
                stream.write(b"competitor")
        return real_open(path, mode, *args, **kwargs)

    monkeypatch.setattr(type(receipt), "open", competing_open)
    with pytest.raises((ValueError, OSError)):
        b.prepare_repackage(old, old / "RELEASE_MANIFEST.json", receipt)
    assert receipt.read_bytes() == b"competitor"
    assert not list(b.OUT.glob("*bbbbbbb*"))


@pytest.mark.parametrize("operation", ["prepare", "final"])
def test_receipt_link_ancestor_rejected(old_rc, operation):
    b, old = old_rc
    receipt = b.prepare_repackage(old, old / "RELEASE_MANIFEST.json")
    alias = b.ROOT / "output-alias"
    try:
        alias.symlink_to(b.OUT, target_is_directory=True)
    except OSError:
        pytest.skip("directory symlink unavailable")
    redirected = alias / ("new.json" if operation == "prepare" else receipt.name)
    original = receipt.read_bytes()
    with pytest.raises((ValueError, OSError)):
        if operation == "prepare":
            b.prepare_repackage(old, old / "RELEASE_MANIFEST.json", redirected)
        else:
            b.repackage_verified(old, old / "RELEASE_MANIFEST.json", redirected)
    assert receipt.read_bytes() == original
    assert not (b.OUT / "new.json").exists()
    assert not list(b.OUT.glob("*bbbbbbb*"))


def test_valid_named_receipt_preserves_old_rc(old_rc):
    b, old = old_rc
    before = {p: p.read_bytes() for p in old.rglob("*") if p.is_file()}
    receipt = b.OUT / "receipt-fix-new.json"
    assert b.prepare_repackage(old, old / "RELEASE_MANIFEST.json", receipt) == receipt
    assert json.loads(receipt.read_text())["mode"] == "repackage-verified"
    assert {p: p.read_bytes() for p in old.rglob("*") if p.is_file()} == before


@pytest.mark.parametrize("target", ["outside", "leaf_link"])
def test_final_receipt_redirect_rejected(old_rc, target):
    b, old = old_rc
    receipt = b.prepare_repackage(old, old / "RELEASE_MANIFEST.json")
    redirect = b.ROOT / "outside.json" if target == "outside" else b.OUT / "alias.json"
    if target == "outside":
        redirect.write_bytes(receipt.read_bytes())
    else:
        try:
            redirect.symlink_to(receipt)
        except OSError:
            pytest.skip("file symlink unavailable")
    original = receipt.read_bytes()
    with pytest.raises((ValueError, OSError)):
        b.repackage_verified(old, old / "RELEASE_MANIFEST.json", redirect)
    assert receipt.read_bytes() == original
    assert not list(b.OUT.glob("*bbbbbbb*"))
