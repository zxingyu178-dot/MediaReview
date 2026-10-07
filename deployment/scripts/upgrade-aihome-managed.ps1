#requires -Version 5.1
<#
既有 AIHome.MediaReview 受控升级事务。默认 Dry Run / WhatIf 零写入。
Stage8E.1 只开放隔离 fixture 执行；生产 Execute 在任何写入/服务操作前拒绝。
未来生产适配器必须接入 ControlHub 官方 coordinator（批准绑定及审计），不能
把底层 ControlHubServiceHelper.exe 或 SCM 命令当作独立生命周期入口。
退出结果 Status 区分升级成功、失败恢复成功、恢复失败和生产封锁。
不输出配置内容、数据库内容或原始异常文本。
#>
[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param(
    [string]$InstallDir = "$env:ProgramFiles\MediaReviewServer",
    [string]$DataRoot = "$env:ProgramData\MediaReview",
    [string]$SourcePackage = '',
    [switch]$Execute,
    [string]$FixtureRoot = '',
    [hashtable]$FixtureAdapter,
    [ValidateSet('', 'copyfail', 'backupfail', 'switchfail')][string]$FixtureFault = '',
    [ValidateRange(1, 120)][int]$WaitSeconds = 30
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\..\version.ps1"
$ServiceName = 'AIHome.MediaReview'
$Port = 8766
$healthUrl = 'http://127.0.0.1:8766/api/v1/system/health'

function Get-CanonicalPath([string]$Path) {
    return [IO.Path]::GetFullPath($Path).TrimEnd('\', '/')
}
function Assert-NoReparse([string]$Path) {
    $cursor = $Path
    while ($cursor) {
        if (Test-Path -LiteralPath $cursor) {
            if ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Reparse path rejected' }
        }
        $parent = Split-Path -Parent $cursor
        if ($parent -eq $cursor) { break }; $cursor = $parent
    }
    if (Test-Path -LiteralPath $Path -PathType Container) {
        foreach ($item in Get-ChildItem -LiteralPath $Path -Force -Recurse) {
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Reparse child rejected' }
        }
    }
}
function Invoke-Adapter([string]$Action, $Argument) {
    if (-not $FixtureAdapter.ContainsKey($Action) -or $FixtureAdapter[$Action] -isnot [scriptblock]) { throw 'Adapter action missing' }
    return & $FixtureAdapter[$Action] $Argument
}
function Get-FileInventory([string]$Path, [switch]$ExcludeManifest) {
    if (-not (Test-Path -LiteralPath $Path)) { return }
    $directory = Test-Path -LiteralPath $Path -PathType Container
    $files = @(if ($directory) { Get-ChildItem -LiteralPath $Path -File -Recurse -Force } else { Get-Item -LiteralPath $Path -Force })
    foreach ($file in $files) {
        $relative = ''; if ($directory) { $relative = $file.FullName.Substring($Path.Length + 1) }
        if ($ExcludeManifest -and $relative -eq 'backup_manifest.json') { continue }
        @{ path = $relative; bytes = $file.Length; sha256 = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash }
    }
}
function Assert-Inventory([string]$Path, $Expected, [switch]$ExcludeManifest) {
    $actual = @(Get-FileInventory $Path -ExcludeManifest:$ExcludeManifest)
    $expectedSet = @($Expected | ForEach-Object { "$($_.path)|$($_.bytes)|$($_.sha256)" } | Sort-Object) -join "`n"
    $actualSet = @($actual | ForEach-Object { "$($_.path)|$($_.bytes)|$($_.sha256)" } | Sort-Object) -join "`n"
    if ($expectedSet -cne $actualSet) { throw 'File set/hash mismatch' }
}
function Wait-ServiceState([string]$State) {
    $deadline = [DateTime]::UtcNow.AddSeconds($WaitSeconds)
    do {
        if ((Invoke-Adapter 'Status' $ServiceName) -eq $State) { return }
        Start-Sleep -Milliseconds 100
    } while ([DateTime]::UtcNow -lt $deadline)
    throw 'Service state timeout'
}
function Wait-PortFree {
    $deadline = [DateTime]::UtcNow.AddSeconds($WaitSeconds)
    do {
        if (Invoke-Adapter 'PortFree' $Port) { return }
        Start-Sleep -Milliseconds 100
    } while ([DateTime]::UtcNow -lt $deadline)
    throw 'Port release timeout'
}
function Test-ManagedHealth($Health, [string]$Version, $Contract) {
    if (-not $Health -or -not $Health.success -or $Health.data.status -ne 'ok') { return $false }
    $data = $Health.data
    if ($data.service -ne 'MediaReview' -or $data.instance_mode -ne 'windows_service' -or $data.lifecycle_target -ne $ServiceName) { return $false }
    if ($data.version -ne $Version -or $data.components.database -ne 'ok') { return $false }
    if ($null -ne $Contract -and $data.api_contract -ne $Contract) { return $false }
    return $true
}
function Wait-Readiness([string]$Version, $Contract) {
    $deadline = [DateTime]::UtcNow.AddSeconds($WaitSeconds)
    do {
        try {
            if ((Invoke-Adapter 'Status' $ServiceName) -eq 'running' -and
                (Test-ManagedHealth (Invoke-Adapter 'Health' $healthUrl) $Version $Contract)) { return }
        } catch { }
        Start-Sleep -Milliseconds 100
    } while ([DateTime]::UtcNow -lt $deadline)
    throw 'Managed health/readiness timeout'
}

$InstallDir = Get-CanonicalPath $InstallDir
$DataRoot = Get-CanonicalPath $DataRoot
$binaryPath = Join-Path $InstallDir 'MediaReviewServer'
$backupPath = Join-Path $DataRoot ('backups\managed-' + [guid]::NewGuid().ToString('N'))
$plan = [ordered]@{
    Status = 'DRY_RUN'; Service = $ServiceName; TargetVersion = $ExpectedServerVersion
    TargetContract = $RequiredApiContract; CurrentVersion = 'unknown'; InstallDir = $InstallDir
    DataRoot = $DataRoot; SourcePackage = $SourcePackage; ServiceState = 'unknown'; JellyfinState = 'unknown'
    BackupPath = $backupPath; ReplacePaths = @($binaryPath, (Join-Path $InstallDir 'VERSION.txt'))
    HealthTarget = $healthUrl; Readiness = 'SCM running + health identity + database ok + exact version/contract'
    RollbackPath = $backupPath; ProductionExecution = 'BLOCKED_STAGE8E1'; ErrorCode = $null
}
# Production execute is denied before querying DB/config, adapter or creating files.
if ($Execute -and -not $WhatIfPreference -and -not $FixtureRoot) {
    $plan.Status = 'PRODUCTION_EXECUTION_BLOCKED'; $plan.ErrorCode = 'OFFICIAL_COORDINATOR_BRIDGE_REQUIRED'
    return [pscustomobject]$plan
}
if ($FixtureRoot) {
    $FixtureRoot = Get-CanonicalPath $FixtureRoot
    $allowed = Get-CanonicalPath (Join-Path $PSScriptRoot '..\..\temp\managed-upgrade-fixtures')
    if (-not $FixtureRoot.StartsWith($allowed + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Fixture outside project temp rejected' }
    foreach ($path in @($InstallDir, $DataRoot, $SourcePackage)) {
        if (-not $path -or -not (Get-CanonicalPath $path).StartsWith($FixtureRoot + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Fixture path escape rejected' }
        Assert-NoReparse (Get-CanonicalPath $path)
    }
    Assert-NoReparse $FixtureRoot
    $paths = @($InstallDir, $DataRoot, (Get-CanonicalPath $SourcePackage))
    for ($i = 0; $i -lt $paths.Count; $i++) {
        for ($j = $i + 1; $j -lt $paths.Count; $j++) {
            if ($paths[$i] -eq $paths[$j] -or
                $paths[$i].StartsWith($paths[$j] + '\', [StringComparison]::OrdinalIgnoreCase) -or
                $paths[$j].StartsWith($paths[$i] + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Overlapping upgrade roots rejected' }
        }
    }
    if (-not $FixtureAdapter) { throw 'Fixture requires explicit mock adapter' }
    $plan.ServiceState = Invoke-Adapter 'Status' $ServiceName
    $currentHealth = Invoke-Adapter 'Health' $healthUrl
    $plan.CurrentVersion = $currentHealth.data.version
    $plan.JellyfinState = 'NOT_QUERIED_FIXTURE'
} else {
    try { $plan.ServiceState = (Get-Service -Name $ServiceName -ErrorAction Stop).Status.ToString() } catch { }
    try { $plan.JellyfinState = (Get-Service -Name 'JellyfinServer' -ErrorAction Stop).Status.ToString() } catch { }
    try { $plan.CurrentVersion = (Invoke-RestMethod -Uri $healthUrl -TimeoutSec 3).data.version } catch { }
}
if (-not $Execute -or $WhatIfPreference) { return [pscustomobject]$plan }
# No production adapter is accepted in Stage8E.1. This engine runs only fake files
# and explicitly injected fixture lifecycle actions under the validated temp root.
if (-not $PSCmdlet.ShouldProcess($ServiceName, '隔离 fixture 升级及失败恢复')) { return [pscustomobject]$plan }
$groups = @(
    @{ Name = 'binary'; Path = $binaryPath; Required = $true },
    @{ Name = 'version'; Path = (Join-Path $InstallDir 'VERSION.txt'); Required = $false },
    @{ Name = 'config'; Path = (Join-Path $DataRoot 'config'); Required = $true },
    @{ Name = 'db'; Path = (Join-Path $DataRoot 'database\mediareview.db'); Required = $true },
    @{ Name = 'wal'; Path = (Join-Path $DataRoot 'database\mediareview.db-wal'); Required = $false },
    @{ Name = 'shm'; Path = (Join-Path $DataRoot 'database\mediareview.db-shm'); Required = $false },
    @{ Name = 'winsw'; Path = (Join-Path $DataRoot 'service\main'); Required = $true }
)
$stopped = $false; $backupReady = $false; $switched = $false
$phase = 'PREFLIGHT'; $manifest = @(); $stage = Join-Path $InstallDir ('.managed-stage-' + [guid]::NewGuid().ToString('N'))
$displaced = Join-Path $InstallDir ('.managed-old-' + [guid]::NewGuid().ToString('N'))
try {
    $source = Join-Path (Get-CanonicalPath $SourcePackage) 'MediaReviewServer'
    if (-not (Test-Path -LiteralPath (Join-Path $source 'MediaReviewServer.exe') -PathType Leaf)) { throw 'Source binary missing' }
    foreach ($group in $groups) {
        $group.Exists = Test-Path -LiteralPath $group.Path
        if ($group.Required -and -not $group.Exists) { throw 'Required backup source missing' }
    }
    foreach ($requiredFile in @((Join-Path $binaryPath 'MediaReviewServer.exe'), (Join-Path $DataRoot 'service\main\WinSW.xml'), (Join-Path $DataRoot 'service\main\WinSW.exe'), (Join-Path $DataRoot 'config\config.json'))) {
        if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) { throw 'Required install/service file missing' }
    }
    if ($plan.ServiceState -ne 'running' -or -not (Test-ManagedHealth $currentHealth $plan.CurrentVersion $currentHealth.data.api_contract)) { throw 'Initial managed identity/health mismatch' }
    $phase = 'STOP'; $stopped = $true
    $null = Invoke-Adapter 'Stop' $ServiceName; Wait-ServiceState 'stopped'; Wait-PortFree
    $phase = 'BACKUP'
    # A DB and its optional sidecars belong to the same stopped/released snapshot.
    # Preflight existence is not a recovery snapshot: Stop may create/remove files.
    foreach ($group in $groups) {
        $group.Exists = Test-Path -LiteralPath $group.Path
        if ($group.Required -and -not $group.Exists) { throw 'Required stopped source missing' }
        $group.Files = @(Get-FileInventory $group.Path)
        foreach ($entry in $group.Files) {
            $relative = $group.Name
            if ($entry.path) { $relative += '\' + $entry.path }
            $manifest += @{ path = $relative; bytes = $entry.bytes; sha256 = $entry.sha256 }
        }
    }
    $null = New-Item -ItemType Directory -Path $backupPath
    foreach ($group in $groups) {
        if ($group.Exists) {
            Copy-Item -LiteralPath $group.Path -Destination (Join-Path $backupPath $group.Name) -Recurse -Force
        }
    }
    if ($FixtureFault -eq 'backupfail') { throw 'Fixture backup failure' }
    foreach ($group in $groups) { Assert-Inventory $group.Path $group.Files }
    Assert-Inventory $backupPath $manifest
    @{ service = $ServiceName; service_state = 'stopped'; port_free = $true; old_version = $plan.CurrentVersion; target_version = $ExpectedServerVersion; files = @($manifest); groups = @($groups | ForEach-Object { @{ name = $_.Name; existed = $_.Exists } }) } |
        ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $backupPath 'backup_manifest.json') -Encoding UTF8
    $backupReady = $true; $phase = 'STAGING'
    $sourceFiles = @(Get-FileInventory $source)
    Copy-Item -LiteralPath $source -Destination $stage -Recurse -Force
    if ($FixtureFault -eq 'copyfail') { throw 'Fixture staging copy failure' }
    Assert-Inventory $source $sourceFiles
    Assert-Inventory $stage $sourceFiles
    $phase = 'SWITCH'; $switched = $true
    Move-Item -LiteralPath $binaryPath -Destination $displaced
    if ($FixtureFault -eq 'switchfail') { throw 'Fixture partial switch failure' }
    Move-Item -LiteralPath $stage -Destination $binaryPath
    Set-Content -LiteralPath (Join-Path $InstallDir 'VERSION.txt') -Value $ExpectedServerVersion -Encoding UTF8
    $phase = 'START'; $null = Invoke-Adapter 'Start' $ServiceName; Wait-ServiceState 'running'
    $phase = 'READINESS'; Wait-Readiness $ExpectedServerVersion $RequiredApiContract
    $plan.Status = 'UPGRADE_PASS'
} catch {
    $plan.ErrorCode = $phase + '_FAILED'
    if (-not $stopped) {
        $plan.Status = 'PREFLIGHT_FAILED'
        return [pscustomobject]$plan
    }
    $plan.Status = 'UPGRADE_FAILED_ROLLBACK_PASS'
    try {
        if ($switched) {
            $null = Invoke-Adapter 'Stop' $ServiceName; Wait-ServiceState 'stopped'; Wait-PortFree
            if (-not $backupReady) { throw 'Consistent backup unavailable' }
            Assert-Inventory $backupPath $manifest -ExcludeManifest
            foreach ($group in $groups) {
                if (Test-Path -LiteralPath $group.Path) { Remove-Item -LiteralPath $group.Path -Recurse -Force }
                if ($group.Exists) { Copy-Item -LiteralPath (Join-Path $backupPath $group.Name) -Destination $group.Path -Recurse -Force }
                Assert-Inventory $group.Path $group.Files
            }
        }
        if ($stopped) {
            $null = Invoke-Adapter 'Start' $ServiceName; Wait-ServiceState 'running'
            Wait-Readiness $plan.CurrentVersion $currentHealth.data.api_contract
        }
    } catch { $plan.Status = 'UPGRADE_FAILED_ROLLBACK_FAILED' }
}
# Backup/displaced directories are retained as recovery evidence; no cleanup on
# rollback failure. The isolated fixture harness owns deletion of its own root.
return [pscustomobject]$plan
