# Recovery Service API

[← API overview](API.md) · [Journey Service API](JOURNEY-API.md) · [README](../../README.md)

Opens recovery cases for bags at risk of missing a connection, moves them along a state machine, holds capacity and keeps the recovery policy.

## Contents

| Section | What it covers |
|---|---|
| [At a glance](#at-a-glance) | Addresses, database, messages |
| [Calls](#calls) | The requests and who may make them |
| [Rules](#rules) | Cases, moves, capacity, policy |
| [Errors](#errors) | Status and code per case |
| [Internal export](#internal-export) | The read-only case export for the search-service's rebuild |
| [Events](#events) | What the service publishes and reads |
| [When Redis is down](#when-redis-is-down) | Degraded mode |
| [Known limitations](#known-limitations) | What is not done yet |

## At a glance

| Topic | Value |
|---|---|
| Through the gateway | `http://localhost:8080/api/v1/recovery-cases` and `/api/v1/recovery-policy` |
| Directly | `http://localhost:1115` |
| Database | PostgreSQL, schema `recovery` |
| Cache | Redis, the recovery policy only |
| Token | Bearer token from [Identity Service](IDENTITY-API.md) |
| Success answer | `{"success":true,"data":...,"timestamp":...}` |
| Errors | Shared shape, see [response format](../../README.md#api-response-format) |
| OpenAPI | `/v3/api-docs`, listed in the gateway's Swagger UI |

## Calls

| Call | Roles | What it does |
|---|---|---|
| `POST /api/v1/recovery-cases` | AGENT, DISPATCHER, ADMIN | Opens a case (201) |
| `PATCH /api/v1/recovery-cases/{id}/transitions` | AGENT, DISPATCHER | Moves a case (200) |
| `PUT /api/v1/recovery-cases/{id}/assignment` | AGENT, DISPATCHER | Hands a case to a handler (200) |
| `GET /api/v1/recovery-cases?state=OPEN&page=0&size=20` | AGENT, DISPATCHER, ADMIN | Lists cases, oldest first |
| `GET /api/v1/recovery-policy` | AGENT, DISPATCHER, ADMIN | Reads the policy |
| `PUT /api/v1/recovery-policy` | ADMIN | Changes the policy |
| `GET /internal/v1/export/cases?after=&size=` | service token only, not routed by the gateway | Every case, keyset-paged, for the search-service's rebuild ([below](#internal-export)) |

| Call | Body |
|---|---|
| `POST /api/v1/recovery-cases` | `bagTag`, `legId`, `reason`; optional header `Idempotency-Key` |
| `PATCH .../transitions` | `targetState`, `expectedVersion`, `reason`; optional header `Idempotency-Key` |
| `PUT .../assignment` | `assignee` (the handler's token subject), `expectedVersion` |
| `PUT /api/v1/recovery-policy` | `minimumExpediteMinutes` (0 to 720), `expectedVersion` |

```json
{ "bagTag": "0123456789", "legId": "LEG-2", "reason": "HIGH_CONNECTION_RISK" }
```

```json
{ "targetState": "RESERVED", "expectedVersion": 2, "reason": "Transport slot secured" }
```

## Rules

**Cases**

| Topic | Rule |
|---|---|
| One active case (BR-01) | One per bag and leg. The database decides (a unique index), so parallel requests, replicas and a Redis outage cannot create two |
| Duplicate | `409 DUPLICATE_RECOVERY_CASE` |
| Idempotency-Key | A repeat returns the original case; the same key for another bag or leg is `409 VERSION_CONFLICT` |
| New case after the end | A final case frees the bag and leg; the new case is a new case with a new id |

**Moves**

| Topic | Rule |
|---|---|
| Path (BR-05) | `OPEN` to `ASSESSING` to `RESERVED` to `IN_TRANSIT` to `RESOLVED` |
| Alternatives | `CANCELLED` and `CLOSED_AS_UNRECOVERABLE` from any open state (an assumption: the case study only calls them terminal) |
| Final (BR-07) | `RESOLVED`, `CANCELLED` and `CLOSED_AS_UNRECOVERABLE` never move again: `409 ILLEGAL_TRANSITION` |
| Version | `expectedVersion` must be the current version, else `409 VERSION_CONFLICT`; a new case starts at 1 |
| History | Every move records who, when and why |
| Retrying a create | With an `Idempotency-Key` a repeat returns the first case. Only the bag and the leg are compared, not the `reason` |
| Retrying a move | Send an `Idempotency-Key`: a repeat gets the answer of the first move and nothing happens twice (also no second capacity hold). The key belongs to one case; the same key with a different target state or version is `409 VERSION_CONFLICT` (the reason is not compared). Without a key a retry is `409 VERSION_CONFLICT` |

**Handler assignment**

| Topic | Rule |
|---|---|
| Who | Agents and dispatchers hand a case to an agent or dispatcher, named by their token subject |
| Effect | The state does not change; the version rises (whoever works on the case must reload), a history row says who assigned whom, and an event is queued |
| Same handler again | Changes nothing and answers with the case as it is, whatever version the request carries, so a retry after a lost answer is safe |
| Order of the checks | Unknown case 404, then the same handler (200), then a stale version `409 VERSION_CONFLICT`, then a closed case `409 ILLEGAL_TRANSITION`, as on a move |
| Assignee | An identity id: letters, digits and `. _ : -`, up to 100 characters. An e-mail address is refused |
| Another handler | Allowed; the history shows both |
| Closed case | `409 ILLEGAL_TRANSITION` |
| Shown | `assignedTo` in every case answer and in the list |

**Capacity**

| Topic | Rule |
|---|---|
| Moving to `RESERVED` | Holds one slot in the capacity-service first |
| Full | `409 CAPACITY_EXCEEDED`, the case stays where it was |
| Leg without a capacity resource | Also `409 CAPACITY_EXCEEDED` to the caller, but counted (`recovery_capacity_reserve_total{outcome="not_provisioned"}`) and logged apart: it is a set-up problem |
| No answer in time, or any other answer | `503 CAPACITY_UNAVAILABLE`, nothing stays held, try again. Answers are told apart by the error `code`, not by the status alone |
| After the move commits | The hold is confirmed to the capacity-service, which expires holds nobody confirmed |
| Move fails after the hold | The hold is released again |
| Cancelling, closing or resolving | Releases the hold; a release that fails is retried every 30 s. The hold stays while the case is `RESERVED` or `IN_TRANSIT`: it is confirmed, so the capacity-service never expires it while the case is active |
| Timeout (AT-12) | A case `RESERVED` for more than 15 minutes is cancelled with reason `RESERVATION_TIMEOUT` and its hold released |

**Policy**

| Topic | Rule |
|---|---|
| `minimumExpediteMinutes` | The least time a transfer needs. With less time left a bag is rerouted instead of expedited |
| Change | Admins only, against the version they read |
| Seen | At once: the cached copy is dropped when the change is saved. If Redis cannot be reached at that moment, within the cache TTL (5 minutes) |

## Errors

| Status | Code | When |
|---|---|---|
| 400 | `VALIDATION_ERROR` | A field or the `Idempotency-Key` breaks a rule |
| 401 | `UNAUTHENTICATED` | No token, or an invalid or expired one |
| 403 | `ACCESS_DENIED` | A valid token without the role |
| 404 | `NOT_FOUND` | No such case |
| 409 | `DUPLICATE_RECOVERY_CASE` | The bag and leg already have an active case |
| 409 | `ILLEGAL_TRANSITION` | The move is not allowed from the current state |
| 409 | `VERSION_CONFLICT` | A stale version, or an `Idempotency-Key` used for another request |
| 409 | `CAPACITY_EXCEEDED` | No capacity is left |
| 503 | `CAPACITY_UNAVAILABLE` | The capacity-service did not answer in time |

## Internal export

`GET /internal/v1/export/cases?after={caseId}&size={1..500}` (#29, [ADR 0004](../adr/0004-search-read-models-and-rebuild.md)) lets the search-service rebuild its `cases` index from this service's own state, without reading its database.

| Topic | Value |
|---|---|
| Who | A service caller with the shared secret in `X-Service-Token` (`SEARCH_EXPORT_SERVICE_TOKEN`, at least 16 characters). A user token is never accepted here, an admin's neither |
| Routing | Inside the network only: the gateway has no route to `/internal/**` and strips `X-Service-Token` from every request it forwards |
| Without a token configured | Fail closed: every call is `401`. Only the `local` and `dev` profiles open the export instead, for local runs (logged at start-up) |
| Paging | Keyset by `caseId` (the primary key), ascending. The first page has no `after`; the next one asks `after=<nextAfter>`. `nextAfter` is `null` on the last page |
| Size | Default 100, maximum 500; `0`, more than 500 or an `after` that is not a case id is `400 VALIDATION_ERROR` |
| Consistency | Every case that exists for the whole run is returned exactly once, whatever is inserted meanwhile; a case inserted meanwhile at most once. A case changed meanwhile is returned as it was when its page was read; its event carries the newer version |
| Read-only | A read-only transaction; nothing is written (no row, no history, no outbox event) |
| Not in | The public OpenAPI document |

| Status | Code | When |
|---|---|---|
| 200 | | A page |
| 400 | `VALIDATION_ERROR` | `size` out of range, or `after` not a case id |
| 401 | `UNAUTHENTICATED` | No token, a wrong one, a user token, or no token configured |
| 403 | `ACCESS_DENIED` | The token on any other method or `/internal/**` path |

Each item has the shape of the `recovery.case.changed.v1` payload (revision 2, below), so the rebuild source and the events cannot drift apart:

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "caseId": "0b6f9c1e-3a52-4d0e-9a59-2b8f6b1d7c11",
        "bagTag": "0123456789",
        "legId": "LEG-2",
        "state": "RESOLVED",
        "version": 6,
        "reason": "delivered",
        "openedAt": "2026-11-03T08:00:00Z",
        "resolvedAt": "2026-11-03T09:30:00Z",
        "trigger": "MANUAL",
        "assignedTo": "agent-7"
      }
    ],
    "nextAfter": "0b6f9c1e-3a52-4d0e-9a59-2b8f6b1d7c11"
  },
  "timestamp": "2026-11-03T10:00:00Z"
}
```

## Events

| Topic | Direction | What happens |
|---|---|---|
| `recovery.case.changed.v1` | published, key = case id | Every change, through a transactional outbox (at least once) |
| `baggage.risk.changed.v1` | read | `HIGH` opens a case with a planned action; a cleared risk cancels a case that is still `OPEN` |
| `baggage.scan.anomaly.v1` | read | A `SCANNER_ANOMALY` flags the bag's active cases and plans the service desk |
| `recovery.capacity.changed.v1` | read | Keeps the latest capacity per resource, a hint for the planned action |
| `baggage.dead-letter.v1` | published | An event that can never be handled, or failed after 3 tries |

Every event id is stored in the same transaction as its effect, so a repeat changes nothing. Events of a bag and leg are applied by event time: an older one is ignored.

While a consumer handles an event, the event's `correlationId` is in the logging context, so its log lines and the case events it writes carry it (a case opened from a risk event has the risk event's id). It is restored, never generated: an event without a valid one is handled without one.

**`recovery.case.changed.v1` payload, schema revision 2** (`schemaVersion: 2`, #29). Revision 2 added the last four fields; the `.v1` topic and the first six fields are unchanged. A revision 1 event has none of them, and a consumer reads them as `null`.

| Field | Since | Meaning |
|---|---|---|
| `caseId`, `bagTag`, `legId` | 1 | The case and its bag and leg |
| `state` | 1 | The state after the change |
| `version` | 1 | Starts at 1, rises by one on every change (move, assignment, anomaly flag) |
| `reason` | 1 | Free text of the change; may name people or places, so consumers must not index or log it |
| `openedAt` | 2 | When the case was opened (UTC) |
| `resolvedAt` | 2 | When it reached `RESOLVED`; `null` in every other state, `CANCELLED` and `CLOSED_AS_UNRECOVERABLE` included |
| `trigger` | 2 | What opened it: `RISK` (a risk event), `MANUAL` (staff through the API) or `SCANNER_ANOMALY`. No case has `SCANNER_ANOMALY` yet: an anomaly only flags the cases a bag already has. A value added later reads as `null` |
| `assignedTo` | 2 | The token subject of the handler; `null` while unassigned |

Cases that existed before revision 2 were backfilled by the migration `011-case-trigger-and-resolution`: `trigger` is `RISK` when the system opened the case and `MANUAL` otherwise, `resolvedAt` is the time of the move to `RESOLVED` in the history.

**Planned action**

| Time left | Capacity | Plan |
|---|---|---|
| At least the minimum expedite time | free, or not known yet | `EXPEDITED_TRANSFER` |
| Less than the minimum | any | `REROUTE_NEXT_FLIGHT` |
| At least the minimum | all known resources full | `HOLD_AT_SERVICE_DESK` |
| Unknown departure, or an anomaly on the bag | any | `HOLD_AT_SERVICE_DESK` |

## When Redis is down

Redis only caches the policy. See [ADR 0001](../adr/0001-redis-policy-cache.md).

| Behaviour | Redis down |
|---|---|
| Open or move a case | Unchanged: no duplicate case is possible |
| Read the policy | From the database, after at most the Redis timeout (500 ms) |
| Change the policy | Works; an old cached copy may live up to its TTL (5 minutes) |
| Health | Stays UP |

## Known limitations

| Topic | State |
|---|---|
| Capacity-service | See [Capacity Service API](CAPACITY-API.md). While it is down, moving to `RESERVED` answers 503 |
| Hold release by the sweeper | Sends no user token; it sends the shared `CAPACITY_SERVICE_TOKEN` in `X-Service-Token`, which the capacity-service accepts for release and confirmation only. Without the token the release fails and is retried |
| Hold confirmation | After a move to `RESERVED` commits, this service confirms the hold (`PUT .../confirmation`); the sweeper repeats a failed confirmation every 30 s for `RESERVED` and `IN_TRANSIT` cases. The capacity-service expires holds nobody confirmed, see [ADR 0002](../adr/0002-capacity-hold-lifecycle.md) |
| Assignee check | The assignee is checked for shape only: the identity-service is not asked whether that person exists or is an agent, and any agent or dispatcher may assign any case to any id |
| Redis policy entry | An entry outside 0 to 720 minutes is dropped; Redis is still trusted to hold only what this service wrote, so keep it private (ACL or own instance per service) |
| Airline policy | One `DEFAULT` policy; the events carry no airline |
| Leg ids | `legId` must be 1 to 32 letters, digits, `-` or `_`. The journey-service's leg ids (in its scan and bag events) are 36-character UUIDs, so a risk event for such a leg is refused as unreadable and dead-lettered. Not changed by #29 (it needs a migration of `leg_id`); open |
