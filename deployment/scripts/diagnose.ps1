#requires -Version 5.1
#requires -RunAsAdministrator
<#
  MediaReview Server 诊断脚本(1.1.0)
  收集: 版本/配置(脱敏)/health/日志/服务/端口/ffmpeg/Jellyfin/磁盘/数据库版本,
  输出 ZIP 到当前目录。
  用法:  .\diagnose.ps1  [-DataRoot ...] [-Port ...]
#>
[CmdletBinding()]
param(
    [string]$InstallDir = "$env:ProgramFiles\MediaReviewServer",
    [string]$DataRoot = "$env:ProgramData\MediaReview",
    [int]$Port = 8766
)
$ErrorActionPreference = "Continue"
$taskName = "MediaReviewServer"
$Exe = Join-Path $InstallDir "MediaReviewServer\MediaReviewServer.exe"
$outDir = Join-Path $PSScriptRoot "..\diagnostics"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$stamp = Get-Date -Format "yyyyMMdd_HHmm"
$work  = Join-Path $outDir "diag_$stamp"
New-Item -ItemType Directory -Force -Path $work | Out-Null

function Out-Diag($name, $content) {
    $path = Join-Path $work $name
    $content | Out-String | Set-Content $path -Encoding UTF8
    Write-Host "  - $name"
}

Write-Host "========== MediaReview 诊断 ==========" -ForegroundColor Cyan
$logDir = Join-Path $DataRoot "logs"

# 1. app version + health
try {
    $h = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/v1/system/health" -TimeoutSec 5
    Out-Diag "health.txt" "version: $($h.data.version)`nstatus: $($h.data.status)`ncomponents: $($h.data.components | ConvertTo-Json -Compress)"
} catch { Out-Diag "health.txt" "health_error: unreachable" }

# 2. 配置(脱敏)
$configFile = Join-Path $DataRoot "config\config.json"
if (Test-Path $configFile) {
    $cfg = Get-Content $configFile -Raw | ConvertFrom-Json
    $masked = $cfg
    if ($masked.jellyfin.api_key) { $masked.jellyfin.api_key = "********" }
    if ($masked.jellyfin.user_id) { $masked.jellyfin.user_id = "********" }
    $masked.jellyfin | Add-Member -NotePropertyName url_configured -Value ([bool]$masked.jellyfin.url) -Force
    $masked.jellyfin | Add-Member -NotePropertyName client_url_configured -Value ([bool]$masked.jellyfin.client_url) -Force
    $masked.jellyfin.PSObject.Properties.Remove('url')
    $masked.jellyfin.PSObject.Properties.Remove('client_url')
    Out-Diag "config_masked.json" ($masked | ConvertTo-Json -Depth 6)
} else { Out-Diag "config_masked.json" "config 不存在" }

# 3. 最近日志(脱敏)
$recent = @()
if (Test-Path $logDir) {
    Get-ChildItem $logDir -Filter "*.log" | Sort-Object LastWriteTime -Descending | Select-Object -First 3 | ForEach-Object {
        $text = Get-Content $_.FullName -Tail 400 | ForEach-Object {
            $_ -replace 'mr_[A-Za-z0-9]{20,}', 'mr_***' -replace 'Bearer\s+\S+', 'Bearer ***' -replace 'api_key\s*[=:]\s*\S+', 'api_key=***'
        }
        $recent += "[$($_.Name)]"
        $recent += $text
    }
}
Out-Diag "recent_logs.txt" $recent

# 4. 服务/任务状态
$task = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
Out-Diag "service.txt" $(if ($task) { "任务: $taskName" } else { "任务: 未注册" })

# 4.1 进程归属(按 EXE 绝对路径)
$owned = Get-Process MediaReviewServer -ErrorAction SilentlyContinue |
    Where-Object { $_.Path -eq $Exe } | Select-Object -First 1
if ($owned) {
    Out-Diag "process.txt" "进程: 运行中 PID=$($owned.Id) Path=$($owned.Path)"
} else {
    Out-Diag "process.txt" "进程: 未运行 (期望 $Exe)"
}

# 5. 端口
$listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
Out-Diag "port.txt" $(if ($listener) { "端口 $Port 监听中" } else { "端口 $Port 未监听" })

# 6. ffmpeg
$ff = Get-Command ffmpeg -ErrorAction SilentlyContinue
Out-Diag "ffmpeg.txt" $(if ($ff) { "ffmpeg: $($ff.Source)" } else { "ffmpeg: 未在 PATH 中找到" })

# 7. Jellyfin 连通性
$jfUrl = $null
if (Test-Path $configFile) {
    $cfg = Get-Content $configFile -Raw | ConvertFrom-Json
    $jfUrl = $cfg.jellyfin.url
}
if ($jfUrl) {
    try {
        $r = Invoke-WebRequest -Uri "$jfUrl/System/Info/Public" -UseBasicParsing -TimeoutSec 5
        Out-Diag "jellyfin.txt" "Jellyfin 可达: $($r.StatusCode)"
    } catch { Out-Diag "jellyfin.txt" "jellyfin_error: unreachable" }
} else { Out-Diag "jellyfin.txt" "未配置 Jellyfin" }

# 8. 磁盘
$drive = [System.IO.Path]::GetPathRoot($DataRoot)
$disk = Get-PSDrive -Name ($drive -replace '\\', '')
Out-Diag "disk.txt" "数据盘 $drive 剩余 $([math]::Round($disk.Free/1GB,1)) GB / 共 $([math]::Round(($disk.Free+$disk.Used)/1GB,1)) GB"

# 9. 数据库版本/表
$db = Join-Path $DataRoot "database\mediareview.db"
if (Test-Path $db) {
    $sizeMB = [math]::Round((Get-Item $db).Length/1MB, 2)
    Out-Diag "database.txt" "数据库: $db ($sizeMB MB)"
} else { Out-Diag "database.txt" "数据库不存在" }

# 打包 ZIP
$zip = Join-Path $outDir "mediareview_diag_$stamp.zip"
Compress-Archive -Path (Join-Path $work '*') -DestinationPath $zip -Force
Remove-Item $work -Recurse -Force
Write-Host ""
Write-Host "诊断包已生成: $zip" -ForegroundColor Green
