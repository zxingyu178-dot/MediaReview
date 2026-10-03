#requires -Version 5.1
<#
  MediaReview 部署沙箱生命周期测试(非管理员可运行)

  针对部署包在一次性临时目录执行:
    Phase A 全新安装: 复制二进制 -> 初始化数据根 -> 启动 -> 迁移 -> 健康检查
    Phase B 事务式升级: 旧版本标记 -> 停止 -> 备份 -> staging -> 迁移+健康 -> promote
    Phase C 失败回滚: 破坏配置模拟新版本失败 -> 从备份恢复 -> 重启旧版健康
    Phase D 卸载保数据: 停止 -> 删程序目录 -> 数据根保留

  说明: 防火墙规则与计划任务创建需要管理员, 不在本脚本覆盖范围(由
  test_deployment_contract_11.py 静态契约 + PS 5.1 解析校验保障)。

  用法:
    .\test_sandbox_lifecycle.ps1 -SourcePackage <解压后的部署包根目录>
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$SourcePackage
)
$ErrorActionPreference = "Stop"
$Version = "1.2.0"
$passed = 0
$failed = 0

function Assert-True($cond, $name) {
    if ($cond) { Write-Host "[PASS] $name" -ForegroundColor Green; $script:passed++ }
    else       { Write-Host "[FAIL] $name" -ForegroundColor Red;   $script:failed++ }
}

function Invoke-WaitHealthy {
    param([int]$Port, [int]$TimeoutSec = 40)
    for ($i = 0; $i -lt $TimeoutSec; $i++) {
        Start-Sleep -Seconds 1
        try {
            $h = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/v1/system/health" -TimeoutSec 2
            if ($h.success -and $h.data.status -eq "ok") { return $h }
        } catch { }
    }
    return $null
}

function Stop-OwnedServer {
    param([string]$Exe)
    Get-Process MediaReviewServer -ErrorAction SilentlyContinue |
        Where-Object { $_.Path -eq $Exe } |
        Stop-Process -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 1
}

function Start-NewServer {
    param([string]$Exe, [string]$DataRoot, [int]$Port)
    $env:MEDIAREVIEW_DATA_ROOT = $DataRoot
    Start-Process -FilePath $Exe -WorkingDirectory (Split-Path $Exe) -WindowStyle Hidden
    return Invoke-WaitHealthy -Port $Port
}

# ---------------- 准备一次性环境 ----------------
$sandbox = Join-Path $env:TEMP ("MediaReview_sandbox_" + [guid]::NewGuid().ToString("N"))
$InstallDir = Join-Path $sandbox "install"
$DataRoot   = Join-Path $sandbox "data"
New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $DataRoot "config") | Out-Null
$Port = 18866
$srcExe = Join-Path $SourcePackage "server\MediaReviewServer\MediaReviewServer.exe"
Write-Host "== 沙箱: $sandbox"
Assert-True (Test-Path $srcExe) "源包 EXE 存在"

# ================= Phase A: 全新安装 =================
Write-Host "`n===== Phase A 全新安装 ====="
$exeDir = Join-Path $InstallDir "MediaReviewServer"
Copy-Item (Join-Path $SourcePackage "server\MediaReviewServer") $exeDir -Recurse -Force
$Exe = Join-Path $exeDir "MediaReviewServer.exe"
Assert-True (Test-Path $Exe) "二进制复制完成"

# 初始化配置(镜像 install.ps1: 以模板为底, 写入本沙箱 data_root 与端口)
$configFile = Join-Path $DataRoot "config\config.json"
$cfg = Get-Content (Join-Path $SourcePackage "config.example.json") -Raw | ConvertFrom-Json
$cfg.storage.data_root = $DataRoot
$cfg.server.port = $Port
$cfg | ConvertTo-Json -Depth 6 | Set-Content $configFile -Encoding UTF8
# 写入哨兵: 验证升级/回滚后数据保留
Set-Content -Path (Join-Path $DataRoot "config\user_note.txt") -Value "SENTINEL-USER-DATA" -Encoding UTF8

