#requires -RunAsAdministrator
<#
  MediaReview Server 修复脚本(Stage 16)
  检查缺失文件/服务/端口/ffmpeg/config/DB,重建服务,**不删除用户数据库**。
  用法:  .\repair.ps1
#>
[CmdletBinding()]
param(
    [string]$InstallDir = "$env:ProgramFiles\MediaReviewServer",
    [string]$DataRoot   = "$env:ProgramData\MediaReview",
    [int]$Port          = 8765
)
$ErrorActionPreference = "Stop"
$ScriptDir = $PSScriptRoot
$issues = @()

function Check($name, [bool]$ok, [string]$detail) {
    if ($ok) { Write-Host "  [OK]  $name - $detail" -ForegroundColor Green }
    else     { Write-Host "  [!!]  $name - $detail" -ForegroundColor Yellow; $script:issues += $name }
}

Write-Host "========== MediaReview 修复检查 ==========" -ForegroundColor Cyan
$startCmd = Join-Path $InstallDir "start_server.cmd"
$taskName = "MediaReviewServer"

# 1. 文件完整性
Write-Host "文件检查"
$exe = Join-Path $InstallDir "MediaReviewServer\Mediaserver.exe"
Check "服务端可执行文件" (Test-Path $exe) $exe
Check "启动脚本" (Test-Path $startCmd) $startCmd

# 2. 配置
Write-Host "配置检查"
$configFile = Join-Path $DataRoot "config\config.json"
Check "config.json" (Test-Path $configFile) $configFile

# 3. 数据库
Write-Host "数据库检查"
$db = Join-Path $DataRoot "database\mediareview.db"
Check "数据库文件" (Test-Path $db) $db

# 4. FFmpeg
Write-Host "FFmpeg 检查"
$bundled = Join-Path $InstallDir "ffmpeg\ffmpeg.exe"
$sys = Get-Command ffmpeg -ErrorAction SilentlyContinue
Check "FFmpeg" ($(Test-Path $bundled) -or $null -ne $sys) ($(if (Test-Path $bundled) { $bundled } else { if ($sys) { $sys.Source } else { "缺失" } }))

# 5. 端口
Write-Host "端口检查"
$listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
Check "端口 $Port" ($null -eq $listener) $(if ($listener) { "已被占用" } else { "空闲" })

# 6. 任务计划
Write-Host "服务/任务检查"
$task = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
Check "计划任务 $taskName" ($null -ne $task) $(if ($task) { "已注册" } else { "未注册" })

# 7. 重建缺失项
if (-not (Test-Path $exe)) {
    Write-Host "重新复制服务端文件..."
    $src = Join-Path $ScriptDir "..\server\MediaReviewServer"
    if (Test-Path (Join-Path $src "Mediaserver.exe")) {
        Copy-Item -Path $src -Destination $InstallDir -Recurse -Force
    } else {
        Write-Host "部署包中缺少 Mediaserver.exe,无法重建,请重新解压部署包" -ForegroundColor Red
    }
}
if (-not (Test-Path $startCmd) -or -not $task) {
    Write-Host "重建开机自启任务..."
    if (-not (Test-Path $startCmd)) {
        @"
@echo off
set "MEDIAREVIEW_DATA_ROOT=$DataRoot"
cd /d "%~dp0MediaReviewServer"
start "" "%~dp0MediaReviewServer\Mediaserver.exe"
"@ | Set-Content $startCmd -Encoding ASCII
    }
    schtasks /Create /F /TN $taskName /TR "`"$startCmd`"" /SC ONSTART /RU SYSTEM /RL HIGHEST | Out-Null
    Check "计划任务重建" ($LASTEXITCODE -eq 0) "schtasks"
}
if (-not $task) {
    schtasks /Run /TN $taskName | Out-Null
}

if ($issues.Count -eq 0) {
    Write-Host "========== 全部正常 ==========" -ForegroundColor Green
    exit 0
} else {
    Write-Host "存在需人工处理项: $($issues -join ', ')" -ForegroundColor Yellow
    exit 2
}
