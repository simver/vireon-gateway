# Shared, local-only evidence helpers. Never serializes XML properties, output or environment values.
Set-StrictMode -Version Latest

function Get-GatewayInputs([string]$Root) {
    $paths = @('AGENTS.md', 'README.md', '.gitignore', 'pom.xml', 'mvnw', 'mvnw.cmd')
    foreach ($directory in @('src', 'ops', 'docs', '.mvn')) {
        $pending = [System.Collections.Generic.Queue[string]]::new()
        $pending.Enqueue((Join-Path $Root $directory))
        while ($pending.Count -gt 0) {
            $current = Get-Item -LiteralPath $pending.Dequeue() -Force
            if ($current.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'INPUT_REPARSE_POINT' }
            foreach ($item in Get-ChildItem -LiteralPath $current.FullName -Force) {
                if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'INPUT_REPARSE_POINT' }
                if ($item.Name -match '^(target|out|\.git|\.env.*)$' -or $item.Extension -match '^\.(key|p12|pfx|log|class)$') {
                    throw 'UNEXPECTED_INPUT'
                }
                if ($item.PSIsContainer) { $pending.Enqueue($item.FullName) }
                else { $paths += [IO.Path]::GetRelativePath($Root, $item.FullName).Replace('\', '/') }
            }
        }
    }
    foreach ($relative in ($paths | Sort-Object -CaseSensitive)) {
        $file = Get-Item -LiteralPath (Join-Path $Root $relative) -Force
        if ($file.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'INPUT_REPARSE_POINT' }
        [ordered]@{ path = $relative; sha256 = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash }
    }
}

function Get-GatewayManifestHash($Inputs) {
    $canonical = ($Inputs | ForEach-Object { $_.sha256 + '  ' + $_.path }) -join "`n"
    [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($canonical)))
}

function Read-GatewayReports([string]$Directory, [datetime]$Started) {
    if (-not (Test-Path -LiteralPath $Directory)) { throw 'REPORTS_MISSING' }
    $files = @(Get-ChildItem -LiteralPath $Directory -Filter 'TEST-*.xml' -File)
    if ($files.Count -eq 0) { throw 'REPORTS_MISSING' }
    $counts = [ordered]@{ tests = 0; failures = 0; errors = 0; skipped = 0; reports = @() }
    foreach ($file in $files) {
        if ($file.LastWriteTimeUtc -lt $Started.ToUniversalTime()) { throw 'REPORT_STALE' }
        $settings = [Xml.XmlReaderSettings]::new()
        $settings.DtdProcessing = [Xml.DtdProcessing]::Prohibit
        $settings.XmlResolver = $null
        $reader = [Xml.XmlReader]::Create($file.FullName, $settings)
        try {
            $xml = [Xml.XmlDocument]::new()
            $xml.XmlResolver = $null
            $xml.Load($reader)
            if ($xml.DocumentElement.get_Name() -ne 'testsuite') { throw 'REPORT_INVALID' }
            foreach ($field in @('tests', 'failures', 'errors', 'skipped')) {
                $value = $xml.DocumentElement.GetAttribute($field)
                if ($value -notmatch '^\d+$') { throw 'REPORT_INVALID' }
                $counts[$field] += [int]$value
            }
            if ($xml.SelectNodes('/testsuite/testcase').Count -ne [int]$xml.DocumentElement.GetAttribute('tests')) { throw 'REPORT_INVALID' }
            $counts.reports += [ordered]@{ file = $file.Name; sha256 = (Get-FileHash -LiteralPath $file.FullName).Hash }
        } finally { $reader.Dispose() }
    }
    if ($counts.tests -eq 0 -or $counts.failures -ne 0 -or $counts.errors -ne 0 -or $counts.skipped -ne 0) { throw 'REPORT_NOT_PASS' }
    $counts
}

function Write-GatewayJson([string]$Path, $Value) {
    $Value | ConvertTo-Json -Depth 15 | Set-Content -LiteralPath $Path -Encoding utf8
}