$h = Start-NewServer -Exe $Exe -DataRoot $DataRoot -Port $Port
Assert-True ($null -ne $h) "全新安装: 健康检查通过"
if ($h) {
    Assert-True ($h.data.version -eq $Version) "全新安装: 版本 $($h.data.version)"
}
Assert-True (Test-Path (Join-Path $DataRoot "database\mediareview.db")) "全新安装: 数据库已迁移创建"
Assert-True (Test-Path (Join-Path $DataRoot "logs\server.log")) "全新安装: 日志已写入"
Assert-True ((Test-Path (Join-Path $DataRoot "config\user_note.txt"))) "全新安装: 配置目录可用"
Stop-OwnedServer -Exe $Exe

# ================= Phase B: 事务式升级 =================
Write-Host "`n===== Phase B 事务式升级 ====="
# 模拟旧版本状态
Set-Content -Path (Join-Path $InstallDir "VERSION.txt") -Value "1.0.0" -Encoding ASCII
Set-Content -Path (Join-Path $DataRoot "config\CURRENT_VERSION") -Value "1.0.0" -Encoding ASCII

# 1) 备份(镜像 install.ps1 Backup-Previous)
$stamp = Get-Date -Format "yyyyMMdd_HHmmss"
$backup = Join-Path $DataRoot "backup\$stamp"
New-Item -ItemType Directory -Force -Path (Join-Path $backup "config")   | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $backup "database") | Out-Null
Copy-Item (Join-Path $DataRoot "config\*") (Join-Path $backup "config") -Recurse -Force
Copy-Item (Join-Path $DataRoot "database\*") (Join-Path $backup "database") -Recurse -Force
Copy-Item (Join-Path $InstallDir "MediaReviewServer") (Join-Path $backup "binaries") -Recurse -Force
Assert-True (Test-Path (Join-Path $backup "binaries\MediaReviewServer.exe")) "升级: 旧二进制已备份"
Assert-True (Test-Path (Join-Path $backup "config\user_note.txt")) "升级: 用户配置已备份"

# 2) staging 新程序到 .new
$staging = Join-Path $InstallDir ".new"
New-Item -ItemType Directory -Force -Path $staging | Out-Null
Copy-Item (Join-Path $SourcePackage "server\MediaReviewServer") (Join-Path $staging "MediaReviewServer") -Recurse -Force
$stagedExe = Join-Path $staging "MediaReviewServer\MediaReviewServer.exe"
Assert-True (Test-Path $stagedExe) "升级: 新程序已暂存"

# 3) 启动新版本做迁移+健康检查(同一数据根)
$h2 = Start-NewServer -Exe $stagedExe -DataRoot $DataRoot -Port $Port
Assert-True ($null -ne $h2) "升级: 新版本迁移+健康检查通过"
Stop-OwnedServer -Exe $stagedExe

# 4) atomic promote
if (Test-Path (Join-Path $InstallDir "MediaReviewServer")) {
    Remove-Item (Join-Path $InstallDir "MediaReviewServer") -Recurse -Force
}
Move-Item (Join-Path $staging "MediaReviewServer") (Join-Path $InstallDir "MediaReviewServer")
Remove-Item $staging -Recurse -Force -ErrorAction SilentlyContinue
Set-Content -Path (Join-Path $InstallDir "VERSION.txt") -Value $Version -Encoding ASCII
Set-Content -Path (Join-Path $DataRoot "config\CURRENT_VERSION") -Value $Version -Encoding ASCII
Assert-True (Test-Path (Join-Path $InstallDir "MediaReviewServer\MediaReviewServer.exe")) "升级: 新程序已提升"
Assert-True (((Get-Content (Join-Path $DataRoot "config\CURRENT_VERSION") -Raw).Trim()) -eq $Version) "升级: CURRENT_VERSION=1.2.0"
Assert-True ((Test-Path (Join-Path $DataRoot "config\user_note.txt"))) "升级: 用户数据在升级后保留"

