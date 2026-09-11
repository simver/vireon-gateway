#requires -Version 7.4
param(
    [string]$Destination = (Join-Path ([IO.Path]::GetTempPath()) ('vireon-gateway-' + [guid]::NewGuid().ToString('N'))),
    [string]$MavenRepository,
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/delivery-common.ps1"
$taskRoot = Split-Path -Parent $PSScriptRoot
if (-not [IO.Path]::IsPathFullyQualified($Destination)) { throw 'DESTINATION_MUST_BE_ABSOLUTE' }
$Destination = [IO.Path]::GetFullPath($Destination)
if ($Destination.TrimEnd('\', '/').Equals($taskRoot, [StringComparison]::OrdinalIgnoreCase) -or
    $Destination.StartsWith($taskRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'DESTINATION_MUST_BE_OUTSIDE_SOURCE' }
if (Test-Path -LiteralPath $Destination) { throw 'DESTINATION_MUST_BE_NEW' }
$taskParent = Get-Item -LiteralPath (Split-Path -Parent $Destination)
while ($null -ne $taskParent) {
    if ($taskParent.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'DESTINATION_REPARSE_POINT' }
    $taskParent = $taskParent.Parent
}
$null = New-Item -ItemType Directory -Path $Destination
$taskDelivery = [ordered]@{ schema = 1; status = 'FAIL'; scope = 'same-environment-local-jar'; startedUtc = [datetime]::UtcNow.ToString('o'); builds = @() }
$taskStage = 'freeze'
try {
    $taskInputs = @(Get-GatewayInputs $taskRoot)
    $taskHash = Get-GatewayManifestHash $taskInputs
    $taskHead = & git -C $taskRoot rev-parse HEAD
    if ($LASTEXITCODE -ne 0) { throw 'GIT_HEAD_FAILED' }
    $taskStatus = @(& git -C $taskRoot status --porcelain=v1 --untracked-files=all)
    if ($LASTEXITCODE -ne 0) { throw 'GIT_STATUS_FAILED' }
    $taskFrozen = [ordered]@{ head = $taskHead; dirty = $taskStatus.Count -gt 0; manifestSha256 = $taskHash; files = $taskInputs }
    Write-GatewayJson (Join-Path $Destination 'frozen-source.json') $taskFrozen
    $taskDelivery.source = $taskFrozen
    # Both copies are frozen before either build starts. No target, Git metadata or sibling inputs.
    foreach ($taskCopy in @('build-a', 'build-b')) {
        $taskCopyRoot = Join-Path $Destination $taskCopy
        $null = New-Item -ItemType Directory -Path $taskCopyRoot
        foreach ($taskInput in $taskInputs) {
            $taskFrom = Join-Path $taskRoot $taskInput.path
            $taskTo = Join-Path $taskCopyRoot $taskInput.path
            $null = New-Item -ItemType Directory -Path (Split-Path -Parent $taskTo) -Force
            Copy-Item -LiteralPath $taskFrom -Destination $taskTo
            if ((Get-FileHash -LiteralPath $taskTo).Hash -ne $taskInput.sha256) { throw 'COPY_HASH_MISMATCH' }
        }
        if ((Get-GatewayManifestHash @(Get-GatewayInputs $taskCopyRoot)) -ne $taskHash) { throw 'COPY_MANIFEST_MISMATCH' }
        Write-GatewayJson (Join-Path $taskCopyRoot 'frozen-source.json') $taskFrozen
    }
    if ((Get-GatewayManifestHash @(Get-GatewayInputs $taskRoot)) -ne $taskHash) { throw 'INPUT_CHANGED' }
    foreach ($taskCopy in @('build-a', 'build-b')) {
        $taskStage = $taskCopy
        $taskCopyRoot = Join-Path $Destination $taskCopy
        $taskArgs = @('-NoProfile', '-File', (Join-Path $taskCopyRoot 'ops/verify.ps1'))
        if ($MavenRepository) { $taskArgs += @('-MavenRepository', $MavenRepository) }
        if ($Offline) { $taskArgs += '-Offline' }
        & (Join-Path $PSHOME 'pwsh.exe') @taskArgs *> (Join-Path $Destination ($taskCopy + '.log'))
        if ($LASTEXITCODE -ne 0) { throw 'BUILD_FAILED' }
        $taskReceipts = @(Get-ChildItem -LiteralPath (Join-Path $taskCopyRoot 'target/verification') -Filter 'receipt.json' -Recurse -File)
        if ($taskReceipts.Count -ne 1) { throw 'RECEIPT_COUNT_INVALID' }
        $taskReceipt = Get-Content -LiteralPath $taskReceipts[0].FullName -Raw -Encoding utf8 | ConvertFrom-Json
        if ($taskReceipt.status -ne 'PASS' -or -not $taskReceipt.completeAcceptance -or $taskReceipt.source.manifestSha256 -ne $taskHash) { throw 'RECEIPT_NOT_PASS' }
        $taskJar = Join-Path $taskCopyRoot ('target/' + $taskReceipt.jar.file)
        if ((Get-FileHash -LiteralPath $taskJar).Hash -ne $taskReceipt.jar.sha256) { throw 'ARTIFACT_HASH_MISMATCH' }
        $taskDelivery.builds += [ordered]@{
            directory = $taskCopy; receipt = [IO.Path]::GetRelativePath($Destination, $taskReceipts[0].FullName)
            receiptSha256 = (Get-FileHash -LiteralPath $taskReceipts[0].FullName).Hash
            jarSha256 = $taskReceipt.jar.sha256; runtime = $taskReceipt.runtime
        }
    }
    $taskStage = 'reproducibility'
    if ($taskDelivery.builds[0].jarSha256 -ne $taskDelivery.builds[1].jarSha256 -or
        ($taskDelivery.builds[0].runtime -join "`n") -ne ($taskDelivery.builds[1].runtime -join "`n")) { throw 'REPRODUCIBILITY_FAILED' }
    if ((Get-GatewayManifestHash @(Get-GatewayInputs $taskRoot)) -ne $taskHash) { throw 'INPUT_CHANGED' }
    $taskStage = 'delivery'
    $taskArtifact = Join-Path $Destination 'vireon-gateway-0.1.0-SNAPSHOT.jar'
    Copy-Item -LiteralPath (Join-Path $Destination 'build-a/target/vireon-gateway-0.1.0-SNAPSHOT.jar') -Destination $taskArtifact
    $taskArtifactHash = (Get-FileHash -LiteralPath $taskArtifact).Hash
    if ($taskArtifactHash -ne $taskDelivery.builds[0].jarSha256) { throw 'DELIVERY_HASH_MISMATCH' }
    "$taskArtifactHash  vireon-gateway-0.1.0-SNAPSHOT.jar" | Set-Content -LiteralPath (Join-Path $Destination 'SHA256SUMS') -Encoding utf8
    $taskDelivery.status = 'PASS'
} catch {
    $taskDelivery.failedStage = $taskStage
    throw "Gateway delivery failed at stage: $taskStage. Evidence retained at: $Destination"
} finally {
    $taskDelivery.finishedUtc = [datetime]::UtcNow.ToString('o')
    Write-GatewayJson (Join-Path $Destination 'delivery.json') $taskDelivery
    Write-Output "Gateway delivery evidence: $Destination"
}
