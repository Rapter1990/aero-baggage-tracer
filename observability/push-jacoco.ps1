# Pushes the JaCoCo coverage of every module to the Prometheus Pushgateway, so the Grafana
# dashboard "AeroTrace - Coverage" can chart it.
#
#   mvn verify                                  # writes <module>/target/site/jacoco/jacoco.csv
#   ./observability/push-jacoco.ps1             # pushes them (Pushgateway on localhost:9091)
#
# Each module is pushed as its own group (job=jacoco, module=<name>), so running the script again
# replaces that module's numbers. Per class and counter it pushes two gauges:
#   jacoco_covered{module, package, class, counter}   and   jacoco_missed{...}
# where counter is one of instruction, branch, line, complexity, method.
param(
  [string] $PushgatewayUrl = $(if ($env:PUSHGATEWAY_URL) { $env:PUSHGATEWAY_URL } else { 'http://localhost:9091' }),
  [string] $Root = (Join-Path $PSScriptRoot '..')
)

$ErrorActionPreference = 'Stop'
$counters = 'INSTRUCTION', 'BRANCH', 'LINE', 'COMPLEXITY', 'METHOD'

function Format-Label([string] $value) {
  $value.Replace('\', '\\').Replace('"', '\"').Replace("`n", '\n')
}

$reports = Get-ChildItem -Path $Root -Directory |
  ForEach-Object { Join-Path $_.FullName 'target/site/jacoco/jacoco.csv' } |
  Where-Object { Test-Path $_ }

if (-not $reports) {
  Write-Error "No jacoco.csv found under $Root. Run 'mvn verify' first."
  exit 1
}

foreach ($report in $reports) {
  # <module>/target/site/jacoco/jacoco.csv -> <module>
  $module = Split-Path (Split-Path (Split-Path (Split-Path (Split-Path $report -Parent) -Parent) -Parent) -Parent) -Leaf
  $rows = Import-Csv -Path $report
  $lines = New-Object System.Collections.Generic.List[string]
  $lines.Add('# TYPE jacoco_covered gauge')
  $lines.Add('# TYPE jacoco_missed gauge')
  foreach ($row in $rows) {
    $labels = 'package="{0}",class="{1}"' -f (Format-Label $row.PACKAGE), (Format-Label $row.CLASS)
    foreach ($counter in $counters) {
      $name = $counter.ToLowerInvariant()
      $lines.Add(('jacoco_covered{{{0},counter="{1}"}} {2}' -f $labels, $name, $row."${counter}_COVERED"))
      $lines.Add(('jacoco_missed{{{0},counter="{1}"}} {2}' -f $labels, $name, $row."${counter}_MISSED"))
    }
  }
  $body = ($lines -join "`n") + "`n"

  $url = '{0}/metrics/job/jacoco/module/{1}' -f $PushgatewayUrl.TrimEnd('/'), [uri]::EscapeDataString($module)
  Invoke-RestMethod -Method Put -Uri $url -ContentType 'text/plain; version=0.0.4' -Body $body | Out-Null

  $covered = ($rows | Measure-Object -Property LINE_COVERED -Sum).Sum
  $missed = ($rows | Measure-Object -Property LINE_MISSED -Sum).Sum
  $total = $covered + $missed
  $percent = if ($total -gt 0) { [math]::Round(100 * $covered / $total, 1) } else { 0 }
  Write-Host ("{0,-20} {1,5} classes, line coverage {2}%" -f $module, @($rows).Count, $percent.ToString([cultureinfo]::InvariantCulture))
}
