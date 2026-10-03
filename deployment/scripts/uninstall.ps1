#requires -Version 5.1
#requires -RunAsAdministrator
<#
  MediaReview Server 卸载脚本(1.2.0)
  默认: 精确停止本服务进程(按绝对路径)、删除计划任务、删除程序文件,**保留用户数据**。
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
$TaskName = "MediaReviewServer"
$Exe      = Join-Path $InstallDir "MediaReviewServer\MediaReviewServer.exe"

Write-Host "========== 卸载 MediaReview Server ==========" -ForegroundColor Cyan

# 1. 停止并删除计划任务
$task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
if ($task) {
    Write-Host "停止任务并删除 $TaskName ..."
    Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue
} else {
    Write-Host "未发现计划任务 $TaskName"
}

# 2. 结束精确归属的本服务进程(按绝对路径,不误杀其他同名/监听进程)
Get-Process MediaReviewServer -ErrorAction SilentlyContinue |
    Where-Object { $_.Path -eq $Exe } |
    Stop-Process -Force -ErrorAction SilentlyContinue

# 3. 删除程序文件(InstallDir)
if (Test-Path $InstallDir) {
    Remove-Item $InstallDir -Recurse -Force -ErrorAction SilentlyContinue
    Write-Host "已删除程序目录: $InstallDir"
} else {
    Write-Host "程序目录不存在: $InstallDir"
}

# 4. 删除防火墙规则
Get-NetFirewallRule -DisplayName "MediaReview Server*" -ErrorAction SilentlyContinue |
    Remove-NetFirewallRule -ErrorAction SilentlyContinue

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
