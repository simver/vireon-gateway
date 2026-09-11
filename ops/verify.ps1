param(
    [ValidateSet('compile', 'test', 'verify')][string]$Phase = 'verify',
    [string]$MavenRepository,
    [switch]$Format
)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $taskRoot
try {
    if (-not $env:JAVA_HOME) {
        $taskJava = (Get-Command java -ErrorAction Stop).Source
        $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $taskJava)
    }
    if (-not $env:MAVEN_USER_HOME) {
        $env:MAVEN_USER_HOME = Join-Path $env:USERPROFILE '.m2'
    }
    $taskArgs = @()
    if ($MavenRepository) { $taskArgs += "-Dmaven.repo.local=$MavenRepository" }
    if ($Format) {
        & "$taskRoot/mvnw.cmd" @taskArgs spotless:apply
        if ($LASTEXITCODE -ne 0) { throw "Formatting failed: $LASTEXITCODE" }
    }
    & "$taskRoot/mvnw.cmd" @taskArgs $Phase
    if ($LASTEXITCODE -ne 0) { throw "Maven $Phase failed: $LASTEXITCODE" }
    & "$PSScriptRoot/check-docs.ps1"
} finally {
    Pop-Location
}
