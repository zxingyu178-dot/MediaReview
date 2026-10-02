#!/usr/bin/env python3
"""MediaReview 阶段交接交付工具（collect → build → send → verify）。

替代此前每阶段复制一份的 temp 脚本（run_stage8c*/build_stage8c*/send_stage8c*/
verify_stage8c*），统一为项目内单一入口。

设计约束：

- 仅使用 Python 标准库；
- ``build`` 内置 **Delivery Integrity Check**：``00_HANDOFF.md`` 的 Head、
  ``git/LOG.txt`` 首行、ZIP 文件名三者必须是同一个 FINAL HEAD，且文档中不得出现
  非真实提交的 short SHA、不得提到旧 SHA 的 Handoff ZIP 名、ZIP 内不得有 APK；
  任一不满足 → PACKAGING FAIL（退出码 2，不产出交付包）；
- ``send`` 使用标准邮件头（``Date`` / ``Message-ID`` / 显示名）：
  历史脚本漏掉 ``Date`` 会导致部分客户端排序错乱甚至不显示（本次交付修复）；
- ``verify`` 通过 IMAP 在 **INBOX 与 Junk 等全部文件夹**回读附件，
  与本地文件做 SHA-256 字节比对，并校验 ``Date`` / ``Message-ID`` 已存在；
- 收件人/发件人凭据复用 hermes 配置（``projects/hermes/config/secrets.env``），
  只读取不打印；本工具输出中不包含任何凭据。

用法::

    # 一次性跑完 collect → build → send → verify
    python scripts/deliver_stage_handoff.py all --stage 8C.2 --base <base-sha>

    # 分步执行
    python scripts/deliver_stage_handoff.py collect --stage 8C.2 --base <base-sha>
    python scripts/deliver_stage_handoff.py build   --stage 8C.2
    python scripts/deliver_stage_handoff.py send    --stage 8C.2 --subject-note 重发
    python scripts/deliver_stage_handoff.py verify  --stage 8C.2
"""

from __future__ import annotations

import argparse
import email
import hashlib
import imaplib
import os
import re
import shutil
import smtplib
import subprocess
import sys
import zipfile
from datetime import datetime
from email.header import Header, decode_header
from email.mime.application import MIMEApplication
from email.mime.multipart import MIMEMultipart
from email.mime.text import MIMEText
from email.utils import formataddr, formatdate, make_msgid
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
AIHOME = Path(r"D:\AIHome_2.0_L1_L2")
TEMP = AIHOME / "temp"
SECRETS = AIHOME / "projects" / "hermes" / "config" / "secrets.env"
HANDOFF_DIR = REPO / "review_handoff"

# 日志文件命名 → temp 里的匹配规则（顺序即优先级）
LOG_PATTERNS: list[tuple[str, str]] = [
    ("server_pytest_raw.txt", r"stage{tag}_server_pytest_raw\.txt$"),
    ("android_jvm_raw.txt", r"stage{tag}.*_android_jvm_raw\.txt$"),
    ("lintDebug_raw.txt", r"stage{tag}.*_lint.*\.txt$"),
    ("compileDebugKotlin_raw.txt", r"stage{tag}.*_compile.*\.txt$"),
    ("android_instrumentation_raw.txt", r"stage{tag}.*(instrumentation|androidtest).*\.txt$"),
]


# ---------------------------------------------------------------- 通用


def run_git(*args: str) -> str:
    # 参数全部来自本工具内部常量与 CLI 引用；git 从 PATH 解析是有意行为。
    return subprocess.run(  # noqa: S603
        ["git", *args],  # noqa: S607
        cwd=str(REPO),
        capture_output=True,
        text=True,
        check=True,
    ).stdout


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def tag_of(stage: str) -> str:
    """'8C.2' -> '8c2'（用于 temp 日志文件名与包目录名）。"""
    return stage.replace(".", "").lower()


def pkg_dir(stage: str) -> Path:
    return HANDOFF_DIR / f"stage{tag_of(stage)}_pkg"


def fail(message: str, code: int = 1) -> None:
    print(f"[FAIL] {message}")
    raise SystemExit(code)


def head_info() -> tuple[str, str]:
    head = run_git("rev-parse", "HEAD").strip()
    return head, head[:7]


# ---------------------------------------------------------------- collect


