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
| Service token | `X-Service-Token` with the shared secret gives the call the SERVICE role, allowed on release and confirmation only. A wrong secret changes nothing |
| Confirmation | Marks a hold as kept by its case. A released or expired hold answers `409 ILLEGAL_TRANSITION` |
| Expiry | Off by default (`CAPACITY_HOLD_EXPIRY_ENABLED`). When on, only a hold nobody confirmed within `CAPACITY_HOLD_TTL` (1 h) is given back. A confirmed hold is never expired |
| Resize | The free slots move by the same difference. A total lower than the slots held is `409 CAPACITY_EXCEEDED` |

## The 409 contract

| Case | Answer |
|---|---|
| Not enough slots left | `409 CAPACITY_EXCEEDED` |
| The leg has no resource | `409 CAPACITY_EXCEEDED` |
| Reservation id or key reused for another request | `409 VERSION_CONFLICT` |
| Hold already released or expired | `409 VERSION_CONFLICT` (hold), `409 ILLEGAL_TRANSITION` (confirm) |

The failed request takes nothing and stores no event. The counter `capacity_reservation_conflicts_total` rises by one.

## Errors

| Status | Code | When |
|---|---|---|
| 400 | `VALIDATION_ERROR` | A field or the `Idempotency-Key` breaks a rule |
| 401 | `UNAUTHENTICATED` | No token, an invalid one, or a wrong service token |
| 403 | `ACCESS_DENIED` | A valid token without the role |
| 404 | `NOT_FOUND` | No such hold or resource |
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
| Confirmation by the recovery-service | The endpoint exists, but the recovery-service does not call it yet. That is why expiry is off: an unconfirmed hold may be a bag on its way |
| Release before hold | If the recovery-service times out on a hold and releases it before the original request commits, the release answers 404 and the late hold has no owner. Nothing reclaims it while expiry is off. Fix: the recovery-service confirms its holds and expiry is turned on |
| Service token | A secret shorter than 16 characters is ignored. The gateway removes `X-Service-Token`, so only direct service calls can use it. Do not use the Compose default outside local runs |
| Resource lifecycle | Resources are created and resized, never deleted |
| Redis hint | Not built (optional in the issue) |
