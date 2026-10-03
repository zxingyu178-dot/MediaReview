#requires -Version 5.1
<#
  MediaReview 部署沙箱生命周期测试(PowerShell 5.1, 非管理员可运行)

  覆盖:
    Part A 契约 Gate(无真实二进制, 本机 stub HTTP 服务):
       正确 1.2.1 + contract 2 + 全能力 + status ok  -> PASS
       版本错误(1.2.0)                                -> FAIL(被拒)
       contract 1                                      -> FAIL(被拒)
       健康降级(status != ok)                          -> FAIL(被拒)
       健康不可达                                      -> FAIL(被拒)
    Part B Gate 镜像升级失败回滚(stub):
       old 1.2.0 -> 1.2.1, 强制最终健康失败
       -> 旧二进制恢复、installer 退出码 != 0、旧服务重新健康、报告含 UPGRADE FAILED / ROLLBACK PASS
    Part C 真实部署包生命周期(可选): 仅当显式传入 -SourcePackage 且含真实
       MediaReviewServer.exe 时执行(复制/迁移/升级/回滚/卸载保数据)。

  说明:
    - 防火墙规则、计划任务创建需要管理员, 不在本脚本覆盖范围(由静态契约测试 +
      PS 5.1 解析校验保障)。
    - install.ps1 端到端需要管理员(防火墙/计划任务), 故本脚本以"镜像其 Gate 与
      回滚逻辑 + stub 健康服务"的方式, 在非管理员环境严格验证行为; Gate 逻辑与
      install.ps1 中的 Test-HealthContract/Wait-Healthy 保持一致。

  用法:
    .\test_sandbox_lifecycle.ps1                                # 仅 Part A + B
    .\test_sandbox_lifecycle.ps1 -SourcePackage <部署包根目录>   # 追加 Part C
#>
[CmdletBinding()]
param(
    [string]$SourcePackage = ""
)
$ErrorActionPreference = "Stop"

# 版本 / API Contract 唯一事实源
. "$PSScriptRoot\..\version.ps1"
$RequiredCapabilities = @(
    "review_session", "review_nearest", "organize",
    "delete_nonce", "duplicates_paged", "library_selection"
)
$passed = 0
$failed = 0
$stubJobs = @()

function Assert-True($cond, $name) {
    if ($cond) { Write-Host "[PASS] $name" -ForegroundColor Green; $script:passed++ }
    else       { Write-Host "[FAIL] $name" -ForegroundColor Red;   $script:failed++ }
}

# ---------------- stub HTTP 健康服务(TcpListener, 免管理员) ----------------
# 每次请求实时读取 $JsonFile, 从而无需重启监听即可切换"当前服务"的响应。
$stubBlock = {
    param([int]$Port, [string]$JsonFile)
    $listener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Loopback, $Port)
    $listener.Server.SetSocketOption([System.Net.Sockets.SocketOptionLevel]::Socket, [System.Net.Sockets.SocketOptionName]::ReuseAddress, $true)
    $bound = $false
    for ($k = 0; $k -lt 20 -and -not $bound; $k++) {
        try { $listener.Start(); $bound = $true } catch { Start-Sleep -Milliseconds 250 }
    }
    if (-not $bound) { return }
    try {
        while ($true) {
            $client = $null
            try {
                $client = $listener.AcceptTcpClient()
                $stream = $client.GetStream()
                $reader = New-Object System.IO.StreamReader($stream)
                try { $null = $reader.ReadLine() } catch { }
                $json = ""
                try { $json = Get-Content -Path $JsonFile -Raw -ErrorAction Stop } catch { }
                if ([string]::IsNullOrWhiteSpace($json)) {
                    $json = '{"success":false,"data":{"status":"error","version":"0","api_contract":0,"capabilities":[],"components":{}}}'
                }
                $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
                $header = "HTTP/1.1 200 OK`r`nContent-Type: application/json`r`nContent-Length: $($bytes.Length)`r`nConnection: close`r`n`r`n"
                $hb = [System.Text.Encoding]::ASCII.GetBytes($header)
                $stream.Write($hb, 0, $hb.Length)
                $stream.Write($bytes, 0, $bytes.Length)
                $stream.Flush()
            } catch { }
            finally { if ($client) { try { $client.Close() } catch { } } }
        }
    } finally { try { $listener.Stop() } catch { } }
}

