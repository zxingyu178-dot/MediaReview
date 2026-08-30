#!/usr/bin/env python3
"""MediaReview 阶段验收 ZIP 构建器（takeover plan Task 0A）。

按 ``REVIEW_HANDOFF_RULES.md`` 打包单个开发阶段供 ChatGPT 验收：

- 打包范围 = ``git diff --name-only <base>``（基线到工作树的已跟踪变更）
  加固定项目状态文档；绝不包含未跟踪文件，绝不跟随仓库外路径。
- 只打包已批准的文本扩展名；禁止类别（数据库/APK/EXE/构建产物/日志等）
  与超限文件被排除并逐条记录；疑似密钥路径直接失败。
- ``REVIEW_SUMMARY.md`` 与至少一个测试/静态检查证据文件必须存在，
  否则拒绝生成 ZIP（fail-closed）。
- 仅使用 Python 标准库；任何失败都以非零退出且不产出 ZIP。

用法::

    python scripts/build_review_handoff.py --stage 0A --name 阶段名 --base <git-ref>
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import zipfile
from datetime import UTC, datetime
from pathlib import Path, PurePosixPath

APPROVED_EXTENSIONS = {
    ".bat",
    ".gradle",
    ".java",
    ".json",
    ".kt",
    ".kts",
    ".md",
    ".properties",
    ".ps1",
    ".py",
    ".sql",
    ".toml",
    ".xml",
    ".yaml",
    ".yml",
}

STATUS_DOCS = [
    "AGENTS.md",
    "TASKS.md",
    "docs/DEV_LOG.md",
    "docs/PRODUCT_SPEC.md",
    "docs/ARCHITECTURE.md",
    "docs/DEVELOPMENT_PLAN.md",
    "docs/ACCEPTANCE.md",
    "docs/API_CONVENTIONS.md",
]

EVIDENCE_FILES = [
    "review_meta/server_tests.txt",
    "review_meta/server_lint.txt",
    "review_meta/android_tests.txt",
    "review_meta/android_lint.txt",
    "review_meta/test_results.txt",
    "review_meta/lint_results.txt",
]

GIT_META_FILES = {
    "review_meta/git_status.txt": ["status", "--short"],
    "review_meta/git_log.txt": ["log", "--oneline", "-20"],
}

SECRET_NAMES = {"key.properties", "secrets.env", "id_rsa", "id_ed25519"}
SECRET_SUFFIXES = {".jks", ".keystore", ".p12", ".pem", ".pfx", ".key"}

FORBIDDEN_DIRS = {
    ".git",
    ".gradle",
    ".idea",
    ".kotlin",
    ".mypy_cache",
    ".pytest_cache",
    ".ruff_cache",
    ".venv",
    ".worktrees",
    "__pycache__",
    "build",
    "deploy_handoff",
    "dist",
    "logs",
    "node_modules",
    "release",
    "review_handoff",
    "venv",
}

FORBIDDEN_SUFFIXES = {
    ".aab",
    ".apk",
    ".bin",
    ".db",
    ".db-shm",
    ".db-wal",
    ".dll",
    ".exe",
    ".jar",
    ".log",
    ".so",
    ".sqlite",
    ".sqlite3",
}

STAGE_NAME_PATTERN = re.compile(r"[\w.\-]+\Z")
DRIVE_LETTER_PATTERN = re.compile(r"[A-Za-z]:")
GIT_GLOBAL = ["-c", "core.quotepath=false"]


class BuildError(Exception):
    """导致整个构建失败（非零退出）的错误。"""


def git_process(args: list[str], cwd: Path) -> subprocess.CompletedProcess:
    try:
        # 参数全部来自内部常量与 CLI 引用，git 从 PATH 解析是有意行为。
        proc = subprocess.run(  # noqa: S603
            ["git", *GIT_GLOBAL, *args],  # noqa: S607
            cwd=str(cwd),
            capture_output=True,
            check=False,
        )
    except FileNotFoundError as exc:
        raise BuildError("git 不可用：无法执行 git 命令，请确认 git 在 PATH 中") from exc
    return proc


def git_output(args: list[str], cwd: Path) -> str:
    proc = git_process(args, cwd)
    if proc.returncode != 0:
        detail = proc.stderr.decode("utf-8", "replace").strip()
        raise BuildError(f"git 命令失败 (git {' '.join(args)}): {detail}")
    return proc.stdout.decode("utf-8", "replace")


def git_must_succeed(args: list[str], cwd: Path, failure: str) -> None:
    proc = git_process(args, cwd)
    if proc.returncode != 0:
        detail = proc.stderr.decode("utf-8", "replace").strip()
        raise BuildError(f"{failure}: {detail}")


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="构建阶段验收 ZIP（详见 REVIEW_HANDOFF_RULES.md）")
    parser.add_argument("--stage", required=True, help="阶段编号，例如 04 或 0A")
    parser.add_argument("--name", required=True, help="阶段名称（进入 build_info）")
    parser.add_argument("--base", required=True, help="上一阶段基线 git 引用（必须是 HEAD 祖先）")
    parser.add_argument(
        "--output-dir", default="review_handoff", help="输出目录（相对仓库根，默认 review_handoff）"
    )
    parser.add_argument(
        "--max-file-kb", type=int, default=1024, help="单文件大小上限 KB（默认 1024）"
    )
    parser.add_argument("--max-zip-mb", type=int, default=15, help="ZIP 总大小上限 MB（默认 15）")
    return parser.parse_args(argv)


def resolve_repo_root() -> Path:
    root = git_output(["rev-parse", "--show-toplevel"], cwd=Path.cwd()).strip()
    if not root:
        raise BuildError("无法确定 Git 仓库根目录")
    return Path(root)


def validate_output_dir(root: Path, output_dir: str) -> Path:
    candidate = Path(output_dir)
    if candidate.is_absolute() or DRIVE_LETTER_PATTERN.match(output_dir):
        raise BuildError(f"输出目录必须是仓库内相对路径: {output_dir}")
    resolved = (root / candidate).resolve()
    if resolved == root.resolve() or not resolved.is_relative_to(root.resolve()):
        raise BuildError(f"输出目录必须位于仓库内部: {output_dir}")
    return resolved


def read_required_bytes(path: Path, label: str) -> bytes:
    try:
        return path.read_bytes()
    except OSError as exc:
        raise BuildError(f"无法读取{label}: {path} ({exc})") from exc


def classify_changed_path(rel: str, root: Path, max_file_kb: int) -> tuple[str, bytes, str | None]:
    """返回 (zip 条目名, 内容, 排除原因)。

    排除原因非 None 表示该路径不进入 ZIP（原因写入 excluded_files.txt）；
    疑似密钥、仓库外路径或不可读文件直接抛出 BuildError 使整个构建失败。
    """

    posix = PurePosixPath(rel.replace("\\", "/"))
    if posix.is_absolute() or ".." in posix.parts or DRIVE_LETTER_PATTERN.match(rel):
        raise BuildError(f"diff 包含仓库外或非法路径，拒绝继续: {rel}")

    fs_path = Path(root, *posix.parts)
    if not fs_path.exists():
        return "", b"", "diff 中已删除"

    name = posix.name
    if name == ".env" or name.endswith(".env") or name in SECRET_NAMES:
        raise BuildError(f"疑似密钥文件进入 diff，拒绝打包: {rel}")
    if posix.suffix in SECRET_SUFFIXES:
        raise BuildError(f"疑似密钥文件进入 diff，拒绝打包: {rel}")

    if any(part in FORBIDDEN_DIRS for part in posix.parts):
        return "", b"", "路径位于禁止打包目录"
    if posix.suffix in FORBIDDEN_SUFFIXES:
        return "", b"", "文件类型属于禁止打包类别"
    if posix.suffix not in APPROVED_EXTENSIONS:
        return "", b"", "扩展名不在已批准文本清单"

    size = fs_path.stat().st_size
    if size > max_file_kb * 1024:
        return "", b"", f"超过单文件上限 {max_file_kb} KB"

    return rel, read_required_bytes(fs_path, "diff 文件"), None


def collect_changed_sources(
    root: Path, base: str, max_file_kb: int
) -> tuple[list[tuple[str, bytes]], list[tuple[str, str]]]:
    diff_output = git_output(["diff", "--name-only", base], cwd=root)
    changed = sorted({line.strip() for line in diff_output.splitlines() if line.strip()})

    included: list[tuple[str, bytes]] = []
    excluded: list[tuple[str, str]] = []
    for rel in changed:
        entry_name, data, reason = classify_changed_path(rel, root, max_file_kb)
        if reason is None:
            print(f"包含: {rel}")
            included.append((entry_name, data))
        else:
            print(f"排除: {rel} ({reason})")
            excluded.append((rel, reason))
    return included, excluded


def build_pack(args: argparse.Namespace) -> Path:
    if not STAGE_NAME_PATTERN.fullmatch(args.stage):
        raise BuildError(f"--stage 含非法字符: {args.stage!r}")
    if not STAGE_NAME_PATTERN.fullmatch(args.name):
        raise BuildError(f"--name 含非法字符: {args.name!r}")

    root = resolve_repo_root()
    out_dir = validate_output_dir(root, args.output_dir)

    git_output(["rev-parse", "--verify", "--quiet", f"{args.base}^{{commit}}"], cwd=root)
    git_must_succeed(
        ["merge-base", "--is-ancestor", args.base, "HEAD"],
        root,
        f"--base {args.base} 不是 HEAD 的祖先提交",
    )

    summary_path = root / "REVIEW_SUMMARY.md"
    if not summary_path.is_file():
        raise BuildError("缺少 REVIEW_SUMMARY.md，请先按 REVIEW_HANDOFF_RULES.md 生成")
    summary_bytes = read_required_bytes(summary_path, "REVIEW_SUMMARY.md")
    if "阶段结论" not in summary_bytes.decode("utf-8", "replace"):
        raise BuildError('REVIEW_SUMMARY.md 缺少"阶段结论"行')

    evidence = [name for name in EVIDENCE_FILES if (root / name).is_file()]
    if not evidence:
        expected = ", ".join(EVIDENCE_FILES)
        raise BuildError(f"缺少测试证据文件（至少需要一个）: {expected}")

    included, excluded = collect_changed_sources(root, args.base, args.max_file_kb)

    entries: list[tuple[str, bytes]] = [("REVIEW_SUMMARY.md", summary_bytes)]
    for doc in STATUS_DOCS:
        doc_path = root / doc
        if doc_path.is_file():
            entries.append((doc, read_required_bytes(doc_path, "状态文档")))
    for zip_name, git_args in GIT_META_FILES.items():
        entries.append((zip_name, git_output(git_args, cwd=root).encode("utf-8")))
    entries.append(
        (
            "review_meta/git_diff_stat.txt",
            git_output(["diff", "--stat", args.base], cwd=root).encode("utf-8"),
        )
    )
    entries.append(
        ("review_meta/git_diff.patch", git_output(["diff", args.base], cwd=root).encode("utf-8"))
    )
    for rel in evidence:
        entries.append(
            (f"review_meta/{PurePosixPath(rel).name}", read_required_bytes(root / rel, "证据文件"))
        )

    if excluded:
        lines = ["# 未进入 ZIP 的 diff 变更（拒绝打包或不可按文本审阅）"]
        lines.extend(f"{rel}\t{reason}" for rel, reason in excluded)
        entries.append(("review_meta/excluded_files.txt", "\n".join(lines).encode("utf-8")))

    head = git_output(["rev-parse", "HEAD"], cwd=root).strip()
    info_lines = [
        f"stage={args.stage}",
        f"name={args.name}",
        f"base={args.base}",
        f"head={head}",
        f"generated_at={datetime.now(UTC).isoformat(timespec='seconds')}",
        "scope=git diff --name-only <base> (基线到工作树, 仅已跟踪文件)",
    ]
    entries.append(("review_meta/build_info.txt", "\n".join(info_lines).encode("utf-8")))

    used_names: set[str] = set()
    for name, _ in entries:
        used_names.add(name)
    for name, data in included:
        if name not in used_names:
            entries.append((name, data))
            used_names.add(name)

    out_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now(UTC).astimezone().strftime("%Y%m%d_%H%M")
    zip_path = out_dir / f"MediaReview_Review_Stage-{args.stage}_{stamp}.zip"
    try:
        with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
            for name, data in entries:
                zf.writestr(name, data)
    except OSError as exc:
        raise BuildError(f"写入 ZIP 失败: {zip_path} ({exc})") from exc

    size_bytes = zip_path.stat().st_size
    size_limit = args.max_zip_mb * 1024 * 1024
    if size_bytes > size_limit:
        zip_path.unlink()
        raise BuildError(f"ZIP 大小 {size_bytes} 字节超过上限 {size_limit} 字节，已删除输出")

    print(f"输出: {zip_path}")
    print(f"大小: {size_bytes} 字节")
    print(f"包含变更源文件 {len(included)} 个；排除 {len(excluded)} 个")
    print(f"测试证据: {', '.join(evidence)}")
    print("请核对 REVIEW_SUMMARY.md 中的结果与真实命令输出一致。")
    return zip_path


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        build_pack(args)
    except BuildError as exc:
        print(f"[build_review_handoff] 失败: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
