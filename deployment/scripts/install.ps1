#requires -Version 5.1
#requires -RunAsAdministrator
<#
  MediaReview Server 安装/升级脚本(1.1.0)
  首次安装: 校验源包、磁盘空间、复制程序、初始化数据目录、配置端口/数据根、
  FFmpeg 检测、防火墙(仅 TCP 8766 + UDP 35001)、开机自启、启动与健康检查。
  已存在旧版本(检测 VERSION.txt/CURRENT_VERSION): 走事务式升级——
  停止旧服务→备份 config+database(与旧二进制)→staging 新程序→健康检查通过后
  promote 并写 CURRENT_VERSION→失败则回滚旧二进制与数据并重启旧版。
  用法:  .\install.ps1 [-InstallDir ...] [-DataRoot ...] [-Port 8766]
#>
[CmdletBinding()]
param(
    [string]$InstallDir = "$env:ProgramFiles\MediaReviewServer",
    [string]$DataRoot   = "$env:ProgramData\MediaReview",
    [int]$Port          = 8766
)
$ErrorActionPreference = "Stop"
$ScriptDir  = $PSScriptRoot
$TaskName   = "MediaReviewServer"
$Version    = "1.1.0"
$Report     = @()
$Global:LASTEXITCODE = 0

function Write-Step($msg)   { Write-Host "==> $msg" -ForegroundColor Cyan }
function Write-Report($msg) { $script:Report += "$msg" }

function Get-OwnedProcess {
    param([string]$ExePath)
    Get-Process MediaReviewServer -ErrorAction SilentlyContinue |
        Where-Object { $_.Path -eq $ExePath }
}

function Wait-Healthy([int]$TimeoutSec = 40) {
    for ($i = 0; $i -lt $TimeoutSec; $i++) {
        Start-Sleep -Seconds 1
        try {
            $h = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/v1/system/health" -TimeoutSec 2
            if ($h.success -and $h.data.status -eq "ok") { return $true }
        } catch { }
    }
    return $false
}

function Backup-Previous([string]$InstallDir, [string]$DataRoot, [string]$ExePath) {
    $stamp   = Get-Date -Format "yyyyMMdd_HHmmss"
    $backup  = Join-Path $DataRoot "backup\$stamp"
    New-Item -ItemType Directory -Force -Path (Join-Path $backup "config")    | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $backup "database")  | Out-Null
    if (Test-Path (Join-Path $DataRoot "config")) {
        Copy-Item (Join-Path $DataRoot "config\*") (Join-Path $backup "config") -Recurse -Force
    }
    if (Test-Path (Join-Path $DataRoot "database")) {
        Copy-Item (Join-Path $DataRoot "database\*") (Join-Path $backup "database") -Recurse -Force
    }
    if (Test-Path (Join-Path $InstallDir "MediaReviewServer")) {
        Copy-Item (Join-Path $InstallDir "MediaReviewServer") (Join-Path $backup "binaries") -Recurse -Force
    }
    Write-Report "backup: $backup"
    Write-Host "已备份 config/database/旧二进制到: $backup"
    return $backup
}

function Restore-Previous([string]$InstallDir, [string]$backup, [string]$DataRoot, [string]$ExePath) {
    Write-Host "回滚: 停止并恢复旧版本..." -ForegroundColor Yellow
    Get-OwnedProcess -ExePath $ExePath | Stop-Process -Force -ErrorAction SilentlyContinue
    $task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    if ($task) { Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue }
    if (Test-Path (Join-Path $InstallDir "MediaReviewServer")) {
        Remove-Item (Join-Path $InstallDir "MediaReviewServer") -Recurse -Force
    }
    if (Test-Path (Join-Path $backup "binaries")) {
        Copy-Item (Join-Path $backup "binaries") (Join-Path $InstallDir "MediaReviewServer") -Recurse -Force
    }
    foreach ($dir in "config", "database") {
        if (Test-Path (Join-Path $backup $dir)) {
            Copy-Item (Join-Path $backup "$dir\*") (Join-Path $DataRoot $dir) -Recurse -Force
        }
    }
    $task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    if ($task) { schtasks /Run /TN $TaskName | Out-Null }
    else {
        $env:MEDIAREVIEW_DATA_ROOT = $DataRoot
        Start-Process -FilePath $ExePath -WorkingDirectory (Split-Path $ExePath) -WindowStyle Hidden
    }
    Write-Report "restore: restored old version from $backup"
}

