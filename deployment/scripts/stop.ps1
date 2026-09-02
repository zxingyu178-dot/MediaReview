#requires -Version 5.1
#requires -RunAsAdministrator
<#
  MediaReview Server 停止脚本(1.1.0)
  仅停止精确位于 InstallDir 下的 MediaReviewServer.exe 进程,绝不按端口误杀
  其他监听进程;同时停止计划任务。
  用法:  .\stop.ps1 [-InstallDir ...] [-DataRoot ...] [-Port 8766]
#>
[CmdletBinding()]
param(
    [string]$InstallDir = "$env:ProgramFiles\MediaReviewServer",
    [string]$DataRoot   = "$env:ProgramData\MediaReview",
    [int]$Port          = 8766
)
$ErrorActionPreference = "Stop"
$TaskName = "MediaReviewServer"
$Exe      = Join-Path $InstallDir "MediaReviewServer\MediaReviewServer.exe"

$procs = Get-Process MediaReviewServer -ErrorAction SilentlyContinue |
    Where-Object { $_.Path -eq $Exe }
if ($procs) {
    $ids = @($procs | ForEach-Object { $_.Id })
    $procs | Stop-Process -Force -ErrorAction SilentlyContinue
    Write-Host "已停止服务进程 PID: $($ids -join ', ')"
} else {
    Write-Host "服务未在运行 (未发现 $Exe 对应的进程)"
}

$task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
if ($task) {
    Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
}
exit 0