def cmd_collect(stage: str, base: str) -> None:
    pkg = pkg_dir(stage)
    git_dir = pkg / "git"
    git_dir.mkdir(parents=True, exist_ok=True)

    head, short = head_info()
    (git_dir / "STATUS.txt").write_text(
        run_git("status", "--porcelain", "--branch"), encoding="utf-8"
    )
    (git_dir / "LOG.txt").write_text(run_git("log", "--oneline", "-12"), encoding="utf-8")
    (git_dir / "DIFF_STAT.txt").write_text(
        run_git("diff", "--stat", f"{base}..HEAD"), encoding="utf-8"
    )
    (git_dir / "CHANGED_FILES.txt").write_text(
        run_git("diff", "--name-status", f"{base}..HEAD"), encoding="utf-8"
    )

    logs_dir = pkg / "logs"
    logs_dir.mkdir(parents=True, exist_ok=True)
    tag = tag_of(stage)
    copied = 0
    for name, pattern in LOG_PATTERNS:
        regex = re.compile(pattern.format(tag=tag))
        for candidate in sorted(TEMP.glob("*.txt")):
            if regex.search(candidate.name):
                shutil.copy2(candidate, logs_dir / name)
                print(f"log  {name}  <- {candidate.name} ({candidate.stat().st_size} B)")
                copied += 1
                break
        else:
            print(f"warn 缺少日志: {name}（pattern={pattern}）")

    changed = run_git("diff", "--name-only", f"{base}..HEAD").split()
    dst_root = pkg / "source"
    src_copied = 0
    for rel in changed:
        src = REPO / rel
        if not src.is_file():
            print(f"skip(missing) {rel}")
            continue
        dst = dst_root / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)
        src_copied += 1

    log_first = (git_dir / "LOG.txt").read_text(encoding="utf-8").splitlines()[0].split()[0]
    print(f"head={head} short={short} log_first={log_first} match={log_first == short}")
    print(f"collect done: logs={copied} sources={src_copied} pkg={pkg}")
    if log_first != short:
        fail("git/LOG.txt 首行与 HEAD 不一致（请先提交再 collect）")


# ---------------------------------------------------------------- build


def cmd_build(stage: str, date: str, subject_note: str = "") -> Path:
    pkg = pkg_dir(stage)
    if not (pkg / "00_HANDOFF.md").is_file():
        fail(f"缺少 {pkg / '00_HANDOFF.md'}（请先写交接文档再打包）")
    if not (pkg / "git" / "LOG.txt").is_file():
        fail("缺少 git evidence（请先运行 collect）")

    head, short = head_info()
    handoff = (pkg / "00_HANDOFF.md").read_text(encoding="utf-8")
    acceptance = (pkg / "01_ACCEPTANCE.md").read_text(encoding="utf-8")
    log_first = (pkg / "git" / "LOG.txt").read_text(encoding="utf-8").splitlines()[0].split()[0]

    # --- Delivery Integrity Check ---
    if head not in handoff:
        fail(
            f"00_HANDOFF.md 未包含 FINAL HEAD 全量 SHA {head}；"
            "交付包必须在其对应的最终提交上生成（先 checkout 该提交再 build）"
        )
    if log_first != short:
        fail(
            f"git/LOG.txt 首行 {log_first} != FINAL HEAD short {short}；"
            "请先运行 collect（在最终提交之后）再 build"
        )
    if short not in handoff:
        fail(f"00_HANDOFF.md 未使用 FINAL short SHA {short}")

    def is_real_commit(sha: str) -> bool:
        # sha 来自包内文档的 short SHA 正则提取，仅用于 git 存在性探测。
        probe = subprocess.run(  # noqa: S603
            ["git", "cat-file", "-e", f"{sha}^{{commit}}"],  # noqa: S607
            cwd=str(REPO),
            capture_output=True,
        )
        return probe.returncode == 0

    for name, text in (("00_HANDOFF.md", handoff), ("01_ACCEPTANCE.md", acceptance)):
        bogus = sorted({s for s in re.findall(r"\b[0-9a-f]{7}\b", text) if not is_real_commit(s)})
        if bogus:
            fail(f"{name} 含不存在的 short SHA: {bogus}")
        zips = re.findall(r"MediaReview_Stage\S*?_Handoff_\d+_([0-9a-f]{7})\.zip", text)
        mismatched = sorted({z for z in zips if z != short})
        if mismatched:
            fail(f"{name} 提到的 Handoff ZIP 使用了非 FINAL SHA: {mismatched}")

    zip_name = f"MediaReview_Stage{stage}_Handoff_{date}_{short}.zip"
    zip_path = HANDOFF_DIR / zip_name

    # --- SHA256SUMS（包内关键文件；不含自身/不含 APK） ---
    lines: list[str] = []
    for root, _dirs, files in os.walk(pkg):
        for name in sorted(files):
            if name == "SHA256SUMS.txt":
                continue
            full = Path(root) / name
            lines.append(f"{sha256_file(full)}  {full.relative_to(pkg).as_posix()}")
    (pkg / "SHA256SUMS.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")

    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as archive:
        for root, _dirs, files in os.walk(pkg):
            for name in sorted(files):
                full = Path(root) / name
                archive.write(full, f"{pkg.name}/{full.relative_to(pkg).as_posix()}")

    names = zipfile.ZipFile(zip_path).namelist()
    apks = [n for n in names if n.lower().endswith(".apk")]
    if apks:
        zip_path.unlink(missing_ok=True)
        fail(f"ZIP 中不允许出现 APK: {apks}")

    zip_sha = sha256_file(zip_path)
    outside = HANDOFF_DIR / f"MediaReview_Stage{stage}_Handoff_{date}_{short}_SHA256.txt"
    outside.write_text(
        f"MediaReview Stage {stage} 交付校验（小版本：仅 Handoff ZIP，NO USER APK）\n"
        f"final_head {head}\n"
        f"zip  {zip_name}  {zip_path.stat().st_size} bytes  sha256={zip_sha}\n"
        "apk  NOT GENERATED — SMALL VERSION POLICY\n",
        encoding="utf-8",
    )

    print(
        f"build done: {zip_name} ({zip_path.stat().st_size} B) sha256={zip_sha} "
        f"entries={len(names)} apk=[]"
    )
    print("INTEGRITY OK", f"short={short}", f"subject_note={subject_note or '-'}")
    return zip_path


