#requires -Version 5.1
$ErrorActionPreference = 'Stop'
$script:passed = 0
function Assert($condition,$name) { if (-not $condition) { throw "FAIL: $name" }; $script:passed++; "PASS: $name" }
$tool = Join-Path $PSScriptRoot '..\scripts\verified-rc.ps1'
Assert (Test-Path -LiteralPath $tool) 'trusted RC validator exists'
. $tool
$base = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\temp\managed-upgrade-fixtures'))
$root = Join-Path $base ('rc-' + [guid]::NewGuid().ToString('N'))
function Seal($path) {
    $exe = (Get-FileHash -LiteralPath (Join-Path $path 'MediaReviewServer\MediaReviewServer.exe')).Hash.ToLowerInvariant()
    @{server_version='1.2.2';api_contract=2;server_exe_sha256=$exe;git_head=('a'*40)} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $path 'RELEASE_MANIFEST.json') -Encoding UTF8
    RewriteSums $path
}
function RewriteSums($path) {
    @(Get-ChildItem -LiteralPath $path -Recurse -File -Force | Where-Object Name -ne 'SHA256SUMS.txt' | ForEach-Object { (Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant() + '  ' + $_.FullName.Substring($path.Length+1).Replace('\','/') }) | Set-Content -LiteralPath (Join-Path $path 'SHA256SUMS.txt') -Encoding UTF8
}
try {
 # The junction rejection case runs last: the fixture boundary deliberately
 # rejects any reparse child, including one from a previous case in this root.
 foreach ($case in @('valid','missingmanifest','version','contract','exehash','tampered','missingfile','added','hidden','traversal','duplicate','selflisted','sumfoldercase','sumexecase','sumdependencycase','summanifestcase','diskfoldercase','diskexecase','diskmanifestcase','disksumscase','duplicatecase','hiddenvalid','reparse')) {
    $p=Join-Path $root $case
    $null=New-Item -ItemType Directory -Path (Join-Path $p 'MediaReviewServer') -Force
    Set-Content -LiteralPath (Join-Path $p 'MediaReviewServer\MediaReviewServer.exe') 'fake-exe'
    Set-Content -LiteralPath (Join-Path $p 'MediaReviewServer\dep.dll') 'fake-dependency'
    Seal $p
    switch ($case) {
      missingmanifest { Remove-Item -LiteralPath (Join-Path $p 'RELEASE_MANIFEST.json') }
      version { $m=Get-Content (Join-Path $p 'RELEASE_MANIFEST.json') -Raw | ConvertFrom-Json; $m.server_version='1.2.1'; $m|ConvertTo-Json|Set-Content (Join-Path $p 'RELEASE_MANIFEST.json'); RewriteSums $p }
      contract { $m=Get-Content (Join-Path $p 'RELEASE_MANIFEST.json') -Raw | ConvertFrom-Json; $m.api_contract=1; $m|ConvertTo-Json|Set-Content (Join-Path $p 'RELEASE_MANIFEST.json'); RewriteSums $p }
      exehash { $m=Get-Content (Join-Path $p 'RELEASE_MANIFEST.json') -Raw | ConvertFrom-Json; $m.server_exe_sha256='b'*64; $m|ConvertTo-Json|Set-Content (Join-Path $p 'RELEASE_MANIFEST.json'); RewriteSums $p }
      tampered { Set-Content (Join-Path $p 'MediaReviewServer\dep.dll') 'tampered' }
      missingfile { Remove-Item (Join-Path $p 'MediaReviewServer\dep.dll') }
      added { Set-Content (Join-Path $p 'extra') 'extra' }
      hidden { $f=Join-Path $p 'hidden'; Set-Content $f 'hidden'; (Get-Item $f).Attributes=[IO.FileAttributes]::Hidden }
      traversal { Add-Content (Join-Path $p 'SHA256SUMS.txt') (('a'*64)+'  ../outside') }
      duplicate { Add-Content (Join-Path $p 'SHA256SUMS.txt') (Get-Content (Join-Path $p 'SHA256SUMS.txt') -First 1) }
      selflisted { Add-Content (Join-Path $p 'SHA256SUMS.txt') (('a'*64)+'  SHA256SUMS.txt') }
      reparse { $null=New-Item -ItemType Junction -Path (Join-Path $p 'linked') -Target (Join-Path $p 'MediaReviewServer') }
      sumfoldercase { (Get-Content (Join-Path $p 'SHA256SUMS.txt')).Replace('MediaReviewServer/','mediareviewserver/') | Set-Content (Join-Path $p 'SHA256SUMS.txt') }
      sumexecase { (Get-Content (Join-Path $p 'SHA256SUMS.txt')).Replace('/MediaReviewServer.exe','/mediareviewserver.exe') | Set-Content (Join-Path $p 'SHA256SUMS.txt') }
      sumdependencycase { (Get-Content (Join-Path $p 'SHA256SUMS.txt')).Replace('/dep.dll','/DEP.dll') | Set-Content (Join-Path $p 'SHA256SUMS.txt') }
      summanifestcase { (Get-Content (Join-Path $p 'SHA256SUMS.txt')).Replace('RELEASE_MANIFEST.json','release_manifest.json') | Set-Content (Join-Path $p 'SHA256SUMS.txt') }
      # Rename through a temporary name so Windows records the changed casing.
      diskfoldercase { Rename-Item (Join-Path $p 'MediaReviewServer') 'case-tmp'; Rename-Item (Join-Path $p 'case-tmp') 'mediareviewserver'; RewriteSums $p }
      diskexecase { Rename-Item (Join-Path $p 'MediaReviewServer\MediaReviewServer.exe') 'case-tmp'; Rename-Item (Join-Path $p 'MediaReviewServer\case-tmp') 'mediareviewserver.exe'; RewriteSums $p }
      diskmanifestcase { Rename-Item (Join-Path $p 'RELEASE_MANIFEST.json') 'case-tmp'; Rename-Item (Join-Path $p 'case-tmp') 'release_manifest.json'; RewriteSums $p }
      disksumscase { Rename-Item (Join-Path $p 'SHA256SUMS.txt') 'case-tmp'; Rename-Item (Join-Path $p 'case-tmp') 'sha256sums.txt' }
      duplicatecase { $entry=Get-Content (Join-Path $p 'SHA256SUMS.txt') | Where-Object { $_.Contains('MediaReviewServer/MediaReviewServer.exe') }; Add-Content (Join-Path $p 'SHA256SUMS.txt') $entry.Replace('MediaReviewServer/MediaReviewServer.exe','mediareviewserver/mediareviewserver.exe') }
      hiddenvalid { $f=Join-Path $p 'MediaReviewServer\hidden.dll'; Set-Content $f 'hidden'; (Get-Item $f).Attributes=[IO.FileAttributes]::Hidden; RewriteSums $p }
    }
    $before=@(Get-ChildItem $p -File -Recurse -Force | ForEach-Object { $_.FullName+':'+(Get-FileHash $_.FullName).Hash }) -join '|'
    $ok=$true; try { $r=Test-VerifiedRc $p '1.2.2' 2 } catch { $ok=$false }
    $validCase=$case -in @('valid','hiddenvalid')
    Assert ($ok -eq $validCase) "$case source gate"
    if ($validCase) {
      Assert ($r.sourcePackageHash -match '^[a-f0-9]{64}$') 'stable source digest format'
      Assert ((Test-VerifiedRc $p '1.2.2' 2).sourcePackageHash -eq $r.sourcePackageHash) 'repeat validation stable'
      if ($case -eq 'hiddenvalid') { Assert (@($r.binaryInventory | Where-Object { $_.path -ceq 'hidden.dll' }).Count -eq 1) 'registered hidden binary retained with exact casing' }
    }
    $after=@(Get-ChildItem $p -File -Recurse -Force | ForEach-Object { $_.FullName+':'+(Get-FileHash $_.FullName).Hash }) -join '|'
    Assert ($before -eq $after) "$case validator read only"
    if (-not $validCase) {
      $state=@{Calls=0}; $adapter=@{ Stop={ $state.Calls++ }.GetNewClosure(); Start={ $state.Calls++ }.GetNewClosure() }
      $result=& (Join-Path $PSScriptRoot '..\scripts\upgrade-aihome-managed.ps1') -SourcePackage $p -Execute
      Assert ($result.ErrorCode -eq 'SOURCE_PACKAGE_INVALID') "$case production pre-stop gate"
      Assert ($state.Calls -eq 0) "$case zero lifecycle calls"
      if ($case -ne 'reparse') {
        $caseRoot = Split-Path -Parent $p
        $install = Join-Path $caseRoot ('install-' + $case)
        $data = Join-Path $caseRoot ('data-' + $case)
        $null = New-Item -ItemType Directory -Path $install,$data -Force
        $result = & (Join-Path $PSScriptRoot '..\scripts\upgrade-aihome-managed.ps1') -SourcePackage $p -InstallDir $install -DataRoot $data -FixtureRoot $caseRoot -FixtureAdapter $adapter -Execute -Confirm:$false
        Assert ($result.ErrorCode -eq 'SOURCE_PACKAGE_INVALID') "$case fixture source rejection"
        Assert ($state.Calls -eq 0) "$case fixture Stop and Start zero"
      }
    }
 }
 $plan=@{service='AIHome.MediaReview';currentVersion='1.1.0';targetVersion='1.2.2';apiContract=2;sourcePackageHash=('a'*64);serverExeHash=('b'*64);installDir='C:\install';dataRoot='C:\data';actionSequence=@('Stop','Backup','Stage','Switch','Start','Readiness');releaseGitHead=('a'*40)}
 $digest=Get-UpgradePlanDigest $plan
 Assert ($digest -ceq '7b0cc33c414637313ac981eb2c772a2446d41431c63b8554371ca533cdb0c4e7') 'normal ASCII plan known canonical SHA256 across engines'
 # Fixed UTF-8 canonical JSON SHA256, independently calculated from the documented
 # field order: printable ASCII literal, quote/backslash escaped, other UTF-16
 # code units lowercase \uXXXX. This identical vector runs under PS5.1 and PS7.
 $special=$plan.Clone()
 $special.installDir="C:\O'Brien\"+[char]0x4e2d+[char]0x6587+'/"&<>'+[char]0xd83d+[char]0xde00
 Assert ((Get-UpgradePlanDigest $special) -ceq 'afdd200d5af788a91799bae6c6756e2e437f8472324ebe8973886fa24eee5cd8') 'canonical special path known SHA256 across engines'
 Assert ((ConvertTo-UpgradePlanJsonString $special.installDir) -ceq '"C:\\O''Brien\\\u4e2d\u6587/\"&<>\ud83d\ude00"') 'canonical quote slash HTML Chinese supplementary string bytes'
 # Digest serialization accepts control-bearing strings with explicit escaping;
 # this is identity only, not filesystem eligibility or official approval.
 $controls='C:\test'+[char]0+[char]9+[char]10+[char]13+[char]31
 Assert ((ConvertTo-UpgradePlanJsonString $controls) -ceq '"C:\\test\u0000\u0009\u000a\u000d\u001f"') 'control code units escaped without normalization'
 $controlPlan=$plan.Clone(); $controlPlan.installDir=$controls
 Assert ((Get-UpgradePlanDigest $controlPlan) -ceq '35848a2c661e23caa8a679843f564d3ba03c0fbf532f043874b31ab7e4851b2d') 'control plan known canonical SHA256 across engines'
 foreach($invalidUnicode in @(([string][char]0xd800),([string][char]0xdc00),([string][char]0xd800+'x'),('x'+[char]0xdc00))) {
   $badUnicode=$plan.Clone(); $badUnicode.installDir='C:\'+$invalidUnicode
   $rejected=$false; try { $null=Get-UpgradePlanDigest $badUnicode } catch { $rejected=$_.Exception.Message -eq 'UPGRADE_PLAN_INVALID' }
   Assert $rejected 'unpaired UTF16 surrogate rejected before hashing'
 }
 $cultureBefore=[Threading.Thread]::CurrentThread.CurrentCulture
 try {
   [Threading.Thread]::CurrentThread.CurrentCulture=[Globalization.CultureInfo]::GetCultureInfo('ar-SA')
   Assert ((Get-UpgradePlanDigest $plan) -ceq $digest) 'integer serialization culture independent'
 } finally { [Threading.Thread]::CurrentThread.CurrentCulture=$cultureBefore }
 $longPlan=$plan.Clone(); $longPlan.apiContract=[long]2
 Assert ((Get-UpgradePlanDigest $longPlan) -ceq $digest) 'same integer int32 int64 identity'
 $different=$special.Clone(); $different.installDir=$different.installDir.Replace('/','\')
 Assert ((Get-UpgradePlanDigest $different) -cne (Get-UpgradePlanDigest $special)) 'separator change not silently normalized'
 Assert ($digest -match '^[a-f0-9]{64}$') 'plan digest format'
 $ordered=[ordered]@{}; foreach($key in @($plan.Keys|Sort-Object -Descending)) { $ordered[$key]=$plan[$key] }
 Assert ((Get-UpgradePlanDigest $ordered) -eq $digest) 'field order independent'
 foreach($field in @('currentVersion','targetVersion','apiContract','sourcePackageHash','serverExeHash','installDir','dataRoot','actionSequence','releaseGitHead')) {
   $changed=$plan.Clone()
   switch($field) { currentVersion {$changed[$field]='1.0.0'} targetVersion {$changed[$field]='1.2.3'} apiContract {$changed[$field]=3} sourcePackageHash {$changed[$field]='c'*64} serverExeHash {$changed[$field]='d'*64} actionSequence {$changed[$field]=@('Stop','Start')} releaseGitHead {$changed[$field]='b'*40} default {$changed[$field]+='2'} }
   Assert ((Get-UpgradePlanDigest $changed) -ne $digest) "$field invalidates approval digest"
 }
 foreach($case in @('missing','target','version','hash','type','unknown','actions')) {
   $bad=$plan.Clone(); switch($case) { missing {$bad.Remove('dataRoot')} target {$bad.service='Other'} version {$bad.targetVersion='oops'} hash {$bad.serverExeHash='oops'} type {$bad.apiContract='2'} unknown {$bad.unknown='x'} actions {$bad.actionSequence='Stop'} }
   $rejected=$false; try {$null=Get-UpgradePlanDigest $bad} catch {$rejected=$true}; Assert $rejected "$case invalid plan rejected"
 }
 "VERIFIED_RC_PASS assertions=$script:passed"
} finally {
 if ([IO.Path]::GetFullPath($root).StartsWith($base+'\',[StringComparison]::OrdinalIgnoreCase)) { Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue }
}
