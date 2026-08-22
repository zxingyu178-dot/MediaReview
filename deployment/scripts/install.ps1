#requires -RunAsAdministrator
<#
  MediaReview Server 安装脚本(Stage 16)
  用法: 以管理员身份运行  .\install.ps1  [可选参数]
  参数:
    -InstallDir  安装目录   (默认 $env:ProgramFiles\MediaReviewServer)
    -DataRoot    数据目录   (默认 $env:ProgramData\MediaReview)
    -Port        服务端口   (默认 8765)
#>
[CmdletBinding()]
param(
    [string]$InstallDir = "$env:ProgramFiles\MediaReviewServer",
    [string]$DataRoot   = "$env:ProgramData\MediaReview",
    [int]$Port          = 8765
)
$ErrorActionPreference = "Stop"
$ScriptDir = $PSScriptRoot
$Report    = @()
$Global:LASTEXITCODE = 0

function Write-Step($msg) { Write-Host "==> $msg" -ForegroundColor Cyan }
function Write-Report($msg) { $script:Report += "$msg" }

try {
    # ---- 1. 管理员权限(参数要求)与 OS 检查 ----
    Write-Step "操作系统与权限检查"
    $os = Get-CimInstance Win32_OperatingSystem
    Write-Report "OS: $($os.Caption) $($os.Version) ($env:PROCESSOR_ARCHITECTURE)"

    # ---- 2. 源文件检查 ----
    Write-Step "检查部署包完整性"
    $srcServer = Join-Path $ScriptDir "..\server\MediaReviewServer"
    if (-not (Test-Path (Join-Path $srcServer "Mediaserver.exe"))) {
        throw "未找到服务器可执行文件: $srcServer\Mediaserver.exe"
    }
    Write-Report "Server 源: $srcServer"

    # ---- 3. 复制程序文件 ----
    Write-Step "复制程序到 $InstallDir"
    New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
    Copy-Item -Path $srcServer -Destination $InstallDir -Recurse -Force
    Write-Report "安装目录: $InstallDir"

    # ---- 4. 数据目录与配置模板 ----
    Write-Step "初始化数据目录 $DataRoot"
    $configDir  = Join-Path $DataRoot "config"
    New-Item -ItemType Directory -Force -Path $configDir | Out-Null
    $configFile = Join-Path $configDir "config.json"
    if (-not (Test-Path $configFile)) {
        $example = Join-Path $ScriptDir "..\config.example.json"
        if (Test-Path $example) {
            Copy-Item $example $configFile
            Write-Host "已生成配置模板: $configFile"
        } else {
            Write-Warning "缺少 config.example.json,跳过配置模板生成"
        }
    }
    # 强制写入数据目录与端口(用户可能用参数覆盖)
    $config = @{}
    if (Test-Path $configFile) {
        $config = Get-Content $configFile -Raw | ConvertFrom-Json -AsHashtable
    }
    if (-not $config.ContainsKey("server")) { $config["server"] = @{} }
    $config["server"]["port"] = $Port
    if (-not $config.ContainsKey("storage")) { $config["storage"] = @{} }
    $config["storage"]["data_root"] = $DataRoot

    # ---- 5. ffmpeg 检查与配置 ----
    Write-Step "检查 FFmpeg"
    $ffmpegDir = Join-Path $InstallDir "ffmpeg"
    $ffmpegExe = Join-Path $ffmpegDir "ffmpeg.exe"
    if (Test-Path $ffmpegExe) {
        $config["storage"]["ffmpeg_dir"] = $ffmpegDir
        Write-Host "使用随包 FFmpeg: $ffmpegDir"
        Write-Report "ffmpeg: bundled $ffmpegDir"
    } else {
        $systemFfmpeg = Get-Command ffmpeg -ErrorAction SilentlyContinue
        if ($systemFfmpeg) {
            $config["storage"]["ffmpeg_dir"] = ""
            Write-Host "使用系统 PATH 中的 ffmpeg: $($systemFfmpeg.Source)"
            Write-Report "ffmpeg: system $($systemFfmpeg.Source)"
        } else {
            $config["storage"]["ffmpeg_dir"] = ""
            Write-Warning "未找到 ffmpeg!雪碧图功能将不可用。请将 ffmpeg.exe/ffprobe.exe 放入 $ffmpegDir 后运行 repair.ps1"
            Write-Report "ffmpeg: MISSING (雪碧图将不可用)"
        }
    }
    $config | ConvertTo-Json -Depth 6 | Set-Content $configFile -Encoding UTF8
    Write-Report "配置: $configFile"

    # ---- 6. 端口冲突检查 ----
    Write-Step "检查端口 $Port 占用"
    $listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    if ($listener) {
        throw "端口 $Port 已被占用,请先停止占用进程或改用 -Port 参数"
    }
    Write-Report "端口 $Port 空闲"

    # ---- 7. 防火墙入站规则 ----
    Write-Step "配置防火墙入站规则"
    $ruleName = "MediaReview Server $Port"
    $existing = Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue
    if (-not $existing) {
        New-NetFirewallRule -DisplayName $ruleName -Direction Inbound -Action Allow `
            -Protocol TCP -LocalPort $Port -Profile Private, Domain | Out-Null
    }
    Write-Report "防火墙规则: $ruleName"

    # ---- 8. 启动包装脚本 + 开机自启任务 ----
    Write-Step "创建开机自启任务"
    $startCmd = Join-Path $InstallDir "start_server.cmd"
    @"
@echo off
set "MEDIAREVIEW_DATA_ROOT=$DataRoot"
cd /d "%~dp0MediaReviewServer"
start "" "%~dp0MediaReviewServer\Mediaserver.exe"
"@ | Set-Content $startCmd -Encoding ASCII
    $taskName = "MediaReviewServer"
    schtasks /Create /F /TN $taskName /TR "`"$startCmd`"" /SC ONSTART /RU SYSTEM /RL HIGHEST | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "创建计划任务失败" }
    Write-Report "计划任务: $taskName (开机自启)"

    # ---- 9. 启动并健康检查 ----
    Write-Step "启动服务并健康检查"
    schtasks /Run /TN $taskName | Out-Null
    $healthy = $false
    for ($i = 0; $i -lt 30; $i++) {
        Start-Sleep -Seconds 1
        try {
            $h = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/v1/system/health" -TimeoutSec 2
            if ($h.success -and $h.data.status -eq "ok") { $healthy = $true; break }
        } catch { }
    }
    if ($healthy) {
        Write-Host "服务器健康检查通过,版本 $($h.data.version)" -ForegroundColor Green
        Write-Report "health: ok, version $($h.data.version)"
    } else {
        Write-Warning "健康检查未通过,请查看日志: $DataRoot\logs\server.log"
        Write-Report "health: FAILED (见日志)"
    }

    # ---- 10. 部署报告 ----
    $reportFile = Join-Path $DataRoot "deploy_report.txt"
    $Report | Set-Content $reportFile -Encoding UTF8
    Write-Report "部署报告: $reportFile"

    Write-Host ""
    Write-Host "========== 安装完成 ==========" -ForegroundColor Green
    Write-Host "管理后台: http://$(hostname):$Port/admin"
    Write-Host "数据目录: $DataRoot"
    Write-Host "日志目录: $DataRoot\logs"
    Write-Host "请打开管理后台生成配对码,在手机 App 中输入完成配对。"
} catch {
    Write-Host "安装失败: $_" -ForegroundColor Red
    Write-Report "ERROR: $_"
    exit 1
}