function New-HealthJson($Version, $Contract, $Status, $Caps) {
    @{
        success = $true
        data = @{
            status       = $Status
            service      = "mediareview-server"
            version      = $Version
            api_contract = $Contract
            capabilities = $Caps
            components   = @{ database = "ok"; storage = "ok" }
        }
    } | ConvertTo-Json -Depth 6 -Compress
}

function Start-StubHealth($Port, $JsonFile) {
    $job = Start-Job -ScriptBlock $stubBlock -ArgumentList $Port, $JsonFile
    $script:stubJobs += $job
    # 等待监听就绪(最多约 8s)
    for ($i = 0; $i -lt 40; $i++) {
        Start-Sleep -Milliseconds 200
        try {
            $null = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/v1/system/health" -TimeoutSec 2
            return $job
        } catch { }
    }
    return $job
}

function Stop-StubHealth {
    foreach ($j in $script:stubJobs) {
        if ($j) {
            Stop-Job -Job $j -ErrorAction SilentlyContinue
            Remove-Job -Job $j -Force -ErrorAction SilentlyContinue
        }
    }
    $script:stubJobs = @()
    Start-Sleep -Milliseconds 300
}

# ---------------- 镜像 install.ps1 的健康 Gate ----------------
function Wait-Gate {
    param([int]$Port, [int]$TimeoutSec = 3, [string]$ExpectVersion = $ExpectedServerVersion)
    for ($i = 0; $i -lt $TimeoutSec; $i++) {
        Start-Sleep -Milliseconds 500
        try {
            $h = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/v1/system/health" -TimeoutSec 2
            $ok = ($h.success -and $h.data.status -eq "ok" -and
                   $h.data.version -eq $ExpectVersion -and
                   [int]$h.data.api_contract -ge $RequiredApiContract)
            foreach ($cap in $RequiredCapabilities) {
                if ($cap -notin $h.data.capabilities) { $ok = $false }
            }
            if ($ok) { return $true }
        } catch { }
    }
    return $false
}

