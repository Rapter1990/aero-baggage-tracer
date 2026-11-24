<#
.SYNOPSIS
  Publishes the replay dataset (late, duplicate, out-of-order scans) to Kafka through the broker container.
#>
param(
  [string]$Container = 'kafka',
  [string]$Topic = 'baggage.scan.received.v1',
  [string]$File = (Join-Path $PSScriptRoot 'scan-replay.ndjson'),
  [switch]$DryRun
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path $File)) { throw "Dataset not found: $File" }
$lines = Get-Content -Path $File -Encoding UTF8 | Where-Object { $_.Trim() -ne '' }
foreach ($line in $lines) {
  if ($line -notmatch '^[^|]+\|\{.*\}$') { throw "Not a '<key>|<json>' line: $line" }
}
if ($DryRun) {
  $lines | ForEach-Object { "[dry-run] $Topic <- $_" }
  return
}
$lines -join "`n" | docker exec -i $Container /opt/kafka/bin/kafka-console-producer.sh `
  --bootstrap-server localhost:9092 --topic $Topic `
  --property parse.key=true --property 'key.separator=|'
if ($LASTEXITCODE -ne 0) { throw "kafka-console-producer failed ($LASTEXITCODE)" }
"Sent $($lines.Count) records to $Topic"
