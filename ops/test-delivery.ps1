#requires -Version 7.4
# Synthetic negative evidence is confined to a new target directory; never a real build receipt.
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/delivery-common.ps1"
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskFixture = Join-Path $taskRoot ('target/delivery-tests/' + [guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $taskFixture
$taskPassed = 0
function Assert-Rejected([scriptblock]$Action, [string]$Code) {
    try { & $Action; throw 'EXPECTED_REJECTION_NOT_OBSERVED' }
    catch { if ($_.Exception.Message -notlike "*$Code*") { throw } }
}
$taskReports = Join-Path $taskFixture 'reports'
$null = New-Item -ItemType Directory -Path $taskReports
Assert-Rejected { Read-GatewayReports $taskReports ([datetime]::UtcNow) } 'REPORTS_MISSING'
$taskPassed++
$taskXml = Join-Path $taskReports 'TEST-synthetic.xml'
$taskStarted = [datetime]::UtcNow.AddSeconds(-2)
'<testsuite name="synthetic.Suite" tests="1" failures="0" errors="0" skipped="0"><testcase name="synthetic"/></testsuite>' | Set-Content -LiteralPath $taskXml
$taskCounts = Read-GatewayReports $taskReports $taskStarted
if ($taskCounts.tests -ne 1 -or $taskCounts.reports.Count -ne 1) { throw 'COUNTS_INVALID' }
$taskPassed++
(Get-Item -LiteralPath $taskXml).LastWriteTimeUtc = $taskStarted.AddMinutes(-1)
Assert-Rejected { Read-GatewayReports $taskReports $taskStarted } 'REPORT_STALE'
$taskPassed++
foreach ($taskField in @('failures', 'errors', 'skipped')) {
    $taskAttrs = [ordered]@{ tests = 1; failures = 0; errors = 0; skipped = 0 }
    $taskAttrs[$taskField] = 1
    $taskText = '<testsuite ' + (($taskAttrs.GetEnumerator() | ForEach-Object { $_.Key + '="' + $_.Value + '"' }) -join ' ') + '><testcase/></testsuite>'
    $taskText | Set-Content -LiteralPath $taskXml
    Assert-Rejected { Read-GatewayReports $taskReports $taskStarted } 'REPORT_NOT_PASS'
    $taskPassed++
}
'<testsuite tests="2" failures="0" errors="0" skipped="0"><testcase/></testsuite>' | Set-Content -LiteralPath $taskXml
Assert-Rejected { Read-GatewayReports $taskReports $taskStarted } 'REPORT_INVALID'
$taskPassed++
# Exercise the actual verify entrypoint in isolated fixture copies, with an explicitly synthetic wrapper.
# This checks exit/stage/completeAcceptance, not Maven or Gateway correctness.
foreach ($taskCase in @('maven-failure', 'missing-reports', 'stale-reports', 'documents-failure', 'artifact-missing', 'artifact-mismatch', 'success')) {
    $taskCaseRoot = Join-Path $taskFixture $taskCase
    foreach ($taskDir in @('ops', 'src', 'docs', '.mvn')) { $null = New-Item -ItemType Directory -Path (Join-Path $taskCaseRoot $taskDir) -Force }
    foreach ($taskFile in @('verify.ps1', 'delivery-common.ps1')) { Copy-Item -LiteralPath (Join-Path $PSScriptRoot $taskFile) -Destination (Join-Path $taskCaseRoot ('ops/' + $taskFile)) }
    foreach ($taskFile in @('AGENTS.md', 'README.md', '.gitignore', 'pom.xml', 'mvnw')) { 'Synthetic fixture only' | Set-Content -LiteralPath (Join-Path $taskCaseRoot $taskFile) }
    if ($taskCase -eq 'documents-failure') { "throw 'SYNTHETIC_DOC_FAILURE'" | Set-Content -LiteralPath (Join-Path $taskCaseRoot 'ops/check-docs.ps1') }
    else { "'Synthetic docs PASS'" | Set-Content -LiteralPath (Join-Path $taskCaseRoot 'ops/check-docs.ps1') }
    $taskFake = @'
param([Parameter(ValueFromRemainingArguments=$true)][string[]]$Arguments)
if ($env:MAVEN_SKIP_RC -ne '1') { exit 8 }
if ($Arguments -contains '--version') { 'Apache Maven synthetic'; 'Java version: synthetic'; 'OS name: synthetic'; exit 0 }
$caseName = Split-Path -Leaf $PSScriptRoot
if ($caseName -eq 'maven-failure') { exit 7 }
$reportRoot = (Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'target/verification') -Directory | Select-Object -First 1).FullName
if ($caseName -eq 'missing-reports') { exit 0 }
foreach ($group in @('surefire-reports', 'failsafe-reports')) {
    $folder = Join-Path $reportRoot $group
    $null = New-Item -ItemType Directory -Path $folder -Force
    $file = Join-Path $folder 'TEST-synthetic.xml'
    '<testsuite name="synthetic.Suite" tests="1" failures="0" errors="0" skipped="0"><testcase/></testsuite>' | Set-Content -LiteralPath $file
    if ($caseName -eq 'stale-reports') { (Get-Item -LiteralPath $file).LastWriteTimeUtc = [datetime]::UtcNow.AddDays(-1) }
}
'SYNTHETIC NOT A JAR' | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'target/vireon-gateway-0.1.0-SNAPSHOT.jar')
if ($caseName -ne 'artifact-missing') {
    $hash = (Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'target/vireon-gateway-0.1.0-SNAPSHOT.jar')).Hash
    if ($caseName -eq 'artifact-mismatch') { $hash = 'A' * 64 }
    $hash | Set-Content -LiteralPath (Join-Path $reportRoot 'failsafe-reports/tested-artifact.sha256')
}
(Get-Item -LiteralPath (Join-Path $PSScriptRoot 'target/vireon-gateway-0.1.0-SNAPSHOT.jar')).LastWriteTimeUtc = [datetime]::UtcNow.AddDays(-1)
'@
    $taskFake | Set-Content -LiteralPath (Join-Path $taskCaseRoot 'synthetic-wrapper.ps1')
    ('@"' + (Join-Path $PSHOME 'pwsh.exe') + '" -NoProfile -File "%~dp0synthetic-wrapper.ps1" %*') | Set-Content -LiteralPath (Join-Path $taskCaseRoot 'mvnw.cmd')
    & (Join-Path $PSHOME 'pwsh.exe') -NoProfile -File (Join-Path $taskCaseRoot 'ops/verify.ps1') *> (Join-Path $taskCaseRoot 'invocation.log')
    $taskExit = $LASTEXITCODE
    $taskReceipts = @(Get-ChildItem -LiteralPath (Join-Path $taskCaseRoot 'target/verification') -Filter 'receipt.json' -Recurse -File)
    if ($taskReceipts.Count -ne 1) { throw "RECEIPT_MISSING: $taskCase" }
    $taskResult = Get-Content -LiteralPath $taskReceipts[0].FullName -Raw | ConvertFrom-Json
    if ($taskCase -eq 'success') {
        if ($taskExit -ne 0 -or $taskResult.status -ne 'PASS' -or -not $taskResult.completeAcceptance) { throw 'SYNTHETIC_SUCCESS_FAILED' }
    } else {
        $taskExpectedStage = switch -Wildcard ($taskCase) { 'maven-failure' { 'maven' } 'documents-failure' { 'documents' } 'artifact-*' { 'artifact' } default { 'reports' } }
        if ($taskExit -eq 0 -or $taskResult.status -ne 'FAIL' -or $taskResult.completeAcceptance -or $taskResult.stages.$taskExpectedStage -ne 'FAIL') { throw "FAILURE_NOT_RECORDED: $taskCase" }
    }
    $taskPassed++
}
$taskMarker = 'synthetic-' + [guid]::NewGuid().ToString('N')
foreach ($taskVariable in @('MAVEN_OPTS', 'MAVEN_ARGS', 'MAVEN_CONFIG', 'MAVEN_DEBUG_OPTS', 'MAVEN_BASEDIR', 'JVM_CONFIG_MAVEN_PROPS', 'MAVEN_BATCH_ECHO', 'MAVEN_BATCH_PAUSE', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS')) {
    $taskPrevious = [Environment]::GetEnvironmentVariable($taskVariable)
    try {
        [Environment]::SetEnvironmentVariable($taskVariable, ('-Dtest=OnlyOne -Dpassword=' + $taskMarker))
        $taskPolicyLog = Join-Path $taskFixture ($taskVariable + '.log')
        & (Join-Path $PSHOME 'pwsh.exe') -NoProfile -File (Join-Path $taskCaseRoot 'ops/verify.ps1') *> $taskPolicyLog
        if ($LASTEXITCODE -eq 0) { throw 'OPTIONS_ACCEPTED' }
        $taskPolicyReceipt = Get-ChildItem -LiteralPath (Join-Path $taskCaseRoot 'target/verification') -Filter 'receipt.json' -Recurse -File | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
        $taskPolicyText = Get-Content -LiteralPath $taskPolicyReceipt.FullName -Raw
        $taskPolicyResult = $taskPolicyText | ConvertFrom-Json
        if ($taskPolicyResult.stages.'invocation-policy' -ne 'FAIL' -or $taskPolicyResult.completeAcceptance) { throw 'OPTIONS_NOT_RECORDED' }
        if ($taskPolicyText.Contains($taskMarker) -or (Get-Content -LiteralPath $taskPolicyLog -Raw).Contains($taskMarker)) { throw 'OPTION_VALUE_LEAKED' }
        $taskPassed++
    } finally { [Environment]::SetEnvironmentVariable($taskVariable, $taskPrevious) }
}
Write-GatewayJson (Join-Path $taskFixture 'results.json') ([ordered]@{ scope = 'synthetic-script-regression'; passed = $taskPassed })
"Delivery script regression: $taskPassed passed. Evidence: $taskFixture"
