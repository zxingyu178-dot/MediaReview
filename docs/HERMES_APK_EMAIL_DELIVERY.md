# Hermes 发送家庭媒体管家 APK 的规则

本流程只在 `家庭媒体管家-1.1.0.apk` 完成正式门禁后执行。构建阶段不得提前发送测试 APK冒充正式交付。

## 1. 授权和收件地址

- 用户已经要求“软件开发完成后将 APK 发到邮箱”，但发送前仍必须确认本次使用的收件地址。
- 可以询问用户确认地址，或让 Hermes 显示已配置 `QQ_RECEIVER` 的掩码形式供用户确认。
- 完整邮箱、SMTP 授权码和发件账号不得写入仓库、共享 handoff、日志或聊天。
- 不得在项目中新增邮箱凭据；Hermes 只使用自己房间的 `config/secrets.env`。

## 2. 发送前 APK 门禁

必须同时满足：

1. 版本名 `1.1.0`，包名仍为 `com.mediareview.app`。
2. Release APK 签名验证通过。
3. 与用户手机已安装 APK 的签名证书指纹一致，能覆盖升级；原签名不可获得时必须先向用户说明需要卸载重装并取得决定。
4. 真机验收通过；否则文件名和邮件主题必须明确为 RC，不得冒充正式版。
5. 文件存在、大小大于 0、SHA-256 已写入 `CHECKSUMS.txt`。
6. Windows Defender 或等价扫描没有发现威胁。
7. Git 提交、测试报告和 APK SHA-256 对应同一发布候选。

PowerShell 记录命令：

```powershell
$apk = 'D:\MediaReview_1.1_Handoff\release\家庭媒体管家-1.1.0.apk'
if (-not (Test-Path -LiteralPath $apk)) { throw "APK missing: $apk" }
$item = Get-Item -LiteralPath $apk
if ($item.Length -le 0) { throw "APK is empty" }
$hash = Get-FileHash -LiteralPath $apk -Algorithm SHA256
"APK=$($item.Name) BYTES=$($item.Length) SHA256=$($hash.Hash)"
```

## 3. 通过共享目录交给 Hermes

项目 Agent 不直接写 Hermes 房间。按 AI Home 协作规则：

1. 把 APK 和 `CHECKSUMS.txt` 复制到 `E:\aihome\shared\outbox\<当前-agent>\mediareview-1.1.0\`。
2. 在 `E:\aihome\shared\inbox\hermes\` 创建 `YYYY-MM-DD-mediareview-apk-email.md`。
3. 请求文件只写：附件路径、文件名、字节数、SHA-256、邮件主题/正文、收件地址“已由用户确认”状态；不写邮箱明文和凭据。
4. 写入 inbox 不会自动执行。必须通过 AI Home 的 Agent 协作入口显式通知/唤醒 Hermes，并取得“已接收此请求文件”的回执；不能把文件落盘当成已经派发。
5. Hermes 读取请求后使用自己的邮件配置发送。

如果接管 Agent 不在 AI Home 主机，停止在此边界：只交回 APK、`CHECKSUMS.txt` 和验证报告，让 AI Home 主机上的 Hermes 执行发送。不得自建 SMTP、复制 Hermes 配置或索要授权码。

## 4. Hermes 工具位置和当前限制

- 工具：`E:\aihome\hermes\scripts\send_mail.py`
- 凭据：`E:\aihome\hermes\config\secrets.env`（只有 Hermes 使用；禁止其他 Agent 读取或复制）
- 共享知识：`E:\aihome\shared\knowledge-base\patterns\hermes-mail-non-image-attachment.md`

截至 2026-08-30，现有 `send_mail.py` 仍使用 `MIMEImage` 处理全部 `--attach`，而且附件缺失/构造失败后可能继续发送正文并返回成功。**因此当前脚本不能直接用于 APK。**

Hermes 必须先在自己的房间完成并验证以下修复：

- APK 使用 `application/vnd.android.package-archive`（例如 `MIMEApplication(data, _subtype="vnd.android.package-archive")`）。
- ZIP/PDF 使用对应 MIME，不再交给 `MIMEImage`。
- 任一附件不存在、读取失败、超过配置的邮件大小上限或 MIME 构造失败时，整个命令以非零退出且不发送邮件。
- `smtplib.sendmail()` 返回的拒收字典必须为空；存在任何拒收收件人即失败。
- 文件名、字节数和 SHA-256 必须从实际附加到 MIME 消息的同一份字节计算，不能只检查请求路径。
- 日志和成功输出中的收件地址必须掩码，不能打印完整邮箱。
- 成功输出必须包含附件文件名和准确字节数，例如：

```text
SMTP accepted
SEND_OK attachment=家庭媒体管家-1.1.0.apk bytes=22344728 sha256=<64-hex>
```

推荐同时输出附件 SHA-256。只看到“邮件发送成功”不算交付成功。

## 5. 修复后的发送命令形态

由 Hermes 在自己的环境中执行；Python 路径由 Hermes 自己发现，不写死项目机器路径：

```powershell
python E:\aihome\hermes\scripts\send_mail.py `
  '家庭媒体管家 1.1.0 APK' `
  '附件为已通过验收的家庭媒体管家 APK；SHA-256 与版本信息见正文和 CHECKSUMS.txt。' `
  --attach 'E:\aihome\shared\outbox\<当前-agent>\mediareview-1.1.0\家庭媒体管家-1.1.0.apk'
```

只有用户明确给出并确认新的地址时才使用 `--to`；否则使用 Hermes 已配置且经用户掩码确认的 `QQ_RECEIVER`。

## 6. 邮件交付验收

Hermes 必须返回并由项目 Agent记录：

- SMTP 操作成功。
- `sendmail()` 没有拒收收件人，并记录 `SMTP accepted`。
- `SEND_OK attachment=<exact filename> bytes=<exact attached bytes> sha256=<matching hash>`。
- 邮件中的 SHA-256 与本地 `CHECKSUMS.txt` 一致。
- 没有“附件不存在”“添加附件失败”或降级成正文-only。

任一条件缺失，只能报告“邮件发送未验证/失败”，不得说 APK 已发出。