# ---------------------------------------------------------------- send


def load_mail_env() -> dict[str, str]:
    if not SECRETS.is_file():
        fail(f"未找到发信配置: {SECRETS}")
    values: dict[str, str] = {}
    for line in SECRETS.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        values.setdefault(key.strip(), value.strip().strip("'\""))
    return values


def newest_zip(stage: str) -> Path:
    candidates = sorted(
        HANDOFF_DIR.glob(f"MediaReview_Stage{stage}_Handoff_*.zip"),
        key=lambda p: p.stat().st_mtime,
    )
    if not candidates:
        fail(f"未找到 Stage {stage} 的 Handoff ZIP（请先 build）")
    return candidates[-1]


def build_message(
    stage: str,
    env: dict[str, str],
    zip_path: Path,
    subject_note: str,
    body: str,
) -> MIMEMultipart:
    sender = env.get("QQ_SENDER", "")
    receiver = env.get("QQ_RECEIVER", "")
    if not (sender and receiver):
        fail("缺少 QQ_SENDER / QQ_RECEIVER")

    msg = MIMEMultipart()
    # 标准头：Date / Message-ID 必须显式设置（历史脚本缺失 Date 导致客户端显示异常）
    msg["Date"] = formatdate(localtime=True)
    msg["Message-ID"] = make_msgid(domain="mediareview.delivery")
    msg["From"] = formataddr(("MediaReview Delivery", sender))
    msg["To"] = formataddr(("MediaReview Owner", receiver))
    note = f"（{subject_note}）" if subject_note else ""
    msg["Subject"] = Header(
        f"MediaReview Stage {stage} 交付{note} — 交接包（小版本，无 APK）", "utf-8"
    )
    msg["X-Mailer"] = "MediaReview-Delivery/1.0"

    digest = sha256_file(zip_path)
    size = zip_path.stat().st_size
    msg.attach(
        MIMEText(
            body.format(
                stage=stage,
                name=zip_path.name,
                size_kib=size / 1024,
                sha256=digest,
                now=datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
            ),
            "plain",
            "utf-8",
        )
    )
    with zip_path.open("rb") as handle:
        part = MIMEApplication(handle.read(), Name=zip_path.name)
    part.add_header("Content-Disposition", "attachment", filename=zip_path.name)
    msg.attach(part)
    return msg


DEFAULT_BODY = """MediaReview 2.0 Stage {stage} 交付（交接包，小版本不附 APK）。

【附件】{name}（{size_kib:.1f} KiB）
【SHA-256】{sha256}
【生成时间】{now}

本邮件为交付交接包，包含该阶段的 00~08 交接文档、Git 证据（STATUS/LOG/DIFF/
CHANGED_FILES）、原始测试日志与本阶段变更源码。
"""


def cmd_send(stage: str, subject_note: str, zip_path: Path | None, body: str) -> Path:
    env = load_mail_env()
    sender = env.get("QQ_SENDER", "")
    auth = env.get("QQ_AUTH_CODE", "")
    receiver = env.get("QQ_RECEIVER", "")
    host = env.get("QQ_SMTP_SERVER", "smtp.qq.com")
    port = int(env.get("QQ_SMTP_PORT", "465"))
    if not (sender and auth):
        fail("缺少 QQ_SENDER / QQ_AUTH_CODE")

    target = zip_path or newest_zip(stage)
    msg = build_message(stage, env, target, subject_note, body)
    try:
        server = smtplib.SMTP_SSL(host, port, timeout=1800)
        server.login(sender, auth)
        server.sendmail(sender, [receiver], msg.as_string())
        server.quit()
    except Exception as exc:  # noqa: BLE001
        fail(f"发送失败: {exc}")
    print(
        f"send done: {target.name} ({target.stat().st_size} B) "
        f"sha256={sha256_file(target)} message_id={msg['Message-ID']}"
    )
    return target


