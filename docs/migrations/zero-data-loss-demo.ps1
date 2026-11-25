<#
.SYNOPSIS
  Shows that the journey-service scan_source migration (V2 + V3) loses no row and changes no value.
.DESCRIPTION
  Starts a throwaway PostgreSQL container, applies the first four journey-service changesets (V1),
  loads legacy rows, records a row count and a checksum per table, applies V2 (005, add the nullable
  column and backfill), validates, applies V3 (006, NOT NULL), and compares the checksums. The checksum
  leaves out the new column, so any difference in an old value shows up. The container is removed at the
  end unless -Keep is given.

  The changeset files are applied with psql, in order, so the demo needs only Docker and the
  postgres:18-alpine image. The V3 precondition (no row without a source) is run by this script as an
  explicit validation step. Liquibase itself, with its precondition and its changelog table, is proved
  by AT-17 in ScanSourceMigrationTest (mvn -pl journey-service test -Dtest=ScanSourceMigrationTest).
.EXAMPLE
  powershell -File docs/migrations/zero-data-loss-demo.ps1
  powershell -File docs/migrations/zero-data-loss-demo.ps1 -Bags 5000 -Keep
#>
param(
  [int]$Bags = 1000,
  [string]$Image = 'postgres:18-alpine',
  [string]$Name = 'aerotrace-migration-demo',
  [switch]$Keep
)
$ErrorActionPreference = 'Stop'
# Windows PowerShell 5.1 pipes text to native commands as ASCII unless told otherwise.
$OutputEncoding = [Text.UTF8Encoding]::new($false)
$root = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$changes = Join-Path $root 'journey-service\src\main\resources\db\changelog\changes'
$scansPerBag = 3

function Invoke-Sql([string]$Sql) {
  $out = $Sql | docker exec -i $Name psql -U postgres -d demo -v ON_ERROR_STOP=1 -At -q
  if ($LASTEXITCODE -ne 0) { throw "psql failed: $Sql" }
  return $out
}

function Invoke-SqlFile([string]$File) {
  Write-Host "  applying $(Split-Path $File -Leaf)"
  Get-Content -Raw -Encoding UTF8 $File | docker exec -i $Name psql -U postgres -d demo -v ON_ERROR_STOP=1 --single-transaction -q | Out-Null
  if ($LASTEXITCODE -ne 0) { throw "psql failed on $File" }
}

# Row count and checksum per table, over every column except the new one.
function Get-Snapshot {
  $tables = @{ baggage = 'id'; itinerary = 'id'; itinerary_leg = 'id'; scan_audit = 'id'; outbox_event = 'id'; processed_event = 'event_id' }
  $result = [ordered]@{}
  foreach ($t in ($tables.Keys | Sort-Object)) {
    $order = $tables[$t]
    $row = Invoke-Sql "SELECT count(*) || '|' || coalesce(md5(string_agg((to_jsonb(x) - 'scan_source')::text, '|' ORDER BY $order)), '') FROM journey.$t x"
    $result[$t] = $row.Trim()
  }
  return $result
}

# Windows PowerShell turns native stderr into an error record; a missing container is not an error here.
& { $ErrorActionPreference = 'Continue'; docker rm -f $Name 2>&1 | Out-Null }
Write-Host "Starting $Image as $Name"
docker run -d --name $Name -e POSTGRES_PASSWORD=demo -e POSTGRES_DB=demo $Image | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'docker run failed' }