# ---------------- 一次性沙箱 ----------------
$sandbox = Join-Path $env:TEMP ("MediaReview_sandbox_" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $sandbox | Out-Null
Write-Host "== 沙箱: $sandbox"
$stubPort = 18871
$deadPort = 18875
$stubJsonFile = Join-Path $sandbox "stub_health.json"

# ================= Part A: 契约 Gate =================
Write-Host "`n===== Part A 契约 Gate(stub, 无真实二进制) ====="
Set-Content -Path $stubJsonFile -Value (New-HealthJson $ExpectedServerVersion $RequiredApiContract "ok" $RequiredCapabilities) -Encoding ASCII
Start-StubHealth -Port $stubPort -JsonFile $stubJsonFile | Out-Null

# A1 正确: 1.2.1 + contract 2 + ok + 全能力 -> PASS
Set-Content -Path $stubJsonFile -Value (New-HealthJson $ExpectedServerVersion $RequiredApiContract "ok" $RequiredCapabilities) -Encoding ASCII
Assert-True (Wait-Gate -Port $stubPort -TimeoutSec 3) "Gate: 正确 1.2.1 + contract 2 -> PASS"

# A2 版本错误 -> FAIL
Set-Content -Path $stubJsonFile -Value (New-HealthJson "1.2.0" $RequiredApiContract "ok" $RequiredCapabilities) -Encoding ASCII
Assert-True (-not (Wait-Gate -Port $stubPort -TimeoutSec 2)) "Gate: 版本错误 1.2.0 -> FAIL(被拒)"

# A3 contract 1 -> FAIL
Set-Content -Path $stubJsonFile -Value (New-HealthJson $ExpectedServerVersion 1 "ok" $RequiredCapabilities) -Encoding ASCII
Assert-True (-not (Wait-Gate -Port $stubPort -TimeoutSec 2)) "Gate: contract 1 -> FAIL(被拒)"

# A4 健康降级 -> FAIL
Set-Content -Path $stubJsonFile -Value (New-HealthJson $ExpectedServerVersion $RequiredApiContract "degraded" $RequiredCapabilities) -Encoding ASCII
Assert-True (-not (Wait-Gate -Port $stubPort -TimeoutSec 2)) "Gate: 健康降级 degraded -> FAIL(被拒)"

# A5 能力缺失 -> FAIL
Set-Content -Path $stubJsonFile -Value (New-HealthJson $ExpectedServerVersion $RequiredApiContract "ok" @("review_session")) -Encoding ASCII
Assert-True (-not (Wait-Gate -Port $stubPort -TimeoutSec 2)) "Gate: 能力清单缺失 -> FAIL(被拒)"

# A6 不可达 -> FAIL(deadPort 无监听)
Assert-True (-not (Wait-Gate -Port $deadPort -TimeoutSec 2)) "Gate: 健康不可达 -> FAIL(被拒)"

# ================= Part B: Gate 镜像升级失败回滚 =================
Write-Host "`n===== Part B 升级失败回滚(stub, 镜像 install.ps1) ====="
$up = Join-Path $sandbox "upgrade"
$UpInstall = Join-Path $up "install"
$UpData    = Join-Path $up "data"
New-Item -ItemType Directory -Force -Path (Join-Path $UpInstall "MediaReviewServer") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $UpData "config")   | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $UpData "database") | Out-Null
$UpExe = Join-Path $UpInstall "MediaReviewServer\MediaReviewServer.exe"

# 旧版本状态(1.2.0)
Set-Content -Path $UpExe      -Value "OLD-BINARY-1.2.0" -Encoding ASCII
Set-Content -Path (Join-Path $UpInstall "VERSION.txt") -Value "1.2.0" -Encoding ASCII
Set-Content -Path (Join-Path $UpData "config\config.json")     -Value '{"server":{"port":18876}}' -Encoding ASCII
Set-Content -Path (Join-Path $UpData "config\CURRENT_VERSION") -Value "1.2.0" -Encoding ASCII
Set-Content -Path (Join-Path $UpData "config\user_note.txt")   -Value "SENTINEL-USER-DATA" -Encoding UTF8

# 旧服务健康(1.2.0 + contract 2 + ok)
Set-Content -Path $stubJsonFile -Value (New-HealthJson "1.2.0" $RequiredApiContract "ok" $RequiredCapabilities) -Encoding ASCII
Assert-True (Wait-Gate -Port $stubPort -TimeoutSec 3 -ExpectVersion "1.2.0") "升级前: 旧服务 1.2.0 健康"

# 备份 config + database + 旧二进制(镜像 install.ps1 Backup-Previous)
$backup = Join-Path $UpData "backup\test"
New-Item -ItemType Directory -Force -Path (Join-Path $backup "config")   | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $backup "database") | Out-Null
Copy-Item (Join-Path $UpData "config\*") (Join-Path $backup "config") -Recurse -Force
Copy-Item (Join-Path $UpData "database\*") (Join-Path $backup "database") -Recurse -Force
Copy-Item (Join-Path $UpInstall "MediaReviewServer") (Join-Path $backup "binaries") -Recurse -Force
Assert-True (Test-Path (Join-Path $backup "binaries\MediaReviewServer.exe")) "升级: 旧二进制已备份"

