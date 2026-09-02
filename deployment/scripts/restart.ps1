#requires -Version 5.1
#requires -RunAsAdministrator
<#
  MediaReview Server 重启脚本(1.1.0)
  先精确停止本服务进程(按绝对路径),再启动并等待健康。
  用法:  .\restart.ps1 [-InstallDir ...] [-DataRoot ...] [-Port 8766]
#>
[CmdletBinding()]
param(
    [string]$InstallDir = "$env:ProgramFiles\MediaReviewServer",
    [string]$DataRoot   = "$env:ProgramData\MediaReview",
    [int]$Port          = 8766
)
$ErrorActionPreference = "Stop"
$TaskName = "MediaReviewServer"
$ExeDir   = Join-Path $InstallDir "MediaReviewServer"
$Exe      = Join-Path $ExeDir "MediaReviewServer.exe"

if (-not (Test-Path $Exe)) { throw "未找到服务端程序: $Exe" }

# ---- 停止(仅本服务进程,按绝对路径归属) ----
$procs = Get-Process MediaReviewServer -ErrorAction SilentlyContinue |
    Where-Object { $_.Path -eq $Exe }
if ($procs) {
    $procs | Stop-Process -Force -ErrorAction SilentlyContinue
    Write-Host "已停止旧进程"
}
$task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
if ($task) { Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue }

Start-Sleep -Milliseconds 800

# ---- 启动(与 start.ps1 同一入口) ----
$task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
if ($task) {
    schtasks /Run /TN $TaskName | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "通过计划任务 $TaskName 启动失败" }
} else {
    $env:MEDIAREVIEW_DATA_ROOT = $DataRoot
    Start-Process -FilePath $Exe -WorkingDirectory $ExeDir -WindowStyle Hidden
}

for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep -Seconds 1
    try {
        $h = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/v1/system/health" -TimeoutSec 2
        if ($h.success -and $h.data.status -eq "ok") {
            Write-Host "重启完成, 版本 $($h.data.version)"
            exit 0
        }
    } catch { }
}
Write-Warning "重启后健康检查未通过, 请查看日志: $DataRoot\logs\server.log"
exit 3
