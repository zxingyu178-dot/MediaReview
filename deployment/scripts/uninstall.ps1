#requires -RunAsAdministrator
<#
  MediaReview Server 卸载脚本(Stage 16)
  默认: 停止服务、删除任务、删除程序文件,**保留用户数据**。
  只有显式加 -DeleteData 才删除 %ProgramData%\MediaReview 数据。
  用法:  .\uninstall.ps1            (保留数据)
         .\uninstall.ps1 -DeleteData (连数据一起删除)
#>
[CmdletBinding()]
param(
    [string]$InstallDir = "$env:ProgramFiles\MediaReviewServer",
    [string]$DataRoot   = "$env:ProgramData\MediaReview",
    [switch]$DeleteData
)
$ErrorActionPreference = "Continue"
$taskName = "MediaReviewServer"

Write-Host "========== 卸载 MediaReview Server ==========" -ForegroundColor Cyan

# 1. 停止并删除计划任务
$task = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
if ($task) {
    Write-Host "停止任务并删除 $taskName ..."
    Stop-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
    Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue
} else {
    Write-Host "未发现计划任务 $taskName"
}

# 2. 结束可能残留的进程
Get-Process Mediaserver -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue

# 3. 删除程序文件(InstallDir)
if (Test-Path $InstallDir) {
    Remove-Item $InstallDir -Recurse -Force -ErrorAction SilentlyContinue
    Write-Host "已删除程序目录: $InstallDir"
} else {
    Write-Host "程序目录不存在: $InstallDir"
}

# 4. 删除防火墙规则
$ruleName = "MediaReview Server"
Get-NetFirewallRule -DisplayName "$ruleName *" -ErrorAction SilentlyContinue | Remove-NetFirewallRule -ErrorAction SilentlyContinue

# 5. 数据目录(仅显式参数)
if ($DeleteData) {
    if (Test-Path $DataRoot) {
        Remove-Item $DataRoot -Recurse -Force -ErrorAction SilentlyContinue
        Write-Host "已删除数据目录: $DataRoot" -ForegroundColor Yellow
    }
} else {
    Write-Host "保留用户数据: $DataRoot (如需删除请加 -DeleteData)"
}

Write-Host "========== 卸载完成 ==========" -ForegroundColor Green
