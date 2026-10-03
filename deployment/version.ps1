#requires -Version 5.1
<#
  MediaReview 部署版本 / API Contract 唯一事实源(Single Source of Truth)。

  所有部署脚本必须 dot-source 本文件(禁止各自写死版本号或 Contract):
      . "$PSScriptRoot\..\version.ps1"

  - $ExpectedServerVersion: Server 期望版本(给人看 / 部署 / 回滚);
  - $RequiredApiContract:   App 兼容性判断所需的 API Contract 下限。
  两者必须与 server/app/__init__.py 保持一致。
#>
$ExpectedServerVersion = "1.2.1"
$RequiredApiContract = 2
