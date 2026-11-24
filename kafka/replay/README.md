# Replay dataset: late, duplicate and out-of-order scans

`scan-replay.ndjson` is one bag (`9990000001`) on one leg, in arrival order. Each line is
`<key>|<event envelope>`: the key is the bag tag (the key of `baggage.scan.received.v1`), the
envelope is the JSON the journey-service's outbox publishes (`schemaVersion` 2).

| Line | Event | What it exercises |
|------|-------|-------------------|
| 1 | `replay-01` CHECK_IN 09:00 | in order |
| 2 | `replay-02` SORTING 09:10 | in order |
| 3 | `replay-02` again | **duplicate**: the same event id, one effective change (AT-03) |
| 4 | `replay-04` LOADED 09:30 | arrives before the TRANSFER scan below |
| 5 | `replay-03` TRANSFER 09:24 | **out of order**, inside the 10 minute grace: applied by event time (AT-04) |
| 6 | `replay-05` CHECK_IN 07:30 | **late** past the grace: kept for audit, `lateEvent=true` (AT-05, BR-06) |

`ReplayDatasetTest` (common-module) keeps the file valid.

## Replay it

Against the Compose stack (Kafka container `kafka`):

```powershell
powershell -File kafka/replay/replay.ps1
```

Options: `-Container kafka`, `-Topic baggage.scan.received.v1`, `-File <ndjson>`, `-DryRun` (prints the
records and sends nothing). The script pipes the file into `kafka-console-producer.sh` with
`parse.key=true` and `key.separator=|`, so the records keep their order inside the partition of the bag.
