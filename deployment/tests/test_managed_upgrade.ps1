#requires -Version 5.1
[CmdletBinding()]
param([string]$TestCase = '')
$ErrorActionPreference = 'Stop'
$upgrade = Join-Path $PSScriptRoot '..\scripts\upgrade-aihome-managed.ps1'
if (-not (Test-Path -LiteralPath $upgrade)) { throw 'FAIL: managed upgrade tool is missing' }
$fixtureBase = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\temp\managed-upgrade-fixtures'))
$root = Join-Path $fixtureBase ([guid]::NewGuid().ToString('N'))
$passed = 0
function Assert($Condition, $Name) {
    if (-not $Condition) { throw "FAIL: $Name" }
    $script:passed++; Write-Output "PASS: $Name"
}
function Snapshot($Path) {
    return @(Get-ChildItem -LiteralPath $Path -File -Recurse -Force | Sort-Object FullName | ForEach-Object {
        "$($_.FullName):$((Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash)"
    }) -join '|'
}
# GetNewClosure creates a dynamic module. A caller script's functions are not
# visible there when this harness is invoked with &, so capture the action.
$snapshotAction = ${function:Snapshot}
try {
    foreach ($case in @('stopcreates','stopremoves','hiddencorrupt','backupadded','stopfail','firststartfail','switchfail','success','healthfail','copyfail','backupfail','rollbackfail','portbusy','wrongidentity','absentsidecars','whatif','dryrun')) {
        if ($TestCase -and $case -ne $TestCase) { continue }
        $caseRoot = Join-Path $root $case
        foreach ($dir in @('install\MediaReviewServer','package\MediaReviewServer','data\config','data\database','data\service\main')) {
            $null = New-Item -ItemType Directory -Path (Join-Path $caseRoot $dir) -Force
        }
        foreach ($file in @('install\MediaReviewServer\MediaReviewServer.exe','install\MediaReviewServer\dependency.dll','data\config\config.json','data\database\mediareview.db','data\database\mediareview.db-wal','data\database\mediareview.db-shm','data\service\main\WinSW.xml','data\service\main\WinSW.exe')) {
            Set-Content -LiteralPath (Join-Path $caseRoot $file) -Value "fake-old-$file" -Encoding UTF8
        }
        Set-Content -LiteralPath (Join-Path $caseRoot 'package\MediaReviewServer\MediaReviewServer.exe') -Value 'fake-new' -Encoding UTF8
        Set-Content -LiteralPath (Join-Path $caseRoot 'package\MediaReviewServer\new.dll') -Value 'fake-new-dependency' -Encoding UTF8
        Set-Content -LiteralPath (Join-Path $caseRoot 'install\VERSION.txt') -Value '1.1.0' -Encoding UTF8
        foreach ($hidden in @('install\MediaReviewServer\hidden.dll','package\MediaReviewServer\hidden.dll','data\config\hidden.json','data\service\main\hidden.xml')) {
            $hiddenPath = Join-Path $caseRoot $hidden
            Set-Content -LiteralPath $hiddenPath -Value "fake-$hidden" -Encoding UTF8
            (Get-Item -LiteralPath $hiddenPath -Force).Attributes = [IO.FileAttributes]::Hidden
        }
        if ($case -in @('absentsidecars','stopcreates')) {
            Remove-Item -LiteralPath (Join-Path $caseRoot 'data\database\mediareview.db-wal')
            Remove-Item -LiteralPath (Join-Path $caseRoot 'data\database\mediareview.db-shm')
        }
        $oldData = Snapshot (Join-Path $caseRoot 'data')
        $oldBinary = Snapshot (Join-Path $caseRoot 'install\MediaReviewServer')
        $oldVersion = (Get-FileHash -LiteralPath (Join-Path $caseRoot 'install\VERSION.txt')).Hash
        $state = @{ Running = $true; Calls = [Collections.Generic.List[string]]::new(); Starts = 0; Stops = 0; StoppedData = $oldData }
        $adapter = @{
            Status = { param($name) if ($state.Running) { 'running' } else { 'stopped' } }.GetNewClosure()
            Stop = {
                param($name)
                $state.Calls.Add("stop:$name"); $state.Stops++; $state.Running = $false
                if ($state.Stops -eq 1) {
                    foreach ($suffix in @('wal','shm')) {
                        $sidecar = Join-Path $caseRoot "data\database\mediareview.db-$suffix"
                        if ($case -eq 'stopcreates') { Set-Content -LiteralPath $sidecar -Value "fake-stop-$suffix" -Encoding UTF8 }
                        if ($case -eq 'stopremoves') { Move-Item -LiteralPath $sidecar -Destination (Join-Path $caseRoot "removed-$suffix") }
                    }
                    $state.StoppedData = & $snapshotAction (Join-Path $caseRoot 'data')
                    if ($case -eq 'stopfail') { throw 'fixture stop failure after stop' }
                }
            }.GetNewClosure()
            Start = {
                param($name)
                $state.Calls.Add("start:$name"); $state.Starts++
                if ($case -eq 'rollbackfail' -and $state.Starts -ge 2) { throw 'fixture restart failure' }
                if ($case -in @('healthfail','absentsidecars','stopcreates','stopremoves','firststartfail') -and $state.Starts -eq 1) {
                    Set-Content -LiteralPath (Join-Path $caseRoot 'data\database\mediareview.db') -Value 'fake migrated DB' -Encoding UTF8
                    Set-Content -LiteralPath (Join-Path $caseRoot 'data\database\mediareview.db-wal') -Value 'fake migrated WAL' -Encoding UTF8
                    Set-Content -LiteralPath (Join-Path $caseRoot 'data\database\mediareview.db-shm') -Value 'fake migrated SHM' -Encoding UTF8
                    Set-Content -LiteralPath (Join-Path $caseRoot 'data\config\config.json') -Value 'fake changed config' -Encoding UTF8
                }
                if ($case -eq 'firststartfail' -and $state.Starts -eq 1) { $state.Running = $true; throw 'fixture first start failure after DB/config writes' }
                if ($case -in @('hiddencorrupt','backupadded') -and $state.Starts -eq 1) {
                    $backup = @(Get-ChildItem -LiteralPath (Join-Path $caseRoot 'data\backups') -Directory)[0].FullName
                    $corruptPath = Join-Path $backup 'binary\hidden.dll'
                    if ($case -eq 'backupadded') { $corruptPath = Join-Path $backup 'binary\unexpected.dll' }
                    Set-Content -LiteralPath $corruptPath -Value 'fake-corruption' -Encoding UTF8 -Force
                }
                $state.Running = $true
            }.GetNewClosure()
            PortFree = { param($port) $case -ne 'portbusy' }.GetNewClosure()
            Health = {
                $target = 'AIHome.MediaReview'; if ($case -eq 'wrongidentity') { $target = 'interactive' }
                $old = (Get-Content -LiteralPath (Join-Path $caseRoot 'install\MediaReviewServer\MediaReviewServer.exe') -Raw).Trim() -like 'fake-old-*'
                $version = '1.2.2'; if ($old) { $version = '1.1.0' }
                if (-not $old -and $case -in @('healthfail','rollbackfail','absentsidecars','stopcreates','stopremoves','hiddencorrupt','backupadded') -and $state.Starts -eq 1) { $version = '0' }
                @{ success = $true; data = @{ status = 'ok'; service = 'MediaReview'; version = $version; api_contract = 2; instance_mode = 'windows_service'; lifecycle_target = $target; components = @{ database = 'ok' } } }
            }.GetNewClosure()
        }
        $args = @{ InstallDir = (Join-Path $caseRoot 'install'); DataRoot = (Join-Path $caseRoot 'data'); SourcePackage = (Join-Path $caseRoot 'package'); FixtureRoot = $caseRoot; FixtureAdapter = $adapter; WaitSeconds = 1 }
        if ($case -eq 'success') {
            $unsafe = $args.Clone(); $unsafe.SourcePackage = $unsafe.InstallDir
            $rejected = $false
            try { $null = & $upgrade @unsafe -Execute -Confirm:$false } catch { $rejected = $true }
            Assert $rejected 'overlapping source and install rejected before actions'
            Assert ($state.Calls.Count -eq 0) 'invalid paths no lifecycle actions'
        }
        $before = Snapshot $caseRoot
        if ($case -eq 'whatif') { $result = & $upgrade @args -Execute -WhatIf }
        elseif ($case -eq 'dryrun') { $result = & $upgrade @args }
        else {
            $fault = ''; if ($case -in @('copyfail','backupfail','switchfail')) { $fault = $case }
            $result = & $upgrade @args -Execute -FixtureFault $fault -Confirm:$false
        }
        $result = @($result)[-1]
        if ($case -in @('whatif','dryrun')) {
            Assert ($result.Status -eq 'DRY_RUN') "$case status"
            Assert ((Snapshot $caseRoot) -eq $before) "$case zero writes"
            Assert ($state.Calls.Count -eq 0) "$case zero lifecycle actions"
        } elseif ($case -eq 'success') {
            Assert ($result.Status -eq 'UPGRADE_PASS') 'success result'
            Assert ((Get-Content -LiteralPath (Join-Path $caseRoot 'install\MediaReviewServer\MediaReviewServer.exe') -Raw).Trim() -eq 'fake-new') 'new binary switched'
            Assert (Test-Path -LiteralPath (Join-Path $result.BackupPath 'backup_manifest.json')) 'hashed backup manifest'
            Assert ((Get-Content -LiteralPath (Join-Path $result.BackupPath 'backup_manifest.json') -Raw | ConvertFrom-Json).files.Count -eq 12) 'backup includes hidden files and all recovery sources'
            Assert (Test-Path -LiteralPath (Join-Path $caseRoot 'install\MediaReviewServer\hidden.dll')) 'hidden package dependency staged'
            Assert ($state.Running) 'same service restarted'
        } else {
            $expected = 'UPGRADE_FAILED_ROLLBACK_PASS'; if ($case -in @('rollbackfail','hiddencorrupt','backupadded')) { $expected = 'UPGRADE_FAILED_ROLLBACK_FAILED' }
            if ($case -eq 'wrongidentity') { $expected = 'PREFLIGHT_FAILED' }
            Assert ($result.Status -eq $expected) "$case result"
            if ($case -in @('hiddencorrupt','backupadded')) {
                Assert ($state.Starts -eq 1) "$case refuses restart from invalid backup"
                Assert ((Get-Content -LiteralPath (Join-Path $caseRoot 'install\MediaReviewServer\MediaReviewServer.exe') -Raw).Trim() -eq 'fake-new') "$case rejects restore before overwriting any live group"
                continue
            }
            Assert ((Get-Content -LiteralPath (Join-Path $caseRoot 'install\MediaReviewServer\MediaReviewServer.exe') -Raw).Trim() -like 'fake-old-*') "$case old binary retained/restored"
            Assert (Test-Path -LiteralPath (Join-Path $caseRoot 'install\MediaReviewServer\dependency.dll')) "$case old dependency restored"
            Assert (-not (Test-Path -LiteralPath (Join-Path $caseRoot 'install\MediaReviewServer\new.dll'))) "$case new dependency absent"
            if ($case -in @('healthfail','absentsidecars','stopcreates','stopremoves','stopfail','firststartfail','switchfail','copyfail','backupfail','portbusy')) {
                # Compare only restored sources; backup evidence deliberately remains.
                $restored = @(Get-ChildItem -LiteralPath (Join-Path $caseRoot 'data') -File -Recurse -Force | Where-Object { $_.FullName -notlike '*\backups\*' } | Sort-Object FullName | ForEach-Object { "$($_.FullName):$((Get-FileHash -LiteralPath $_.FullName).Hash)" }) -join '|'
                Assert ($restored -eq $state.StoppedData) "$case config DB WAL SHM definition restored to stopped snapshot exactly"
                Assert ((Snapshot (Join-Path $caseRoot 'install\MediaReviewServer')) -eq $oldBinary) "$case full old binary hashes restored"
                Assert ((Get-FileHash -LiteralPath (Join-Path $caseRoot 'install\VERSION.txt')).Hash -eq $oldVersion) "$case VERSION restored exactly"
            }
            if ($case -in @('stopcreates','stopremoves')) {
                $saved = Get-Content -LiteralPath (Join-Path $result.BackupPath 'backup_manifest.json') -Raw | ConvertFrom-Json
                Assert ($saved.service_state -eq 'stopped' -and $saved.port_free) "$case manifest records stopped released state"
                foreach ($name in @('wal','shm')) { Assert ((@($saved.groups | Where-Object { $_.name -eq $name })[0].existed) -eq ($case -eq 'stopcreates')) "$case $name existence from stopped snapshot" }
                Assert ($result.ErrorCode -eq 'READINESS_FAILED') "$case progresses beyond backup"
            }
            if ($case -in @('stopfail','firststartfail','switchfail')) {
                $expectedCalls = 'stop:AIHome.MediaReview|start:AIHome.MediaReview'
                if ($case -eq 'firststartfail') { $expectedCalls = 'stop:AIHome.MediaReview|start:AIHome.MediaReview|stop:AIHome.MediaReview|start:AIHome.MediaReview' }
                if ($case -eq 'switchfail') { $expectedCalls = 'stop:AIHome.MediaReview|stop:AIHome.MediaReview|start:AIHome.MediaReview' }
                Assert (($state.Calls -join '|') -eq $expectedCalls) "$case recovery lifecycle order"
            }
            if ($case -ne 'rollbackfail') { Assert ($state.Running) "$case service running after recovery" }
        }
    }
    $blocked = & $upgrade -Execute
    Assert ($blocked.Status -eq 'PRODUCTION_EXECUTION_BLOCKED') 'Stage8E.1 production execution blocked before actions'
    Write-Output "MANAGED_UPGRADE_FIXTURE_PASS assertions=$passed"
} finally {
    if ([IO.Path]::GetFullPath($root).StartsWith($fixtureBase + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue
    }
}