# ---------------------------------------------------------------- verify


def _subject_of(msg: email.message.Message) -> str:
    parts = decode_header(msg.get("Subject", ""))
    return "".join(
        (p.decode(enc or "utf-8", errors="replace") if isinstance(p, bytes) else p)
        for p, enc in parts
    )


def cmd_verify(stage: str, zip_path: Path | None, folders: list[str] | None = None) -> None:
    env = load_mail_env()
    user = env.get("QQ_SENDER", "")
    auth = env.get("QQ_AUTH_CODE", "")
    if not (user and auth):
        fail("缺少 QQ_SENDER / QQ_AUTH_CODE")

    target = zip_path or newest_zip(stage)
    local_sha = sha256_file(target)

    conn = imaplib.IMAP4_SSL("imap.qq.com", 993)
    conn.login(user, auth)
    matches: list[dict] = []
    try:
        if folders is None:
            folders = []
            typ, boxes = conn.list()
            for line in boxes or []:
                if isinstance(line, bytes):
                    text = line.decode("utf-8", errors="replace")
                    folders.append(text.split(' "/" ')[-1].strip().strip('"'))
        for folder in folders:
            try:
                typ, _ = conn.select(f'"{folder}"', readonly=True)
            except imaplib.IMAP4.error:
                continue
            if typ != "OK":
                continue
            typ, data = conn.search(None, "ALL")
            ids = data[0].split() if data and data[0] else []
            for num in ids[-30:]:
                typ, raw = conn.fetch(num, "(INTERNALDATE RFC822)")
                if typ != "OK" or not raw or not isinstance(raw[0], tuple):
                    continue
                meta = raw[0][0]
                if isinstance(meta, bytes):
                    meta = meta.decode("utf-8", errors="replace")
                msg = email.message_from_bytes(raw[0][1])
                for part in msg.walk():
                    filename = (part.get_filename() or "").strip()
                    if filename != target.name:
                        continue
                    payload = part.get_payload(decode=True) or b""
                    matches.append(
                        {
                            "folder": folder,
                            "internal_date": meta,
                            "date": msg.get("Date"),
                            "message_id": msg.get("Message-ID"),
                            "subject": _subject_of(msg),
                            "sha_match": hashlib.sha256(payload).hexdigest() == local_sha,
                            "size": len(payload),
                        }
                    )
                    break
    finally:
        conn.logout()

    if not matches:
        fail(f"全部文件夹均未找到附件 {target.name}（投递失败）")

    for item in matches:
        print(
            f"[{'OK' if item['sha_match'] else 'STALE'}] folder={item['folder']} "
            f"size={item['size']} date={item['date']!r} "
            f"message_id={item['message_id']!r} subject={item['subject'][:56]}"
        )

    # 邮件按投递时间升序返回；取"最新一条"作为本次发送的验证目标
    latest = matches[-1]
    if not latest["sha_match"]:
        fail("最新一封的附件 SHA-256 与本地不一致（发送后本地文件被改动？）")
    if not latest["date"]:
        fail("最新一封缺少 Date 头（客户端可能不显示）")
    if not latest["message_id"]:
        fail("最新一封缺少 Message-ID 头")
    print(f"verify done: ALL OK（最新一封 SHA/Date/Message-ID 全部通过，共匹配 {len(matches)} 封）")


# ---------------------------------------------------------------- main


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="MediaReview 阶段交接交付工具")
    parser.add_argument(
        "action", choices=["collect", "build", "send", "verify", "all"], help="执行的动作"
    )
    parser.add_argument("--stage", required=True, help="阶段号，如 8C.2")
    parser.add_argument("--base", default="", help="collect 需要的基线提交（base sha/ref）")
    parser.add_argument("--date", default=datetime.now().strftime("%Y%m%d"), help="交付日期")
    parser.add_argument("--subject-note", default="", help="邮件主题附加说明（如 重发）")
    parser.add_argument("--zip", default="", help="指定 ZIP 路径（send/verify 用）")
    parser.add_argument("--body", default=DEFAULT_BODY, help="邮件正文模板")
    args = parser.parse_args(argv)

    stage = args.stage
    zip_path = Path(args.zip) if args.zip else None

    if args.action in ("collect", "all"):
        if not args.base:
            fail("collect 需要 --base（基线提交）")
        cmd_collect(stage, args.base)
    if args.action in ("build", "all"):
        cmd_build(stage, args.date, args.subject_note)
    if args.action in ("send", "all"):
        cmd_send(stage, args.subject_note, zip_path, args.body)
    if args.action in ("verify", "all"):
        cmd_verify(stage, zip_path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
