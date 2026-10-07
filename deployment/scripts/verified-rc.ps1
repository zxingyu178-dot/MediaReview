#requires -Version 5.1
# 可信 RC 和固定升级计划摘要。仅读取包文件，不执行生命周期动作。
function Get-RcTextHash([string]$Text) {
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($algorithm.ComputeHash([Text.Encoding]::UTF8.GetBytes($Text)))).Replace('-','').ToLowerInvariant() }
    finally { $algorithm.Dispose() }
}
function Test-VerifiedRc {
    param([string]$SourcePackage,[string]$ExpectedServerVersion,$RequiredApiContract)
    $ErrorActionPreference = 'Stop'
    try {
        if (-not $SourcePackage -or -not (Test-Path -LiteralPath $SourcePackage -PathType Container)) { throw 'Missing root' }
        $root=[IO.Path]::GetFullPath($SourcePackage).TrimEnd('\','/')
        $cursor=$root
        while ($cursor) {
            if ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Reparse ancestor' }
            $parent=Split-Path -Parent $cursor; if ($parent -eq $cursor) { break }; $cursor=$parent
        }
        # Walk one directory at a time: never recurse through an unchecked link.
        $queue=New-Object 'Collections.Generic.Queue[string]'; $queue.Enqueue($root)
        $files=New-Object 'Collections.Generic.Dictionary[string,object]' ([StringComparer]::OrdinalIgnoreCase)
        # Keep case-insensitive collision rejection, but require inventory paths
        # and fixed package entries to match the actual relative path exactly.
        # Staging uses ordinal paths; Windows lookup must not accept a different
        # spelling here and leave an empty/mismatched binary inventory after Stop.
        $actualPaths=New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
        while ($queue.Count) {
            foreach($item in Get-ChildItem -LiteralPath $queue.Dequeue() -Force) {
                if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Reparse child' }
                if ($item.PSIsContainer) { $queue.Enqueue($item.FullName) }
                else {
                    $relativePath=$item.FullName.Substring($root.Length+1).Replace('\','/')
                    $files.Add($relativePath,$item)
                    $null=$actualPaths.Add($relativePath)
                }
            }
        }
        if (-not $actualPaths.Contains('SHA256SUMS.txt') -or -not $actualPaths.Contains('RELEASE_MANIFEST.json') -or -not $actualPaths.Contains('MediaReviewServer/MediaReviewServer.exe')) { throw 'Missing exact package entries' }
        $inventory=New-Object 'Collections.Generic.Dictionary[string,string]' ([StringComparer]::OrdinalIgnoreCase)
        foreach($line in Get-Content -LiteralPath (Join-Path $root 'SHA256SUMS.txt')) {
            if ($line -notmatch '^([a-fA-F0-9]{64})  (.+)$') { throw 'Malformed inventory' }
            $hash=$Matches[1].ToLowerInvariant(); $path=$Matches[2]
            if ($path.Contains('\') -or $path.Contains(':') -or $path.StartsWith('/') -or $path -match '[\x00-\x1f]' -or $path -match '(^|/)(\.|\.\.)(/|$)' -or $path -match '//|/$' -or $path -match '(^|/)[^/]*[ .](/|$)' -or $path -ieq 'SHA256SUMS.txt') { throw 'Unsafe inventory path' }
            if ($inventory.ContainsKey($path) -or -not $actualPaths.Contains($path)) { throw 'Duplicate, missing or case-mismatched entry' }
            if ((Get-FileHash -LiteralPath $files[$path].FullName -Algorithm SHA256).Hash.ToLowerInvariant() -cne $hash) { throw 'Hash mismatch' }
            $inventory.Add($path,$hash)
        }
        if ($inventory.Count -ne ($files.Count-1) -or -not $inventory.ContainsKey('RELEASE_MANIFEST.json') -or -not $inventory.ContainsKey('MediaReviewServer/MediaReviewServer.exe')) { throw 'Incomplete inventory' }
        $m=Get-Content -LiteralPath (Join-Path $root 'RELEASE_MANIFEST.json') -Raw | ConvertFrom-Json
        if ($m.server_version -isnot [string] -or $m.server_version -cne $ExpectedServerVersion -or ($m.api_contract -isnot [long] -and $m.api_contract -isnot [int]) -or $m.api_contract -ne $RequiredApiContract) { throw 'Release identity mismatch' }
        $exeHash=$inventory['MediaReviewServer/MediaReviewServer.exe']
        if ($m.server_exe_sha256 -isnot [string] -or $m.server_exe_sha256 -notmatch '^[a-fA-F0-9]{64}$' -or $m.server_exe_sha256.ToLowerInvariant() -cne $exeHash) { throw 'Executable identity mismatch' }
        $keys=[string[]]@($inventory.Keys); [Array]::Sort($keys,[StringComparer]::Ordinal)
        $canonical=@($keys | ForEach-Object { $_+'|'+$inventory[$_] }) -join "`n"
        $head=$m.git_head
        if ($m.package_git_head) { $head=$m.package_git_head }
        if ($null -ne $head -and ($head -isnot [string] -or $head -notmatch '^[a-fA-F0-9]{40}$')) { throw 'Invalid release head' }
        $binaryInventory=@($keys | Where-Object { $_.StartsWith('MediaReviewServer/',[StringComparison]::Ordinal) } | ForEach-Object {
            @{path=$_.Substring('MediaReviewServer/'.Length).Replace('/','\');bytes=$files[$_].Length;sha256=$inventory[$_].ToUpperInvariant()}
        })
        return [pscustomobject]@{binaryInventory=$binaryInventory;sourcePackageHash=(Get-RcTextHash $canonical);serverExeHash=$exeHash;releaseGitHead=$head}
    } catch { throw 'SOURCE_PACKAGE_INVALID' }
}
# Canonical strings: printable ASCII is literal except quote/backslash; all other
# UTF-16 units use lowercase \uXXXX. Reject unmatched surrogates rather than let
# UTF-8 replacement fallback collapse different plan identities. No path folding.
function ConvertTo-UpgradePlanJsonString([string]$Value) {
    $builder=New-Object Text.StringBuilder
    $null=$builder.Append('"')
    for($i=0; $i -lt $Value.Length; $i++) {
        $unit=[int][char]$Value[$i]
        if ($unit -ge 0xd800 -and $unit -le 0xdbff) {
            if ($i+1 -ge $Value.Length -or [int][char]$Value[$i+1] -lt 0xdc00 -or [int][char]$Value[$i+1] -gt 0xdfff) { throw 'UPGRADE_PLAN_INVALID' }
        } elseif ($unit -ge 0xdc00 -and $unit -le 0xdfff) {
            if ($i -eq 0 -or [int][char]$Value[$i-1] -lt 0xd800 -or [int][char]$Value[$i-1] -gt 0xdbff) { throw 'UPGRADE_PLAN_INVALID' }
        }
        if ($unit -eq 34) { $null=$builder.Append('\"') }
        elseif ($unit -eq 92) { $null=$builder.Append('\\') }
        elseif ($unit -lt 32 -or $unit -gt 126) { $null=$builder.Append('\u').Append($unit.ToString('x4',[Globalization.CultureInfo]::InvariantCulture)) }
        else { $null=$builder.Append([char]$unit) }
    }
    $null=$builder.Append('"')
    return $builder.ToString()
}
function Get-UpgradePlanDigest {
    param($Plan)
    if ($Plan -isnot [Collections.IDictionary] -and $Plan -isnot [pscustomobject]) { throw 'UPGRADE_PLAN_INVALID' }
    $fields=@('service','currentVersion','targetVersion','apiContract','sourcePackageHash','serverExeHash','installDir','dataRoot','actionSequence')
    $names=@(if ($Plan -is [Collections.IDictionary]) {$Plan.Keys} else {$Plan.PSObject.Properties.Name})
    foreach($name in $names) { if ($name -cnotin ($fields+@('releaseGitHead'))) { throw 'UPGRADE_PLAN_INVALID' } }
    foreach($field in $fields) { if ($field -cnotin $names) { throw 'UPGRADE_PLAN_INVALID' } }
    $canonical=[ordered]@{}
    foreach($field in $fields) {
        $value=$Plan.$field
        if ($field -eq 'apiContract') { if (($value -isnot [int] -and $value -isnot [long]) -or $value -lt 1) { throw 'UPGRADE_PLAN_INVALID' } }
        elseif ($field -eq 'actionSequence') {
            if ($value -isnot [array] -or $value.Count -eq 0) { throw 'UPGRADE_PLAN_INVALID' }
            foreach($action in $value) { if ($action -isnot [string] -or $action -cnotin @('Stop','Backup','Stage','Switch','Start','Readiness','Rollback')) { throw 'UPGRADE_PLAN_INVALID' } }
        } else {
            if ($value -isnot [string] -or [string]::IsNullOrWhiteSpace($value)) { throw 'UPGRADE_PLAN_INVALID' }
            if ($field -eq 'service' -and $value -cne 'AIHome.MediaReview') { throw 'UPGRADE_PLAN_INVALID' }
            if ($field -in @('currentVersion','targetVersion') -and $value -notmatch '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$') { throw 'UPGRADE_PLAN_INVALID' }
            if ($field -in @('sourcePackageHash','serverExeHash') -and $value -notmatch '^[a-f0-9]{64}$') { throw 'UPGRADE_PLAN_INVALID' }
        }
        $canonical[$field]=$value
    }
    if ('releaseGitHead' -cin $names) {
        if ($Plan.releaseGitHead -isnot [string] -or $Plan.releaseGitHead -notmatch '^[a-f0-9]{40}$') { throw 'UPGRADE_PLAN_INVALID' }
        $canonical.releaseGitHead=$Plan.releaseGitHead
    }
    # Schema-specific serialization avoids PS5.1/PS7 ConvertTo-Json differences.
    $parts=@(foreach($field in $canonical.Keys) {
        $value=$canonical[$field]
        if ($field -eq 'apiContract') { $json=$value.ToString([Globalization.CultureInfo]::InvariantCulture) }
        elseif ($field -eq 'actionSequence') { $json='['+(@(foreach($action in $value) { ConvertTo-UpgradePlanJsonString $action }) -join ',')+']' }
        else { $json=ConvertTo-UpgradePlanJsonString $value }
        (ConvertTo-UpgradePlanJsonString $field)+':'+$json
    })
    return Get-RcTextHash ('{'+($parts -join ',')+'}')
}
