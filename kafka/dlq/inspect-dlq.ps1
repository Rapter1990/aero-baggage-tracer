<#
.SYNOPSIS
  Lists the dead letters on baggage.dead-letter.v1 so an operator can see what failed and why.
.DESCRIPTION
  Reads the topic from the beginning through the broker container (kafka-console-consumer, read only: it
  uses no consumer group and commits nothing), or reads saved lines from -InputFile (one envelope per line).
  Each line is the envelope of a DeadLetterPayload: originalTopic, originalEventId, reason, attempts and
  the original message as rawPayload text. rawPayload may hold personal data: it is shown only with -Raw.
.EXAMPLE
  powershell -File kafka/dlq/inspect-dlq.ps1
  powershell -File kafka/dlq/inspect-dlq.ps1 -OriginalTopic recovery.case.changed.v1 -Last 5
  powershell -File kafka/dlq/inspect-dlq.ps1 -EventId 7c1e... -Raw
#>
param(
  [string]$Container = 'kafka',
  [string]$Topic = 'baggage.dead-letter.v1',
  [string]$InputFile,
  [string]$OriginalTopic,
  [string]$EventId,
  [int]$Last = 0,
  [int]$TimeoutMs = 10000,
  [switch]$Raw
)
$ErrorActionPreference = 'Stop'

if ($InputFile) {
  $lines = Get-Content -Path $InputFile -Encoding UTF8
} else {
  # The console consumer ends with a timeout error on stderr by design (--timeout-ms). Windows PowerShell
  # turns native stderr into an error record, so stop-on-error is off for this one call.
  $ErrorActionPreference = 'Continue'
  $lines = docker exec $Container /opt/kafka/bin/kafka-console-consumer.sh `
    --bootstrap-server localhost:9092 --topic $Topic --from-beginning --timeout-ms $TimeoutMs 2>$null
  $ErrorActionPreference = 'Stop'
  if (-not $lines -and $LASTEXITCODE -gt 1) {
    throw "Could not read $Topic from container '$Container' (exit $LASTEXITCODE)"
  }
}

$letters = foreach ($line in $lines) {
  if ([string]::IsNullOrWhiteSpace($line) -or $line -notmatch '^\s*\{') { continue }
  try { $envelope = $line | ConvertFrom-Json } catch { Write-Warning "Skipped a line that is not JSON"; continue }
  $p = $envelope.payload
  if ($null -eq $p) { continue }
  [pscustomobject]@{
    PublishedAt     = $envelope.publishedAt
    OriginalTopic   = $p.originalTopic
    OriginalEventId = $p.originalEventId
    Attempts        = $p.attempts
    Reason          = $p.reason
    RawPayload      = $p.rawPayload
  }
}

if ($OriginalTopic) { $letters = $letters | Where-Object { $_.OriginalTopic -eq $OriginalTopic } }
if ($EventId)       { $letters = $letters | Where-Object { $_.OriginalEventId -eq $EventId } }
if ($Last -gt 0)    { $letters = $letters | Select-Object -Last $Last }

if (-not $letters) { "No dead letters" + $(if ($OriginalTopic -or $EventId) { ' match.' } else { '.' }); return }

if ($Raw) {
  $letters | Format-List PublishedAt, OriginalTopic, OriginalEventId, Attempts, Reason, RawPayload
} else {
  $letters | Select-Object PublishedAt, OriginalTopic, OriginalEventId, Attempts, Reason | Format-Table -AutoSize
  "$(@($letters).Count) dead letter(s). Add -Raw to see the original message (may contain personal data)."
}