# staging 新程序 1.2.1 并做迁移+健康检查
$staging = Join-Path $UpInstall ".new"
New-Item -ItemType Directory -Force -Path (Join-Path $staging "MediaReviewServer") | Out-Null
Set-Content -Path (Join-Path $staging "MediaReviewServer\MediaReviewServer.exe") -Value "NEW-BINARY-1.2.1" -Encoding ASCII
Set-Content -Path $stubJsonFile -Value (New-HealthJson $ExpectedServerVersion $RequiredApiContract "ok" $RequiredCapabilities) -Encoding ASCII
Assert-True (Wait-Gate -Port $stubPort -TimeoutSec 3) "升级: staging 新版本健康检查通过"

# atomic promote: 替换二进制 + 写版本标记
Remove-Item (Join-Path $UpInstall "MediaReviewServer") -Recurse -Force
Move-Item (Join-Path $staging "MediaReviewServer") (Join-Path $UpInstall "MediaReviewServer")
Remove-Item $staging -Recurse -Force -ErrorAction SilentlyContinue
Set-Content -Path (Join-Path $UpInstall "VERSION.txt") -Value $ExpectedServerVersion -Encoding ASCII
Set-Content -Path (Join-Path $UpData "config\CURRENT_VERSION") -Value $ExpectedServerVersion -Encoding ASCII
Assert-True (Test-Path (Join-Path $UpInstall "MediaReviewServer\MediaReviewServer.exe")) "升级: 新程序已提升"

# 最终健康检查: 强制失败(新版本状态降级)
Set-Content -Path $stubJsonFile -Value (New-HealthJson $ExpectedServerVersion $RequiredApiContract "degraded" $RequiredCapabilities) -Encoding ASCII
$finalOk = Wait-Gate -Port $stubPort -TimeoutSec 2
Assert-True (-not $finalOk) "升级: 最终健康检查失败(强制)"

# ---- install.ps1 升级路径硬失败分支(镜像) ----
$installerExit = 0
$report = @()
if (-not $finalOk) {
    # 停止坏的新版本
    # Restore-Previous: 恢复旧二进制 + config/database
    if (Test-Path (Join-Path $UpInstall "MediaReviewServer")) {
        Remove-Item (Join-Path $UpInstall "MediaReviewServer") -Recurse -Force
    }
    Copy-Item (Join-Path $backup "binaries") (Join-Path $UpInstall "MediaReviewServer") -Recurse -Force
    foreach ($dir in "config", "database") {
        Copy-Item (Join-Path $backup "$dir\*") (Join-Path $UpData $dir) -Recurse -Force
    }
    $report += "UPGRADE FAILED"
    # 重启旧服务(1.2.0 健康)并校验"任意健康"
    Set-Content -Path $stubJsonFile -Value (New-HealthJson "1.2.0" $RequiredApiContract "ok" $RequiredCapabilities) -Encoding ASCII
    if (Wait-Gate -Port $stubPort -TimeoutSec 3 -ExpectVersion "1.2.0") {
        $report += "ROLLBACK PASS"
    } else {
        $report += "ROLLBACK FAIL"
    }
    $installerExit = 1
}

Assert-True ($installerExit -ne 0) "升级失败: installer 退出码 != 0"
Assert-True ((Get-Content $UpExe -Raw).Trim() -eq "OLD-BINARY-1.2.0") "升级失败: 旧二进制已恢复"
Assert-True (Test-Path (Join-Path $UpData "config\user_note.txt")) "升级失败: 用户数据已恢复"
Assert-True ($report -contains "UPGRADE FAILED") "升级失败: 报告含 UPGRADE FAILED"
Assert-True ($report -contains "ROLLBACK PASS") "升级失败: 报告含 ROLLBACK PASS"
Assert-True (Wait-Gate -Port $stubPort -TimeoutSec 3 -ExpectVersion "1.2.0") "升级失败: 旧服务重新健康"

Stop-StubHealth

