$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskDocs = @((Join-Path $taskRoot 'README.md'), (Join-Path $taskRoot 'AGENTS.md'))
$taskDocs += Get-ChildItem -LiteralPath (Join-Path $taskRoot 'docs') -Filter '*.md' -Recurse -File | Select-Object -ExpandProperty FullName
$taskDocs += Join-Path $PSScriptRoot 'README.md'
$taskLinks = 0
foreach ($taskDoc in $taskDocs) {
    $taskText = Get-Content -LiteralPath $taskDoc -Raw -Encoding utf8
    if ($taskText -match '(?m)[\t ]+$') { throw "Trailing whitespace: $taskDoc" }
    foreach ($taskMatch in [regex]::Matches($taskText, '\]\(([^)]+)\)')) {
        $taskLink = $taskMatch.Groups[1].Value
        if ($taskLink -match '^https?://' -or $taskLink.StartsWith('#')) { continue }
        $taskTarget = Join-Path (Split-Path -Parent $taskDoc) $taskLink.Split('#')[0]
        if (-not (Test-Path -LiteralPath $taskTarget)) { throw "Broken local link: $taskDoc -> $taskLink" }
        $taskLinks++
    }
}
"Document local links passed: $taskLinks (external URLs and anchors not checked)"