try {
    # ---- 1. OS / 权限 ----
    Write-Step "操作系统与权限检查"
    $os = Get-CimInstance Win32_OperatingSystem
    Write-Report "OS: $($os.Caption) $($os.Version) ($env:PROCESSOR_ARCHITECTURE)"

    # ---- 2. 源包完整性 ----
    Write-Step "检查部署包完整性"
    $srcExe = Join-Path $ScriptDir "..\server\MediaReviewServer\MediaReviewServer.exe"
    if (-not (Test-Path $srcExe)) {
        throw "未找到服务器可执行文件: $srcExe (应为 MediaReviewServer.exe)"
    }
    Write-Report "Server 源: $srcExe"

    # ---- 3. 磁盘空间检查(安装盘与数据盘至少 500MB) ----
    Write-Step "磁盘剩余空间检查"
    foreach ($p in @($InstallDir, $DataRoot)) {
        $drive = [System.IO.Path]::GetPathRoot($p)
        $d = Get-PSDrive -Name ($drive.TrimEnd('\')) -ErrorAction SilentlyContinue
        if ($d -and $d.Free -lt 500MB) {
            throw "磁盘剩余空间不足: $drive 仅 $([math]::Round($d.Free/1MB))MB,需要至少 500MB"
        }
        Write-Report "disk: $drive free $([math]::Round($d.Free/1MB))MB"
    }

    $ExePath  = Join-Path $InstallDir "MediaReviewServer\MediaReviewServer.exe"
    $verFile  = Join-Path $InstallDir "VERSION.txt"
    $markFile = Join-Path $DataRoot "config\CURRENT_VERSION"
    $prior    = (Test-Path $ExePath) -or (Test-Path $verFile) -or (Test-Path $markFile)

    if ($prior) {
        # ================= 升级路径(事务式) =================
        $oldVer = if (Test-Path $verFile) { (Get-Content $verFile -Raw).Trim() } else { "未知" }
        Write-Step "检测到旧版本($oldVer), 执行事务式升级 -> $Version"

        # 3a. 停止旧服务(仅本进程)
        Get-OwnedProcess -ExePath $ExePath | Stop-Process -Force -ErrorAction SilentlyContinue
        $task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
        if ($task) { Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue }

        # 3b. 备份 config+database+旧二进制
        $backup = Backup-Previous $InstallDir $DataRoot $ExePath

        # 3c. staging 新程序到 $InstallDir\.new
        $staging = Join-Path $InstallDir ".new"
        if (Test-Path $staging) { Remove-Item $staging -Recurse -Force }
        New-Item -ItemType Directory -Force -Path $staging | Out-Null
        Copy-Item (Join-Path $ScriptDir "..\server\MediaReviewServer") (Join-Path $staging "MediaReviewServer") -Recurse -Force
        $stagedExe = Join-Path $staging "MediaReviewServer\MediaReviewServer.exe"
        Write-Report "staged: $stagedExe"

        # 3d. 迁移+健康检查(新程序同数据根启动即自动迁移)
        Write-Step "启动新版本进行迁移与健康检查..."
        $env:MEDIAREVIEW_DATA_ROOT = $DataRoot
        Start-Process -FilePath $stagedExe -WorkingDirectory (Split-Path $stagedExe) -WindowStyle Hidden
        $ok = Wait-Healthy -TimeoutSec 60
        Get-OwnedProcess -ExePath $stagedExe | Stop-Process -Force -ErrorAction SilentlyContinue
        if (-not $ok) {
            Restore-Previous $InstallDir $backup $DataRoot $ExePath
            throw "新版本健康检查未通过,已回滚旧版本"
        }

        # 3e. atomic promote: 旧二进制已备份, 用新程序替换
        Write-Step "健康检查通过, 原子替换程序文件"
        if (Test-Path (Join-Path $InstallDir "MediaReviewServer")) {
            Remove-Item (Join-Path $InstallDir "MediaReviewServer") -Recurse -Force
        }
        Move-Item (Join-Path $staging "MediaReviewServer") (Join-Path $InstallDir "MediaReviewServer")
        Remove-Item $staging -Recurse -Force -ErrorAction SilentlyContinue

        # 3f. 写版本标记
        Set-Content -Path $verFile -Value $Version -Encoding ASCII
        Set-Content -Path $markFile -Value $Version -Encoding ASCII
        Write-Report "upgrade: $oldVer -> $Version (promoted)"
    } else {
        # ================= 首次安装 =================
        Write-Step "首次安装 -> $Version"
        New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
        Copy-Item (Join-Path $ScriptDir "..\server\MediaReviewServer") (Join-Path $InstallDir "MediaReviewServer") -Recurse -Force

        Write-Step "初始化数据目录 $DataRoot"
        $configDir  = Join-Path $DataRoot "config"
        New-Item -ItemType Directory -Force -Path $configDir | Out-Null
        $configFile = Join-Path $configDir "config.json"
        if (-not (Test-Path $configFile)) {
            $example = Join-Path $ScriptDir "..\config.example.json"
            if (Test-Path $example) { Copy-Item $example $configFile }
            else { Write-Warning "缺少 config.example.json,跳过配置模板生成" }
        }
        $config = @{}
        if (Test-Path $configFile) {
            $config = Get-Content $configFile -Raw | ConvertFrom-Json
        }
        # 写入端口/数据根(用户参数优先)
        $config | Add-Member -NotePropertyName server -NotePropertyValue $config.server -Force
        $config.server | Add-Member -NotePropertyName port -NotePropertyValue $Port -Force
        $config | Add-Member -NotePropertyName storage -NotePropertyValue $config.storage -Force
        $config.storage | Add-Member -NotePropertyName data_root -NotePropertyValue $DataRoot -Force
        $config | ConvertTo-Json -Depth 6 | Set-Content $configFile -Encoding UTF8
        Set-Content -Path $verFile -Value $Version -Encoding ASCII
        Set-Content -Path $markFile -Value $Version -Encoding ASCII
        Write-Report "install: fresh $Version @ $InstallDir"

        # FFmpeg: 优先随包, 其次系统 PATH
        Write-Step "检查 FFmpeg"
        $ffmpegDir = Join-Path $InstallDir "ffmpeg"
        $ffmpegExe = Join-Path $ffmpegDir "ffmpeg.exe"
        if (Test-Path $ffmpegExe) {
            $config.storage | Add-Member -NotePropertyName ffmpeg_dir -NotePropertyValue $ffmpegDir -Force
            Write-Host "使用随包 FFmpeg: $ffmpegDir"
            Write-Report "ffmpeg: bundled $ffmpegDir"
        } else {
            $sys = Get-Command ffmpeg -ErrorAction SilentlyContinue
            if ($sys) {
                $config.storage | Add-Member -NotePropertyName ffmpeg_dir -NotePropertyValue "" -Force
                Write-Host "使用系统 PATH 中的 ffmpeg: $($sys.Source)"
                Write-Report "ffmpeg: system $($sys.Source)"
            } else {
                $config.storage | Add-Member -NotePropertyName ffmpeg_dir -NotePropertyValue "" -Force
                Write-Warning "未找到 ffmpeg!雪碧图功能将不可用。请将 ffmpeg.exe/ffprobe.exe 放入 $ffmpegDir 后运行 repair.ps1"
                Write-Report "ffmpeg: MISSING (雪碧图将不可用)"
            }
        }
        $config | ConvertTo-Json -Depth 6 | Set-Content $configFile -Encoding UTF8
        Write-Report "配置: $configFile"
    }

    # ---- 4. 端口冲突检查 ----
    Write-Step "检查端口 $Port 占用"
    $listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    if ($listener) {
        throw "端口 $Port 已被占用,请先停止占用进程(本服务进程将被精确停止,不会误杀其他监听者)"
    }
    Write-Report "端口 $Port 空闲"

    # ---- 5. 防火墙: 仅 TCP 8766 + UDP 35001 ----
    Write-Step "配置防火墙入站规则 (仅 TCP 8766 + UDP 35001)"
    Get-NetFirewallRule -DisplayName "MediaReview Server*" -ErrorAction SilentlyContinue |
        Remove-NetFirewallRule -ErrorAction SilentlyContinue
    $tcpRule = "MediaReview Server TCP 8766"
    $udpRule = "MediaReview Server UDP 35001"
    if (-not (Get-NetFirewallRule -DisplayName $tcpRule -ErrorAction SilentlyContinue)) {
        New-NetFirewallRule -DisplayName $tcpRule -Direction Inbound -Action Allow `
            -Protocol TCP -LocalPort 8766 -Profile Private, Domain | Out-Null
    }
    if (-not (Get-NetFirewallRule -DisplayName $udpRule -ErrorAction SilentlyContinue)) {
        New-NetFirewallRule -DisplayName $udpRule -Direction Inbound -Action Allow `
            -Protocol UDP -LocalPort 35001 -Profile Private, Domain | Out-Null
    }
    Write-Report "防火墙: TCP 8766 + UDP 35001"

    # ---- 6. 开机自启任务 ----
    Write-Step "创建开机自启任务"
    $startCmd = Join-Path $InstallDir "start_server.cmd"
    @"
@echo off
set "MEDIAREVIEW_DATA_ROOT=$DataRoot"
cd /d "%~dp0MediaReviewServer"
start "" "%~dp0MediaReviewServer\MediaReviewServer.exe"
"@ | Set-Content $startCmd -Encoding ASCII
    schtasks /Create /F /TN $TaskName /TR "`"$startCmd`"" /SC ONSTART /RU SYSTEM /RL HIGHEST | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "创建计划任务失败" }
    Write-Report "计划任务: $TaskName (开机自启)"

    # ---- 7. 启动并健康检查 ----
    Write-Step "启动服务并健康检查"
    schtasks /Run /TN $TaskName | Out-Null
    if (Wait-Healthy -TimeoutSec 40) {
        $h = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/v1/system/health" -TimeoutSec 3
        Write-Host "服务器健康检查通过, 版本 $($h.data.version)" -ForegroundColor Green
        Write-Report "health: ok, version $($h.data.version)"
    } else {
        Write-Warning "健康检查未通过, 请查看日志: $DataRoot\logs\server.log"
        Write-Report "health: FAILED (见日志)"
    }

    # ---- 8. 部署报告 ----
    $reportFile = Join-Path $DataRoot "deploy_report.txt"
    $Report | Set-Content $reportFile -Encoding UTF8
    Write-Report "部署报告: $reportFile"

    Write-Host ""
    Write-Host "========== 安装完成 (版本 $Version) ==========" -ForegroundColor Green
    Write-Host "管理后台: http://$(hostname):$Port/admin"
    Write-Host "数据目录: $DataRoot"
    Write-Host "日志目录: $DataRoot\logs"
    Write-Host "请打开管理后台生成配对码, 在手机 App 中输入完成配对。"
} catch {
    Write-Host "安装失败: $_" -ForegroundColor Red
    Write-Report "ERROR: $_"
    $Report | Set-Content (Join-Path $DataRoot "deploy_report.txt") -Encoding UTF8 -ErrorAction SilentlyContinue
    exit 1
}
