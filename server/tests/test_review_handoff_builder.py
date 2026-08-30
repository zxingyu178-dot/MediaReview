"""Task 0A 阶段验收 ZIP 构建器端到端测试。

在临时 Git 仓库中真实运行 ``scripts/build_review_handoff.py``，
验证 CLI 合同、必需 ZIP 条目、禁止项处理与 fail-closed 行为。
"""

import os
import re
import subprocess
import sys
import zipfile
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parents[2]
BUILDER = REPO_ROOT / "scripts" / "build_review_handoff.py"

SUMMARY_TEXT = "# 阶段验收摘要\n\n阶段结论：合格\n"


@pytest.fixture()
def git_env(tmp_path):
    blank = tmp_path / "blank.gitconfig"
    blank.write_text("", encoding="utf-8")
    env = dict(os.environ)
    env["GIT_CONFIG_GLOBAL"] = str(blank)
    env["GIT_CONFIG_SYSTEM"] = str(blank)
    env["PYTHONIOENCODING"] = "utf-8"
    return env


def run_git(root: Path, env: dict, *args: str) -> None:
    subprocess.run(["git", "-C", str(root), *args], check=True, capture_output=True, env=env)


@pytest.fixture()
def repo(tmp_path, git_env):
    root = tmp_path / "proj"
    root.mkdir()
    run_git(root, git_env, "init")
    run_git(root, git_env, "config", "user.name", "Reviewer")
    run_git(root, git_env, "config", "user.email", "reviewer@example.com")
    return root


def seed_stage_files(root: Path) -> None:
    (root / "REVIEW_SUMMARY.md").write_text(SUMMARY_TEXT, encoding="utf-8")
    meta = root / "review_meta"
    meta.mkdir()
    (meta / "server_tests.txt").write_text("258 passed\n", encoding="utf-8")
    (meta / "server_lint.txt").write_text("All checks passed!\n", encoding="utf-8")
    (root / "AGENTS.md").write_text("rules\n", encoding="utf-8")
    docs = root / "docs"
    docs.mkdir()
    (docs / "DEV_LOG.md").write_text("log\n", encoding="utf-8")


def commit_all(root: Path, env: dict, message: str) -> str:
    run_git(root, env, "add", "-A")
    run_git(root, env, "-c", "commit.gpgsign=false", "commit", "-m", message)
    result = subprocess.run(
        ["git", "-C", str(root), "rev-parse", "HEAD"],
        check=True,
        capture_output=True,
        text=True,
        env=env,
    )
    return result.stdout.strip()


def build(root: Path, env: dict, stage: str, base: str, *extra: str) -> object:
    return subprocess.run(
        [
            sys.executable,
            str(BUILDER),
            "--stage",
            stage,
            "--name",
            "阶段名",
            "--base",
            base,
            *extra,
        ],
        cwd=root,
        capture_output=True,
        encoding="utf-8",
        errors="replace",
        env=env,
    )


def latest_zip(root: Path) -> Path:
    zips = sorted((root / "review_handoff").glob("*.zip"))
    assert zips, "expected a review handoff ZIP"
    return zips[-1]


def read_zip_names(root: Path) -> set[str]:
    with zipfile.ZipFile(latest_zip(root)) as zf:
        return set(zf.namelist())


def test_builder_script_exists():
    assert BUILDER.is_file(), "scripts/build_review_handoff.py 必须存在"


def test_happy_path_builds_required_zip(repo, git_env):
    seed_stage_files(repo)
    base = commit_all(repo, git_env, "baseline")
    src = repo / "server" / "app"
    src.mkdir(parents=True)
    (src / "main.py").write_text("print('v2')\n", encoding="utf-8")
    (src / "util.kt").write_text("val x = 1\n", encoding="utf-8")
    commit_all(repo, git_env, "stage change")
    # 保留一个未提交的已跟踪修改，覆盖"基线..工作树"的完整 diff 范围。
    (repo / "AGENTS.md").write_text("rules v2\n", encoding="utf-8")

    result = build(repo, git_env, "04", base)
    assert result.returncode == 0, result.stderr

    zip_path = latest_zip(repo)
    assert re.fullmatch(r"MediaReview_Review_Stage-04_\d{8}_\d{4}\.zip", zip_path.name)

    names = read_zip_names(repo)
    required = {
        "REVIEW_SUMMARY.md",
        "AGENTS.md",
        "docs/DEV_LOG.md",
        "review_meta/git_status.txt",
        "review_meta/git_log.txt",
        "review_meta/git_diff_stat.txt",
        "review_meta/git_diff.patch",
        "review_meta/server_tests.txt",
        "review_meta/server_lint.txt",
        "server/app/main.py",
        "server/app/util.kt",
    }
    missing = required - names
    assert not missing, f"missing ZIP entries: {missing}"

    with zipfile.ZipFile(zip_path) as zf:
        patch = zf.read("review_meta/git_diff.patch").decode("utf-8")
        status = zf.read("review_meta/git_status.txt").decode("utf-8")
    assert "main.py" in patch
    assert "util.kt" in patch
    assert status.strip(), "git status must not be empty"


def test_missing_summary_fails_closed(repo, git_env):
    meta = repo / "review_meta"
    meta.mkdir()
    (meta / "server_tests.txt").write_text("ok\n", encoding="utf-8")
    base = commit_all(repo, git_env, "baseline")

    result = build(repo, git_env, "01", base)

    assert result.returncode != 0
    assert "REVIEW_SUMMARY" in result.stdout + result.stderr
    assert not list((repo / "review_handoff").glob("*.zip"))