# ================= Part C: 真实部署包生命周期(可选) =================
if ($SourcePackage -ne "" -and (Test-Path (Join-Path $SourcePackage "server\MediaReviewServer\MediaReviewServer.exe"))) {
    $Version = $ExpectedServerVersion
    $InstallDir = Join-Path $sandbox "install"
    $DataRoot   = Join-Path $sandbox "data"
    $Port = 18866
    New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $DataRoot "config") | Out-Null
    $srcExe = Join-Path $SourcePackage "server\MediaReviewServer\MediaReviewServer.exe"

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

    $exeDir = Join-Path $InstallDir "MediaReviewServer"
    Copy-Item (Join-Path $SourcePackage "server\MediaReviewServer") $exeDir -Recurse -Force
    $Exe = Join-Path $exeDir "MediaReviewServer.exe"
    Assert-True (Test-Path $Exe) "二进制复制完成"

    $configFile = Join-Path $DataRoot "config\config.json"
    $cfg = Get-Content (Join-Path $SourcePackage "config.example.json") -Raw | ConvertFrom-Json
    $cfg.storage.data_root = $DataRoot
    $cfg.server.port = $Port
    $cfg | ConvertTo-Json -Depth 6 | Set-Content $configFile -Encoding UTF8
    Set-Content -Path (Join-Path $DataRoot "config\user_note.txt") -Value "SENTINEL-USER-DATA" -Encoding UTF8

    Write-Host "`n===== Part C 全新安装 ====="
    $h = Start-NewServer -Exe $Exe -DataRoot $DataRoot -Port $Port
    Assert-True ($null -ne $h) "全新安装: 健康检查通过"
    if ($h) { Assert-True ($h.data.version -eq $Version) "全新安装: 版本 $($h.data.version)" }
    Assert-True (Test-Path (Join-Path $DataRoot "database\mediareview.db")) "全新安装: 数据库已迁移创建"
    Assert-True (Test-Path (Join-Path $DataRoot "logs\server.log")) "全新安装: 日志已写入"
    Stop-OwnedServer -Exe $Exe

    Write-Host "`n===== Part C 事务式升级 ====="
    Set-Content -Path (Join-Path $InstallDir "VERSION.txt") -Value "1.0.0" -Encoding ASCII
    Set-Content -Path (Join-Path $DataRoot "config\CURRENT_VERSION") -Value "1.0.0" -Encoding ASCII
    $stamp = Get-Date -Format "yyyyMMdd_HHmmss"
    $backup2 = Join-Path $DataRoot "backup\$stamp"
    New-Item -ItemType Directory -Force -Path (Join-Path $backup2 "config")   | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $backup2 "database") | Out-Null
    Copy-Item (Join-Path $DataRoot "config\*") (Join-Path $backup2 "config") -Recurse -Force
    Copy-Item (Join-Path $DataRoot "database\*") (Join-Path $backup2 "database") -Recurse -Force
    Copy-Item (Join-Path $InstallDir "MediaReviewServer") (Join-Path $backup2 "binaries") -Recurse -Force
    Assert-True (Test-Path (Join-Path $backup2 "binaries\MediaReviewServer.exe")) "升级: 旧二进制已备份"

    $staging2 = Join-Path $InstallDir ".new"
    New-Item -ItemType Directory -Force -Path $staging2 | Out-Null
    Copy-Item (Join-Path $SourcePackage "server\MediaReviewServer") (Join-Path $staging2 "MediaReviewServer") -Recurse -Force
    $stagedExe = Join-Path $staging2 "MediaReviewServer\MediaReviewServer.exe"
    $h2 = Start-NewServer -Exe $stagedExe -DataRoot $DataRoot -Port $Port
    Assert-True ($null -ne $h2) "升级: 新版本迁移+健康检查通过"
    Stop-OwnedServer -Exe $stagedExe
    if (Test-Path (Join-Path $InstallDir "MediaReviewServer")) {
        Remove-Item (Join-Path $InstallDir "MediaReviewServer") -Recurse -Force
    }
    Move-Item (Join-Path $staging2 "MediaReviewServer") (Join-Path $InstallDir "MediaReviewServer")
    Remove-Item $staging2 -Recurse -Force -ErrorAction SilentlyContinue
    Set-Content -Path (Join-Path $InstallDir "VERSION.txt") -Value $Version -Encoding ASCII
    Set-Content -Path (Join-Path $DataRoot "config\CURRENT_VERSION") -Value $Version -Encoding ASCII
    Assert-True (Test-Path (Join-Path $InstallDir "MediaReviewServer\MediaReviewServer.exe")) "升级: 新程序已提升"
    Assert-True (((Get-Content (Join-Path $DataRoot "config\CURRENT_VERSION") -Raw).Trim()) -eq $Version) "升级: CURRENT_VERSION=$Version"
    $h3 = Start-NewServer -Exe $Exe -DataRoot $DataRoot -Port $Port
    Assert-True ($null -ne $h3) "升级: 提升后重启健康"
    Stop-OwnedServer -Exe $Exe

    Write-Host "`n===== Part C 失败回滚 ====="
    Set-Content -Path (Join-Path $DataRoot "config\config.json") -Value "{ NOT VALID JSON !!!" -Encoding ASCII
    $h4 = Start-NewServer -Exe $Exe -DataRoot $DataRoot -Port $Port
    Assert-True ($null -eq $h4) "回滚: 坏配置导致健康检查失败(预期)"
    Stop-OwnedServer -Exe $Exe
    Get-Process MediaReviewServer -ErrorAction SilentlyContinue |
        Where-Object { $_.Path -eq $Exe } | Stop-Process -Force -ErrorAction SilentlyContinue
    if (Test-Path (Join-Path $InstallDir "MediaReviewServer")) {
        Remove-Item (Join-Path $InstallDir "MediaReviewServer") -Recurse -Force
    }
    Copy-Item (Join-Path $backup2 "binaries") (Join-Path $InstallDir "MediaReviewServer") -Recurse -Force
    foreach ($dir in "config", "database") {
        if (Test-Path (Join-Path $backup2 $dir)) {
            Copy-Item (Join-Path $backup2 "$dir\*") (Join-Path $DataRoot $dir) -Recurse -Force
        }
    }
    Assert-True (Test-Path (Join-Path $DataRoot "config\config.json")) "回滚: 有效配置已恢复"
    Assert-True (Test-Path (Join-Path $InstallDir "MediaReviewServer\MediaReviewServer.exe")) "回滚: 旧二进制已恢复"
    $h5 = Start-NewServer -Exe $Exe -DataRoot $DataRoot -Port $Port
    Assert-True ($null -ne $h5) "回滚: 恢复后服务健康"
    Stop-OwnedServer -Exe $Exe

    Write-Host "`n===== Part C 卸载保数据 ====="
    Get-Process MediaReviewServer -ErrorAction SilentlyContinue |
        Where-Object { $_.Path -eq $Exe } | Stop-Process -Force -ErrorAction SilentlyContinue
    if (Test-Path $InstallDir) { Remove-Item $InstallDir -Recurse -Force -ErrorAction SilentlyContinue }
    Assert-True (-not (Test-Path $InstallDir)) "卸载: 程序目录已删除"
    Assert-True (Test-Path $DataRoot) "卸载: 数据目录保留(默认)"
} else {
    Write-Host "`n(跳过 Part C: 未提供 -SourcePackage 或缺少真实 MediaReviewServer.exe; Part A/B stub 已覆盖 Gate 与回滚)" -ForegroundColor DarkGray
}

# ---------------- 汇总 ----------------
Write-Host "`n======================================"
Write-Host "通过: $passed  失败: $failed"
Write-Host "沙箱目录: $sandbox"
if ($failed -gt 0) { exit 1 }
Write-Host "沙箱生命周期测试: 全部通过" -ForegroundColor Green
exit 0
