param(
    [string]$Repo = "Rapter1990/aero-baggage-tracer",
    [string]$File = "$PSScriptRoot\GITHUB_ISSUES.md",
    [string]$Base = "main",
    [string]$Branch,
    [string]$Label = "enhancement",
    [switch]$Draft,
    [switch]$DryRun
)
# Creates a pull request for the current feature/<N>/<slug> branch.
#   Title : "PR#<N> : <issue title from GITHUB_ISSUES.md>"
#   Body  : summary, "Closes #<GitHub issue number>", commit list, acceptance criteria.
# <N> is the backlog number in GITHUB_ISSUES.md. The GitHub issue number can differ (pull requests share
# the numbering), so the script finds the issue titled "Issue #<N> : ..." and closes that one.
# Usage: .\docs\issues\create-pr.ps1 [-DryRun] [-Draft]

$ErrorActionPreference = "Stop"
$ghDir = "$env:USERPROFILE\tools\gh\bin"
if (-not (Get-Command gh -ErrorAction SilentlyContinue) -and (Test-Path "$ghDir\gh.exe")) { $env:PATH = "$ghDir;$env:PATH" }

if (-not $Branch) { $Branch = (git rev-parse --abbrev-ref HEAD).Trim() }
if ($Branch -notmatch '^feature/(?<n>\d+)/') { throw "Branch '$Branch' must look like feature/<issue-number>/<slug>." }
$n = [int]$Matches['n']

$text = Get-Content $File -Raw -Encoding UTF8
$m = [regex]::Match($text, "(?ms)^## #$n (?<title>[^\r\n]+)\r?\n(?<body>.*?)(?=^---\s*`$|\z)")
if (-not $m.Success) { throw "Issue #$n not found in $File." }
$issueTitle = $m.Groups['title'].Value.Trim()
$section = $m.Groups['body'].Value

$desc = [regex]::Match($section, '(?s)\*\*Description\*\*\s*(.*?)\s*\*\*Scope\*\*')
$summary = if ($desc.Success) { $desc.Groups[1].Value.Trim() } else { $issueTitle }
$crit = [regex]::Match($section, '(?s)\*\*Acceptance criteria\*\*\s*(.*)$')
$criteria = if ($crit.Success) { $crit.Groups[1].Value.Trim() } else { "- [ ] See issue #$n." }

# Find the GitHub issue number (title "Issue #<n> : ...").
$closes = $null
try {
    $issues = gh issue list -R $Repo --state all --limit 200 --json number,title | ConvertFrom-Json
    $hit = $issues | Where-Object { $_.title -match "^Issue #$n\b" } | Select-Object -First 1
    if ($hit) { $closes = $hit.number }
} catch { Write-Warning "Could not look up the GitHub issue: $($_.Exception.Message)" }
if (-not $closes) { Write-Warning "No GitHub issue titled 'Issue #$n : ...' found; the PR will not close an issue." }

$commits = (git log "origin/$Base..HEAD" --no-merges --pretty="- %s") -join "`n"
if (-not $commits) { $commits = "- (no commits ahead of $Base)" }

$title = "PR#$n : $issueTitle"
$closeLine = if ($closes) { "Closes #$closes" } else { "Related to backlog issue #$n" }
$body = @"
## Summary
$summary

$closeLine

## Changes
$commits

## Acceptance criteria
$criteria
"@

if ($DryRun) {
    Write-Host "[dry-run] branch: $Branch -> $Base"
    Write-Host "[dry-run] title : $title"
    Write-Host "----- body -----"
    Write-Host $body
    return
}

git push -u origin $Branch
$existing = gh pr list -R $Repo --head $Branch --state open --json number | ConvertFrom-Json
if ($existing) { throw "An open PR already exists for $Branch (#$($existing[0].number))." }

$tmp = New-TemporaryFile
Set-Content -Path $tmp -Value $body -Encoding UTF8
$prArgs = @("pr", "create", "-R", $Repo, "--base", $Base, "--head", $Branch, "--title", $title, "--body-file", $tmp, "--label", $Label)
if ($Draft) { $prArgs += "--draft" }
gh @prArgs
Remove-Item $tmp
