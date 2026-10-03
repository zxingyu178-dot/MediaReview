#requires -Version 5.1
#requires -RunAsAdministrator
<#
  MediaReview Server 启动脚本(1.2.0)
  精确按 MediaReviewServer.exe 绝对路径归属进程;已运行则直接返回。
  用法:  .\start.ps1 [-InstallDir ...] [-DataRoot ...] [-Port 8766] [-WaitHealthy]
#>
[CmdletBinding()]
param(
    [string]$InstallDir = "$env:ProgramFiles\MediaReviewServer",
    [string]$DataRoot   = "$env:ProgramData\MediaReview",
    [int]$Port          = 8766,
    [switch]$WaitHealthy
)
$ErrorActionPreference = "Stop"
$TaskName = "MediaReviewServer"
$ExeDir   = Join-Path $InstallDir "MediaReviewServer"
$Exe      = Join-Path $ExeDir "MediaReviewServer.exe"

function Get-OwnedProcess {
    Get-Process MediaReviewServer -ErrorAction SilentlyContinue |
        Where-Object { $_.Path -eq $Exe }
}

if (-not (Test-Path $Exe)) { throw "未找到服务端程序: $Exe" }

$running = Get-OwnedProcess
if ($running) {
    Write-Host "服务已在运行 (PID $($running.Id)): $Exe"
    exit 0
}

# 优先走计划任务(与开机自启同一入口);未注册则直接拉起进程。
$task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
if ($task) {
    schtasks /Run /TN $TaskName | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "通过计划任务 $TaskName 启动失败" }
} else {
    $env:MEDIAREVIEW_DATA_ROOT = $DataRoot
    Start-Process -FilePath $Exe -WorkingDirectory $ExeDir -WindowStyle Hidden
    Write-Host "已直接启动: $Exe"
}

if ($WaitHealthy) {
    for ($i = 0; $i -lt 30; $i++) {
        Start-Sleep -Seconds 1
        try {
            $h = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/v1/system/health" -TimeoutSec 2
            if ($h.success -and $h.data.status -eq "ok") {
                Write-Host "健康检查通过, 版本 $($h.data.version)"
                exit 0
            }
        } catch { }
    }
    Write-Warning "服务已启动但健康检查未通过, 请查看日志: $DataRoot\logs\server.log"
    exit 3
}
exit 0
