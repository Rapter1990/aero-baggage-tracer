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
| Retrying a move | Send an `Idempotency-Key`: a repeat gets the answer of the first move and nothing happens twice (also no second capacity hold). The key belongs to one case; the same key for a different move is `409 VERSION_CONFLICT`. Without a key a retry is `409 VERSION_CONFLICT` |

**Handler assignment**

| Topic | Rule |
|---|---|
| Who | Agents and dispatchers hand a case to an agent or dispatcher, named by their token subject |
| Effect | The state does not change; the version rises (whoever works on the case must reload), a history row says who assigned whom, and an event is queued |
| Same handler again | Changes nothing, so a retry is safe |
| Another handler | Allowed; the history shows both |
| Closed case | `409 ILLEGAL_TRANSITION` |
| Shown | `assignedTo` in every case answer and in the list |

**Capacity**

| Topic | Rule |
|---|---|
| Moving to `RESERVED` | Holds one slot in the capacity-service first |
| Full | `409 CAPACITY_EXCEEDED`, the case stays where it was |
| No answer in time | `503 CAPACITY_UNAVAILABLE`, nothing stays held, try again |
| Move fails after the hold | The hold is released again |
| Cancelling or closing | Releases the hold; a release that fails is retried every 30 s |
| Timeout (AT-12) | A case `RESERVED` for more than 15 minutes is cancelled with reason `RESERVATION_TIMEOUT` and its hold released |

**Policy**

| Topic | Rule |
|---|---|
| `minimumExpediteMinutes` | The least time a transfer needs. With less time left a bag is rerouted instead of expedited |
| Change | Admins only, against the version they read |
| Seen | At once: the cached copy is dropped when the change is saved |

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

## Events

| Topic | Direction | What happens |
|---|---|---|
| `recovery.case.changed.v1` | published, key = case id | Every change, through a transactional outbox (at least once) |
| `baggage.risk.changed.v1` | read | `HIGH` opens a case with a planned action; a cleared risk cancels a case that is still `OPEN` |
| `baggage.scan.anomaly.v1` | read | A `SCANNER_ANOMALY` flags the bag's active cases and plans the service desk |
| `recovery.capacity.changed.v1` | read | Keeps the latest capacity per resource, a hint for the planned action |
| `baggage.dead-letter.v1` | published | An event that can never be handled, or failed after 3 tries |

Every event id is stored in the same transaction as its effect, so a repeat changes nothing. Events of a bag and leg are applied by event time: an older one is ignored.

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
| Capacity-service | Not built yet (issue #12): moving to `RESERVED` answers 503 until it runs |
| Hold release by the sweeper | Sends no token; the capacity-service must accept it, or the hold stays |
| Assignee check | The assignee is taken as given: the identity-service is not asked whether that person exists or is an agent |
| Airline policy | One `DEFAULT` policy; the events carry no airline |