def test_summary_without_conclusion_fails(repo, git_env):
    (repo / "REVIEW_SUMMARY.md").write_text("没有结论行\n", encoding="utf-8")
    meta = repo / "review_meta"
    meta.mkdir()
    (meta / "test_results.txt").write_text("ok\n", encoding="utf-8")
    base = commit_all(repo, git_env, "baseline")

    result = build(repo, git_env, "01", base)

    assert result.returncode != 0
    assert "阶段结论" in result.stdout + result.stderr


def test_missing_test_evidence_fails(repo, git_env):
    (repo / "REVIEW_SUMMARY.md").write_text(SUMMARY_TEXT, encoding="utf-8")
    base = commit_all(repo, git_env, "baseline")

    result = build(repo, git_env, "01", base)

    assert result.returncode != 0
    assert "测试证据" in result.stdout + result.stderr


def test_invalid_base_fails(repo, git_env):
    seed_stage_files(repo)
    commit_all(repo, git_env, "baseline")

    result = build(repo, git_env, "01", "no-such-ref")

    assert result.returncode != 0


def test_non_ancestor_base_fails(repo, git_env):
    seed_stage_files(repo)
    base = commit_all(repo, git_env, "baseline")
    run_git(repo, git_env, "checkout", "--orphan", "other")
    commit_all(repo, git_env, "orphan")

    result = build(repo, git_env, "01", base)

    assert result.returncode != 0
    assert "祖先" in result.stdout + result.stderr


def test_secret_env_file_is_fatal(repo, git_env):
    seed_stage_files(repo)
    base = commit_all(repo, git_env, "baseline")
    (repo / ".env").write_text("TOKEN=1\n", encoding="utf-8")
    commit_all(repo, git_env, "leak a secret")

    result = build(repo, git_env, "01", base)

    assert result.returncode != 0
    assert ".env" in result.stdout + result.stderr
    assert not list((repo / "review_handoff").glob("*.zip"))


def test_keystore_file_is_fatal(repo, git_env):
    seed_stage_files(repo)
    base = commit_all(repo, git_env, "baseline")
    keys = repo / "android" / "keys"
    keys.mkdir(parents=True)
    (keys / "release.jks").write_bytes(b"\x00" * 32)
    commit_all(repo, git_env, "leak a keystore")

    result = build(repo, git_env, "01", base)

    assert result.returncode != 0
    assert "release.jks" in result.stdout + result.stderr


def test_forbidden_and_unapproved_files_excluded_with_notes(repo, git_env):
    seed_stage_files(repo)
    base = commit_all(repo, git_env, "baseline")
    (repo / "build").mkdir()
    (repo / "build" / "out.log").write_text("log\n", encoding="utf-8")
    (repo / "data.db").write_bytes(b"\x00" * 16)
    assets = repo / "assets"
    assets.mkdir()
    (assets / "icon.png").write_bytes(b"\x89PNG" + b"\x00" * 16)
    commit_all(repo, git_env, "mixed changes")

    result = build(repo, git_env, "01", base)
    assert result.returncode == 0, result.stderr

    names = read_zip_names(repo)
    assert "build/out.log" not in names
    assert "data.db" not in names
    assert "assets/icon.png" not in names
    with zipfile.ZipFile(latest_zip(repo)) as zf:
        excluded = zf.read("review_meta/excluded_files.txt").decode("utf-8")
    assert "build/out.log" in excluded
    assert "data.db" in excluded
    assert "assets/icon.png" in excluded


def test_oversized_file_excluded(repo, git_env):
    seed_stage_files(repo)
    base = commit_all(repo, git_env, "baseline")
    big = repo / "big.py"
    big.write_text("x = 1\n" + "#" * (1500 * 1024) + "\n", encoding="utf-8")
    commit_all(repo, git_env, "big file")

    result = build(repo, git_env, "01", base)
    assert result.returncode == 0, result.stderr

    names = read_zip_names(repo)
    assert "big.py" not in names
    with zipfile.ZipFile(latest_zip(repo)) as zf:
        excluded = zf.read("review_meta/excluded_files.txt").decode("utf-8")
    assert "big.py" in excluded


def test_untracked_files_not_included(repo, git_env):
    seed_stage_files(repo)
    base = commit_all(repo, git_env, "baseline")
    (repo / "AGENTS.md").write_text("rules v2\n", encoding="utf-8")
    (repo / "scratch.py").write_text("print('scratch')\n", encoding="utf-8")

    result = build(repo, git_env, "01", base)
    assert result.returncode == 0, result.stderr

    names = read_zip_names(repo)
    assert "AGENTS.md" in names
    assert "scratch.py" not in names
    with zipfile.ZipFile(latest_zip(repo)) as zf:
        patch = zf.read("review_meta/git_diff.patch").decode("utf-8")
    assert "rules v2" in patch


def test_zip_size_cap_fails(repo, git_env):
    seed_stage_files(repo)
    base = commit_all(repo, git_env, "baseline")

    result = build(repo, git_env, "01", base, "--max-zip-mb", "0")

    assert result.returncode != 0
    assert not list((repo / "review_handoff").glob("*.zip"))


def test_unsafe_stage_value_rejected(repo, git_env):
    seed_stage_files(repo)
    base = commit_all(repo, git_env, "baseline")

    result = build(repo, git_env, "../evil", base)

    assert result.returncode != 0


def test_git_unavailable_fails(repo, git_env):
    seed_stage_files(repo)
    base = commit_all(repo, git_env, "baseline")
    env = dict(git_env)
    env["PATH"] = r"C:\Windows\System32"

    result = build(repo, env, "01", base)

    assert result.returncode != 0
    assert "git" in (result.stdout + result.stderr).lower()
