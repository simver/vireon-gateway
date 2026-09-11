#requires -Version 7.4
param(
    [ValidateSet('compile', 'test', 'verify')][string]$Phase = 'verify',
    [string]$MavenRepository,
    [switch]$Format,
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
. "$PSScriptRoot/delivery-common.ps1"
$taskRun = Join-Path $taskRoot ('target/verification/' + [guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $taskRun
$taskReceiptPath = Join-Path $taskRun 'receipt.json'
$taskReceipt = [ordered]@{
    schema = 1; runId = Split-Path -Leaf $taskRun; phase = $Phase
    startedUtc = [datetime]::UtcNow.ToString('o'); status = 'FAIL'; completeAcceptance = $false
    networkMode = $(if ($Offline) { 'offline' } else { 'online-allowed' })
    stages = [ordered]@{}; source = $null; runtime = $null; tests = $null; jar = $null
}
$taskStage = 'initialize'
$taskOriginalSkipRc = [Environment]::GetEnvironmentVariable('MAVEN_SKIP_RC')
Push-Location -LiteralPath $taskRoot
try {
    $taskStage = 'invocation-policy'
    # Reject external option injection before the first JVM can print it or bypass a gate.
    foreach ($taskVariable in @('MAVEN_OPTS', 'MAVEN_ARGS', 'MAVEN_CONFIG', 'MAVEN_DEBUG_OPTS', 'MAVEN_BASEDIR', 'JVM_CONFIG_MAVEN_PROPS', 'MAVEN_BATCH_ECHO', 'MAVEN_BATCH_PAUSE', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS')) {
        if (-not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($taskVariable))) { throw 'EXTERNAL_OPTIONS_NOT_ALLOWED' }
    }
    $env:MAVEN_SKIP_RC = '1'
    if ($MavenRepository -and -not [IO.Path]::IsPathFullyQualified($MavenRepository)) { throw 'MAVEN_REPOSITORY_MUST_BE_ABSOLUTE' }
    $taskJvmConfig = Join-Path $taskRoot '.mvn/jvm.config'
    if ((Test-Path -LiteralPath $taskJvmConfig) -and -not [string]::IsNullOrWhiteSpace((Get-Content -LiteralPath $taskJvmConfig -Raw))) { throw 'JVM_OPTIONS_NOT_ALLOWED' }
    $taskMavenConfig = Join-Path $taskRoot '.mvn/maven.config'
    if (Test-Path -LiteralPath $taskMavenConfig) {
        $taskConfigLines = @(Get-Content -LiteralPath $taskMavenConfig | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
        if (($taskConfigLines -join "`n") -ne "--settings`n.mvn/settings.xml`n--global-settings`n.mvn/settings.xml`n--no-transfer-progress") { throw 'MAVEN_CONFIG_NOT_APPROVED' }
    }
    $taskReceipt.stages[$taskStage] = 'PASS'
    if (-not $env:JAVA_HOME) {
        $taskJava = (Get-Command java -ErrorAction Stop).Source
        $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $taskJava)
    }
    if (-not $env:MAVEN_USER_HOME) {
        $env:MAVEN_USER_HOME = Join-Path $env:USERPROFILE '.m2'
    }
    $taskArgs = @()
    if ($MavenRepository) { $taskArgs += "-Dmaven.repo.local=$MavenRepository" }
    if ($Offline) { $taskArgs += '--offline' }
    $taskStage = 'runtime'
    $taskRuntime = @(& "$taskRoot/mvnw.cmd" @taskArgs --version 2>&1)
    if ($LASTEXITCODE -ne 0) { throw 'RUNTIME_FAILED' }
    # Only standard version lines; no environment dump or arbitrary JVM option lines.
    $taskReceipt.runtime = @($taskRuntime | ForEach-Object { "$_" -replace '\x1b\[[0-9;]*m', '' } | Where-Object { $_ -match '^(Apache Maven |Java version:|OS name:)' })
    if ($taskReceipt.runtime.Count -lt 3) { throw 'RUNTIME_MISSING' }
    $taskReceipt.stages[$taskStage] = 'PASS'
    if ($Format) {
        $taskStage = 'format'
        & "$taskRoot/mvnw.cmd" @taskArgs spotless:apply *> (Join-Path $taskRun 'format.log')
        if ($LASTEXITCODE -ne 0) { throw "Formatting failed: $LASTEXITCODE" }
        $taskReceipt.stages[$taskStage] = 'PASS'
    }
    $taskStage = 'source'
    $taskInputs = @(Get-GatewayInputs $taskRoot)
    $taskInputHash = Get-GatewayManifestHash $taskInputs
    $taskSource = [ordered]@{ head = $null; dirty = $null; origin = 'directory'; manifestSha256 = $taskInputHash; files = $taskInputs }
    if (Test-Path -LiteralPath (Join-Path $taskRoot '.git')) {
        $taskHead = & git -C $taskRoot rev-parse HEAD
        if ($LASTEXITCODE -ne 0) { throw 'GIT_HEAD_FAILED' }
        $taskStatus = @(& git -C $taskRoot status --porcelain=v1 --untracked-files=all)
        if ($LASTEXITCODE -ne 0) { throw 'GIT_STATUS_FAILED' }
        $taskSource.head = $taskHead
        $taskSource.dirty = $taskStatus.Count -gt 0
        $taskSource.origin = 'git-workspace'
    } elseif (Test-Path -LiteralPath (Join-Path $taskRoot 'frozen-source.json')) {
        $taskFrozen = Get-Content -LiteralPath (Join-Path $taskRoot 'frozen-source.json') -Raw -Encoding utf8 | ConvertFrom-Json
        if ($taskFrozen.manifestSha256 -ne $taskInputHash) { throw 'FROZEN_INPUT_MISMATCH' }
        $taskSource.head = $taskFrozen.head
        $taskSource.dirty = $taskFrozen.dirty
        $taskSource.origin = 'frozen-copy'
    }
    $taskReceipt.source = $taskSource
    $taskReceipt.stages[$taskStage] = 'PASS'
    $taskStage = 'maven'
    $taskMavenStarted = [datetime]::UtcNow
    & "$taskRoot/mvnw.cmd" @taskArgs "-Dgateway.verificationDirectory=$taskRun" $Phase *> (Join-Path $taskRun 'maven.log')
    if ($LASTEXITCODE -ne 0) { throw "Maven $Phase failed: $LASTEXITCODE" }
    $taskReceipt.stages[$taskStage] = 'PASS'
    $taskStage = 'reports'
    if ($Phase -ne 'compile') {
        $taskTests = [ordered]@{ surefire = Read-GatewayReports (Join-Path $taskRun 'surefire-reports') $taskMavenStarted }
        if ($Phase -eq 'verify') { $taskTests.failsafe = Read-GatewayReports (Join-Path $taskRun 'failsafe-reports') $taskMavenStarted }
        $taskReceipt.tests = $taskTests
        $taskReceipt.stages[$taskStage] = 'PASS'
    } else { $taskReceipt.stages[$taskStage] = 'NOT_APPLICABLE' }
    $taskStage = 'documents'
    & "$PSScriptRoot/check-docs.ps1" *> (Join-Path $taskRun 'documents.log')
    $taskReceipt.stages[$taskStage] = 'PASS'
    $taskStage = 'input-integrity'
    if ((Get-GatewayManifestHash @(Get-GatewayInputs $taskRoot)) -ne $taskInputHash) { throw 'INPUT_CHANGED' }
    $taskReceipt.stages[$taskStage] = 'PASS'
    $taskStage = 'artifact'
    if ($Phase -eq 'verify') {
        $taskJar = Get-Item -LiteralPath (Join-Path $taskRoot 'target/vireon-gateway-0.1.0-SNAPSHOT.jar')
        # Boot applies outputTimestamp to the archive file itself. Bind bytes observed by JAR tests,
        # not its intentionally historical filesystem timestamp.
        $taskAttestation = Get-Item -LiteralPath (Join-Path $taskRun 'failsafe-reports/tested-artifact.sha256')
        if ($taskAttestation.LastWriteTimeUtc -lt $taskMavenStarted) { throw 'ARTIFACT_EVIDENCE_STALE' }
        $taskTestedHash = (Get-Content -LiteralPath $taskAttestation.FullName -Raw -Encoding utf8).Trim()
        $taskJarHash = (Get-FileHash -LiteralPath $taskJar.FullName).Hash
        if ($taskTestedHash -notmatch '^[A-F0-9]{64}$' -or $taskTestedHash -ne $taskJarHash) { throw 'TESTED_ARTIFACT_MISMATCH' }
        $taskReceipt.jar = [ordered]@{ file = $taskJar.Name; sha256 = $taskJarHash; testedSha256 = $taskTestedHash }
        $taskReceipt.completeAcceptance = $true
        $taskReceipt.stages[$taskStage] = 'PASS'
    } else { $taskReceipt.stages[$taskStage] = 'NOT_APPLICABLE' }
    $taskReceipt.status = 'PASS'
} catch {
    $taskReceipt.stages[$taskStage] = 'FAIL'
    # Fixed stage identifier only; exception messages may include rejected inputs.
    throw "Gateway verification failed at stage: $taskStage. Evidence: $taskReceiptPath"
} finally {
    [Environment]::SetEnvironmentVariable('MAVEN_SKIP_RC', $taskOriginalSkipRc)
    $taskReceipt.finishedUtc = [datetime]::UtcNow.ToString('o')
    Write-GatewayJson $taskReceiptPath $taskReceipt
    Pop-Location
    Write-Output "Gateway receipt: $taskReceiptPath"
}
