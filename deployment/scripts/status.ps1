#requires -Version 5.1
<#
  MediaReview Server 状态脚本(1.1.0,只读)
  报告: 安装、进程(PID/路径)、计划任务、端口、健康版本。
  退出码: 0 运行且健康 / 1 未安装 / 2 已安装未运行 / 3 运行但不健康
  用法:  .\status.ps1 [-InstallDir ...] [-DataRoot ...] [-Port 8766]
#>
[CmdletBinding()]
param(
    [string]$InstallDir = "$env:ProgramFiles\MediaReviewServer",
    [string]$DataRoot   = "$env:ProgramData\MediaReview",
    [int]$Port          = 8766
)
$ErrorActionPreference = "Continue"
$TaskName = "MediaReviewServer"
$Exe      = Join-Path $InstallDir "MediaReviewServer\MediaReviewServer.exe"

Write-Host "========== MediaReview Server 状态 ==========" -ForegroundColor Cyan

# 1. 安装
if (-not (Test-Path $Exe)) {
    Write-Host "状态: 未安装 (缺少 $Exe)" -ForegroundColor Yellow
    exit 1
}
Write-Host "安装: $Exe"

# 2. 版本标记
$verFile = Join-Path $InstallDir "VERSION.txt"
if (Test-Path $verFile) {
    Write-Host "版本标记: $((Get-Content $verFile -Raw).Trim())"
}

# 3. 进程(按绝对路径归属)
$proc = Get-Process MediaReviewServer -ErrorAction SilentlyContinue |
    Where-Object { $_.Path -eq $Exe } | Select-Object -First 1
if ($proc) {
    Write-Host "进程: 运行中 (PID $($proc.Id))"
} else {
    Write-Host "进程: 未运行"
}

# 4. 计划任务
$task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
if ($task) { Write-Host "计划任务: 已注册 ($($task.State))" }
else { Write-Host "计划任务: 未注册" }

# 5. 端口监听
$listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
if ($listener) { Write-Host "端口: TCP $Port 监听中" }
else { Write-Host "端口: TCP $Port 未监听" }

# 6. 健康与版本
if (-not $proc) {
    Write-Host "状态: 已安装但未运行" -ForegroundColor Yellow
    exit 2
}
try {
    $h = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/v1/system/health" -TimeoutSec 5
    if ($h.success -and $h.data.status -eq "ok") {
        Write-Host "健康: ok, 版本 $($h.data.version)" -ForegroundColor Green
        exit 0
    }
    Write-Host "健康: 异常 ($($h.data.status))" -ForegroundColor Yellow
    exit 3
} catch {
    Write-Host "健康: 不可达 (进程在但未响应)" -ForegroundColor Yellow
    exit 3
}
