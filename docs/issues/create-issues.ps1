param(
    [string]$Repo = "Rapter1990/aero-baggage-tracer",
    [string]$File = "$PSScriptRoot\GITHUB_ISSUES.md",
    [switch]$DryRun
)
# Splits GITHUB_ISSUES.md into issues: each "## #N Title" section up to the next "---".
$text = Get-Content $File -Raw -Encoding UTF8
$sections = [regex]::Matches($text, '(?ms)^## #(?<num>\d+) (?<title>[^\r\n]+)\r?\n(?<body>.*?)(?=^---\s*$|\z)')
foreach ($s in $sections) {
    # Issue titles follow the "Issue #N : Title" convention (create-pr.ps1 relies on it).
    $title = "Issue #$($s.Groups['num'].Value) : " + $s.Groups['title'].Value.Trim()
    $body  = $s.Groups['body'].Value.Trim()
    if ($DryRun) { Write-Host "[dry-run] $title"; continue }
    $tmp = New-TemporaryFile
    Set-Content -Path $tmp -Value $body -Encoding UTF8
    gh issue create -R $Repo --title $title --body-file $tmp
    Remove-Item $tmp
}