try {
  # The image starts a temporary server for its init scripts, stops it and starts the real one, so a
  # single successful query is not enough: wait for three in a row.
  $ok = 0
  for ($i = 0; $i -lt 90 -and $ok -lt 3; $i++) {
    & { $ErrorActionPreference = 'Continue'; docker exec $Name psql -U postgres -d demo -At -c 'select 1' 2>&1 | Out-Null }
    if ($LASTEXITCODE -eq 0) { $ok++ } else { $ok = 0 }
    Start-Sleep -Seconds 1
  }
  if ($ok -lt 3) { throw 'PostgreSQL did not become ready' }

  Write-Host '== V1: the schema the application ran on before this change'
  Invoke-Sql 'CREATE SCHEMA journey' | Out-Null
  foreach ($f in '001-baggage-and-itinerary.sql', '002-scan-audit.sql', '003-outbox-and-dedupe.sql', '004-audit-columns.sql') {
    Invoke-SqlFile (Join-Path $changes $f)
  }

  Write-Host "== Legacy data: $Bags bags, $($Bags * $scansPerBag) scans"
  Invoke-Sql @"
INSERT INTO journey.baggage (id, bag_tag, passenger_ref)
  SELECT gen_random_uuid(), lpad(n::text, 10, '0'), 'P-' || n FROM generate_series(1, $Bags) n;
INSERT INTO journey.itinerary (id, baggage_id) SELECT gen_random_uuid(), id FROM journey.baggage;
INSERT INTO journey.itinerary_leg (id, itinerary_id, leg_no, flight_number, origin, destination, departure_at, arrival_at)
  SELECT gen_random_uuid(), i.id, leg, 'AT' || leg, 'IST', 'FRA',
         now() + make_interval(hours => leg), now() + make_interval(hours => leg + 3)
  FROM journey.itinerary i, generate_series(1, 2) leg;
INSERT INTO journey.scan_audit (id, event_id, bag_tag, protocol, airport, checkpoint, device_sequence,
                                occurred_at, device_timestamp, source_timezone, ingested_at, late_event, applied)
  SELECT gen_random_uuid(), 'evt-' || b.bag_tag || '-' || s, b.bag_tag,
         (ARRAY['BARCODE','RFID','CONVEYOR'])[1 + s % 3], 'IST', 'CHECK_IN', s,
         now() - make_interval(mins => s), now() - make_interval(mins => s), 'Europe/Istanbul', now(), s = 3, s <> 3
  FROM journey.baggage b, generate_series(1, $scansPerBag) s;
INSERT INTO journey.outbox_event (id, topic, message_key, payload)
  SELECT gen_random_uuid(), 'baggage.scan.v1', bag_tag, '{}' FROM journey.baggage;
INSERT INTO journey.processed_event (event_id) SELECT 'evt-' || bag_tag || '-1' FROM journey.baggage;
"@ | Out-Null

  $before = Get-Snapshot
  Write-Host '== Before (rows|checksum)'
  $before.GetEnumerator() | ForEach-Object { Write-Host ("  {0,-16} {1}" -f $_.Key, $_.Value) }

  Write-Host '== V2: add nullable scan_source, backfill'
  Invoke-SqlFile (Join-Path $changes '005-scan-source.sql')

  Write-Host '== Validation before V3'
  $missing = [int](Invoke-Sql 'SELECT count(*) FROM journey.scan_audit WHERE scan_source IS NULL')
  Write-Host "  rows without scan_source: $missing"
  if ($missing -ne 0) { throw "V3 must not run: $missing rows have no scan_source" }

  Write-Host '== V3: enforce NOT NULL'
  Invoke-SqlFile (Join-Path $changes '006-scan-source-not-null.sql')

  $after = Get-Snapshot
  Write-Host '== After (rows|checksum)'
  $after.GetEnumerator() | ForEach-Object { Write-Host ("  {0,-16} {1}" -f $_.Key, $_.Value) }

  $failed = @()
  foreach ($t in $before.Keys) { if ($before[$t] -ne $after[$t]) { $failed += $t } }
  $sources = Invoke-Sql "SELECT string_agg(scan_source || '=' || n, ', ') FROM (SELECT scan_source, count(*) n FROM journey.scan_audit GROUP BY 1) q"
  Write-Host "== scan_source values: $sources"

  $protected = Invoke-Sql "SELECT tgenabled FROM pg_trigger WHERE tgname = 'trg_scan_audit_no_update_delete'"
  Write-Host "== append-only trigger enabled: $($protected -eq 'O')"

  if ($failed.Count -gt 0 -or $protected -ne 'O') {
    Write-Host "FAIL: changed tables: $($failed -join ', ')" -ForegroundColor Red
    exit 1
  }
  Write-Host 'PASS: no row lost, no value changed, scan_audit still append-only' -ForegroundColor Green
}
finally {
  if ($Keep) { Write-Host "Container $Name kept (docker rm -f $Name to remove)" }
  else { docker rm -f $Name | Out-Null }
}