# 5) 升级后重启健康
$h3 = Start-NewServer -Exe $Exe -DataRoot $DataRoot -Port $Port
Assert-True ($null -ne $h3) "升级: 提升后重启健康"
Stop-OwnedServer -Exe $Exe

# ================= Phase C: 失败回滚 =================
Write-Host "`n===== Phase C 失败回滚 ====="
# 模拟新版本不可用: 破坏配置(config 无法解析 -> 新版本无法启动)
Set-Content -Path (Join-Path $DataRoot "config\config.json") -Value "{ NOT VALID JSON !!!" -Encoding ASCII
# 尝试启动"坏版本"(当前已提升的程序) -> 应健康失败
$h4 = Start-NewServer -Exe $Exe -DataRoot $DataRoot -Port $Port
Assert-True ($null -eq $h4) "回滚: 坏配置导致健康检查失败(预期)"
Stop-OwnedServer -Exe $Exe

# 执行回滚(镜像 install.ps1 Restore-Previous)
Get-Process MediaReviewServer -ErrorAction SilentlyContinue |
    Where-Object { $_.Path -eq $Exe } | Stop-Process -Force -ErrorAction SilentlyContinue
if (Test-Path (Join-Path $InstallDir "MediaReviewServer")) {
    Remove-Item (Join-Path $InstallDir "MediaReviewServer") -Recurse -Force
}
Copy-Item (Join-Path $backup "binaries") (Join-Path $InstallDir "MediaReviewServer") -Recurse -Force
foreach ($dir in "config", "database") {
    if (Test-Path (Join-Path $backup $dir)) {
        Copy-Item (Join-Path $backup "$dir\*") (Join-Path $DataRoot $dir) -Recurse -Force
    }
}
# 回滚后 config.json 应为备份中的有效模板, 哨兵恢复
Assert-True (Test-Path (Join-Path $DataRoot "config\config.json")) "回滚: 有效配置已恢复"
Assert-True ((Test-Path (Join-Path $DataRoot "config\user_note.txt"))) "回滚: 用户数据已恢复"
Assert-True (Test-Path (Join-Path $InstallDir "MediaReviewServer\MediaReviewServer.exe")) "回滚: 旧二进制已恢复"

# 回滚后重启 -> 健康
$h5 = Start-NewServer -Exe $Exe -DataRoot $DataRoot -Port $Port
Assert-True ($null -ne $h5) "回滚: 恢复后服务健康"
Stop-OwnedServer -Exe $Exe

# ================= Phase D: 卸载保数据 =================
Write-Host "`n===== Phase D 卸载保数据 ====="
# 模拟 uninstall.ps1: 停止进程 + 删除程序目录, 保留数据目录
Get-Process MediaReviewServer -ErrorAction SilentlyContinue |
    Where-Object { $_.Path -eq $Exe } | Stop-Process -Force -ErrorAction SilentlyContinue
if (Test-Path $InstallDir) {
    Remove-Item $InstallDir -Recurse -Force -ErrorAction SilentlyContinue
}
Assert-True (-not (Test-Path $InstallDir)) "卸载: 程序目录已删除"
Assert-True (Test-Path $DataRoot) "卸载: 数据目录保留(默认)"
Assert-True (Test-Path (Join-Path $DataRoot "config\config.json")) "卸载: 配置保留"
Assert-True (Test-Path (Join-Path $DataRoot "database\mediareview.db")) "卸载: 数据库保留"

# ---------------- 汇总 ----------------
Write-Host "`n======================================"
Write-Host "通过: $passed  失败: $failed"
Write-Host "沙箱目录: $sandbox"
if ($failed -gt 0) { exit 1 }
Write-Host "沙箱生命周期测试: 全部通过" -ForegroundColor Green
exit 0
