# Capacity Service API

[← API overview](API.md) · [Recovery Service API](RECOVERY-API.md) · [README](../../README.md)

Keeps the finite recovery capacity (carts, flights, bag slots) and holds slots for recovery cases. PostgreSQL decides every reservation, so parallel requests can never take more than is left.

## Contents

| Section | What it covers |
|---|---|
| [At a glance](#at-a-glance) | Addresses, database, messages |
| [Calls](#calls) | The requests and who may make them |
| [Rules](#rules) | Reservations, release, confirmation, expiry |
| [The 409 contract](#the-409-contract) | What the recovery-service relies on |
| [Errors](#errors) | Status and code per case |
| [Events](#events) | What the service publishes |
| [Redis](#redis) | Why it is not used |
| [Known limitations](#known-limitations) | What is not done yet |

## At a glance

| Topic | Value |
|---|---|
| Through the gateway | `http://localhost:8080/api/v1/capacity/**` |
| Directly | `http://localhost:1116` |
| Database | PostgreSQL, schema `capacity` |
| Token | Bearer token from [Identity Service](IDENTITY-API.md), or the service token on release and confirmation |
| Success answer | `{"success":true,"data":...,"timestamp":...}` |
| Errors | Shared shape, see [response format](../../README.md#api-response-format) |
| OpenAPI | `/v3/api-docs`, listed in the gateway's Swagger UI |

## Calls

| Call | Who | What it does |
|---|---|---|
| `POST /api/v1/capacity/reservations` | AGENT, DISPATCHER, ADMIN | Holds slots (201) |
| `DELETE /api/v1/capacity/reservations/{id}` | AGENT, DISPATCHER, ADMIN, or a service with the service token | Releases a hold (204) |
| `PUT /api/v1/capacity/reservations/{id}/confirmation` | AGENT, DISPATCHER, ADMIN, or a service with the service token | Says a case move kept the hold (200) |
| `PUT /api/v1/capacity/resources/{id}` | ADMIN | Creates a resource or sets its total (200) |
| `GET /api/v1/capacity/resources/{id}` | AGENT, DISPATCHER, ADMIN | Reads one resource |
| `GET /api/v1/capacity/resources` | AGENT, DISPATCHER, ADMIN | Lists the resources, ordered by id |

Request to hold slots:

```json
{
  "reservationId": "0b6e1c52-6f4d-4d43-9d2c-1a1f0a7c9e10",
  "caseId": "5d1c9a42-2e8c-4a1f-9a3e-7f0e5a1d4b21",
  "bagTag": "0123456789",
  "legId": "LEG-2",
  "slots": 1
}
```

Answer: `201` with `{"reservationId": "...", "status": "HELD"}`.

Answer of a resource (`PUT` and `GET`), with who created and last changed it:

```json
{
  "success": true,
  "data": {
    "resourceId": "LEG-2",
    "totalSlots": 3,
    "remainingSlots": 2,
    "createdBy": "00000000-0000-4000-8000-000000000005",
    "createdAt": "2026-11-19T10:25:50.123Z",
    "updatedBy": "00000000-0000-4000-8000-000000000003",
    "updatedAt": "2026-11-19T10:26:30.456Z"
  },
  "timestamp": "2026-11-19T10:26:30.863Z"
}
```

`createdBy` and `updatedBy` are the ids of the users (the subject of their token), `service` for the recovery-service sweeper, `system` for a row from before auditing. See [ADR 0003](../adr/0003-spring-data-auditing.md).

## Rules

| Topic | Rule |
|---|---|
| Resource | One resource per leg: `legId` of the request is the resource id |
| BR-04 | Slots are taken by one conditional `UPDATE ... WHERE remaining_slots >= :slots`. 50 parallel requests for 10 slots succeed exactly 10 times |
| Reservation id | Chosen by the caller. Repeating the same request answers with the same hold and takes nothing more |
| Reused id | The same id with other content is `409 VERSION_CONFLICT` |
| `Idempotency-Key` | Optional. A repeat gets the same hold; the same key for a new reservation id is `409 VERSION_CONFLICT` |
| Retry after release | A hold that was released does not hold again: `409 VERSION_CONFLICT` |
| Release | Returns the slots once. Releasing twice is `204` both times; no such hold is `404` (the recovery-service treats that as released) |
| Release before hold | A release of an unknown id answers `404` and records the id as released (a tombstone). A hold with that id that arrives later is refused with `409 VERSION_CONFLICT`, so a slow hold can never stay with no owner |
| Service token | `X-Service-Token` with the shared secret gives the call the SERVICE role, allowed on release and confirmation only. A wrong secret changes nothing |
| Confirmation | Marks a hold as kept by its case. A released or expired hold answers `409 ILLEGAL_TRANSITION` |
| Expiry | On by default (`CAPACITY_HOLD_EXPIRY_ENABLED`). Only a hold nobody confirmed within `CAPACITY_HOLD_TTL` (1 h) is given back. A confirmed hold is never expired: its owner releases it. See [ADR 0002](../adr/0002-capacity-hold-lifecycle.md) |
| Listing | `GET /resources?page=0&size=50`, ordered by id, size capped at 200 |
| Audit | A resource records who created it and who last changed it, and when. A hold records who made it, who confirmed it and who released it (`system` when it expired). The user is the subject of the token, which is the user's id; `service` for the sweeper. See [ADR 0003](../adr/0003-spring-data-auditing.md) |
| Resize | The free slots move by the same difference. A total lower than the slots held is `409 CAPACITY_EXCEEDED` |

## The 409 contract

| Case | Answer |
|---|---|
| Not enough slots left | `409 CAPACITY_EXCEEDED` |
| The leg has no resource | `404 CAPACITY_NOT_PROVISIONED` (a set-up problem, not a full leg; counted in `capacity_reservation_not_provisioned_total`) |
| Reservation id or key reused for another request | `409 VERSION_CONFLICT` |
| Hold already released or expired | `409 VERSION_CONFLICT` (hold), `409 ILLEGAL_TRANSITION` (confirm) |

The failed request takes nothing and stores no event. The counter `capacity_reservation_conflicts_total` rises by one.

## Errors

| Status | Code | When |
|---|---|---|
| 400 | `VALIDATION_ERROR` | A field or the `Idempotency-Key` breaks a rule |
| 401 | `UNAUTHENTICATED` | No token, an invalid one, or a wrong service token |
| 403 | `ACCESS_DENIED` | A valid token without the role |
| 404 | `NOT_FOUND`, `CAPACITY_NOT_PROVISIONED` | No such hold or resource; a hold for a leg with no resource |
| 409 | `CAPACITY_EXCEEDED`, `VERSION_CONFLICT`, `ILLEGAL_TRANSITION` | See the tables above |

## Events

| Topic | Key | When |
|---|---|---|
| `recovery.capacity.changed.v1` | `resourceId` | After every change of the slots: a hold, a release, an expiry, a resize |

Payload: `{"resourceId":"LEG-2","totalSlots":10,"remainingSlots":7}`. The event is written to the outbox in the same transaction as the change and published at least once. The recovery-service keeps the latest value per resource as a hint.

Metrics: `capacity_reservation_conflicts_total`, `capacity_reservations_created_total`, `capacity_reservations_released_total`, `capacity_reservations_expired_total`, `outbox_pending_count`.

## Redis

The service does not use Redis. Nothing but PostgreSQL can grant a slot, so a Redis outage cannot cause overbooking (AT-19). A Redis hint for reads may come later; it would never be asked before a reservation.

## Known limitations

| Topic | State |
|---|---|
| Confirmation window | The recovery-service confirms a hold right after the case move commits and repeats it every 30 s. If both services are down for longer than the TTL right after a move, the hold of that active case expires |
| Tombstones | A release of an unknown id leaves a small row. The expiry job deletes tombstones older than the hold time to live (1 h); with expiry switched off they are kept |
| Status code of a replay | A repeated hold answers `201` again, with the same body |
| Service token | A secret shorter than 16 characters is ignored. The gateway removes `X-Service-Token`, so only direct service calls can use it. Do not use the Compose default outside local runs |
| Resource lifecycle | Resources are created and resized, never deleted |
| Redis hint | Not built (optional in the issue) |

## Every request in the Postman collection

Generated from `postman/AeroTrace.postman_collection.json`, folder "Capacity Service". Run it top to bottom; see [the Postman section of the API overview](API.md#postman-collection).

| Column | Meaning |
|---|---|
| Step | Number before the request name |
| Call | Path via gateway, unless marked direct (port 1116) |
| Expect | Status the test script checks |

#### 1 - Sign in

| Step | Request | Call | Expect |
|---|---|---|---|
| 1.1 | Sign in as the admin | `POST /api/v1/auth/login` | 200 |
| 1.2 | Sign in as the agent | `POST /api/v1/auth/login` | 200 |
| 1.3 | Sign in as the dispatcher | `POST /api/v1/auth/login` | 200 |
| 1.4 | Sign in as the passenger | `POST /api/v1/auth/login` | 200 |

#### 2 - Resources (the admin sets capacity)

| Step | Request | Call | Expect |
|---|---|---|---|
| 2.1 | Create the resource with 3 slots (admin) | `PUT /api/v1/capacity/resources/{leg}` | 200 |
| 2.2 | Read the resource (agent) | `GET /api/v1/capacity/resources/{leg}` | 200 |
| 2.3 | Set the same total again (nothing changes) | `PUT /api/v1/capacity/resources/{leg}` | 200 |
| 2.4 | Set the total as the agent (refused) | `PUT /api/v1/capacity/resources/{leg}` | 403 |
| 2.5 | An invalid resource id (refused) | `PUT /api/v1/capacity/resources/bad%20id!` | 400 |
| 2.6 | A total out of range (refused) | `PUT /api/v1/capacity/resources/{leg}` | 400 |
| 2.7 | Read a resource that does not exist | `GET /api/v1/capacity/resources/NO-SUCH-LEG-PM` | 404 |
| 2.8 | List the resources, one per page (agent) | `GET /api/v1/capacity/resources?page=0&size=1` | 200 |

#### 3 - Hold slots (agent)

| Step | Request | Call | Expect |
|---|---|---|---|
| 3.1 | Hold one slot | `POST /api/v1/capacity/reservations` | 201 |
| 3.2 | Repeat the same request (the same hold, nothing more is taken) | `POST /api/v1/capacity/reservations` | 201 |
| 3.3 | Two slots are free, and the audit shows the agent as the last to change it | `GET /api/v1/capacity/resources/{leg}` | 200 |
| 3.4 | The same reservation id for a different request (refused) | `POST /api/v1/capacity/reservations` | 409 |
| 3.5 | Hold one more slot with an Idempotency-Key | `POST /api/v1/capacity/reservations` | 201 |
| 3.6 | The same Idempotency-Key for another reservation (refused) | `POST /api/v1/capacity/reservations` | 409 |
| 3.7 | Two slots when one is left (409 CAPACITY_EXCEEDED, nothing is taken) | `POST /api/v1/capacity/reservations` | 409 |
| 3.8 | A leg without a resource (404, a set-up problem and not a full leg) | `POST /api/v1/capacity/reservations` | 404 |
| 3.9 | Zero slots (refused) | `POST /api/v1/capacity/reservations` | 400 |
| 3.10 | Hold a slot as the passenger (refused) | `POST /api/v1/capacity/reservations` | 403 |
| 3.11 | Hold a slot without a token (refused) | `POST /api/v1/capacity/reservations` | 401 |

#### 4 - Confirm and release (dispatcher)

| Step | Request | Call | Expect |
|---|---|---|---|
| 4.1 | Confirm the first hold | `PUT /api/v1/capacity/reservations/{id}/confirmation` | 200 |
| 4.2 | Confirm it again (nothing changes) | `PUT /api/v1/capacity/reservations/{id}/confirmation` | 200 |
| 4.3 | Release the first hold | `DELETE /api/v1/capacity/reservations/{id}` | 204 |
| 4.4 | Release it again (204, the slot is returned only once) | `DELETE /api/v1/capacity/reservations/{id}` | 204 |
| 4.5 | Two slots are free again, and the audit shows the dispatcher | `GET /api/v1/capacity/resources/{leg}` | 200 |
| 4.6 | A released hold does not hold again (refused) | `POST /api/v1/capacity/reservations` | 409 |
| 4.7 | Confirm a released hold (refused) | `PUT /api/v1/capacity/reservations/{id}/confirmation` | 409 |
| 4.8 | Release an id that was never held (404, and the id is remembered as released) | `DELETE /api/v1/capacity/reservations/{id}` | 404 |
| 4.9 | A hold with that id arrives late (refused, it never takes a slot) | `POST /api/v1/capacity/reservations` | 409 |
| 4.10 | Confirm a hold that does not exist | `PUT /api/v1/capacity/reservations/{id}/confirmation` | 404 |
| 4.11 | Release the second hold | `DELETE /api/v1/capacity/reservations/{{capacityReservationId2}}` | 204 |
| 4.12 | All 3 slots are free again | `GET /api/v1/capacity/resources/{leg}` | 200 |

#### 5 - Refused calls

| Step | Request | Call | Expect |
|---|---|---|---|
| 5.1 | List the resources as the passenger (403) | `GET /api/v1/capacity/resources` | 403 |
| 5.2 | Create a resource as the dispatcher (403: only admins) | `PUT /api/v1/capacity/resources/{leg}` | 403 |
| 5.3 | Release without a token (401) | `DELETE /api/v1/capacity/reservations/{id}` | 401 |
| 5.4 | A path that is not listed, as the admin (403: everything else is denied) | `GET /api/v1/capacity/other` | 403 |

#### 6 - Service token (the recovery-service sweeper)

| Step | Request | Call | Expect |
|---|---|---|---|
| 6.1 | Hold a slot through the gateway (agent) | `POST /api/v1/capacity/reservations` | 201 |
| 6.2 | Confirm it with the service token, no user token | `PUT /api/v1/capacity/reservations/{id}/confirmation` (direct) | 200 |
| 6.3 | Release it with the service token | `DELETE /api/v1/capacity/reservations/{id}` (direct) | 204 |
| 6.4 | All 3 slots are free, and the audit shows the service | `GET /api/v1/capacity/resources/{leg}` | 200 |
| 6.5 | The service token cannot hold slots (403) | `POST /api/v1/capacity/reservations` (direct) | 403 |
| 6.6 | A wrong service token changes nothing (401) | `DELETE /api/v1/capacity/reservations/{id}` (direct) | 401 |
| 6.7 | Through the gateway the service token is ignored (401) | `DELETE /api/v1/capacity/reservations/{id}` | 401 |

#### 7 - Service checks (direct, port 1116)

| Step | Request | Call | Expect |
|---|---|---|---|
| 7.1 | Health | `GET /actuator/health` (direct) | 200 |
| 7.2 | OpenAPI definition (the 409 contract is documented) | `GET /v3/api-docs` (direct) | 200 |
