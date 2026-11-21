# Notification Service API

[← API overview](API.md) · [Recovery Service API](RECOVERY-API.md) · [README](../../README.md)

Turns `recovery.case.changed.v1` into in-app notifications for the case's assigned handler (BR-08) and lets each user read their own. Design decisions: [ADR 0005](../adr/0005-notification-recipients-dedupe-and-delivery.md).

## Contents

| Section | What it covers |
|---|---|
| [At a glance](#at-a-glance) | Addresses, database, messages |
| [Calls](#calls) | The two reads and who may make them |
| [Notification](#notification) | The response fields |
| [Paging](#paging) | The list envelope and its limits |
| [Rules](#rules) | Ownership, order, what is and is not stored |
| [Errors](#errors) | Status and code per case |
| [Examples](#examples) | Request and answer samples, also the OpenAPI examples |
| [Events](#events) | The event it reads and the dead letter it publishes |
| [Every request in the Postman collection](#every-request-in-the-postman-collection) | The Notification folder |
| [Known limitations](#known-limitations) | What is not done yet |

## At a glance

| Topic | Value |
|---|---|
| Through the gateway | `http://localhost:8080/api/v1/notifications` |
| Directly | `http://localhost:1118` |
| Database | PostgreSQL, schema `notification` |
| Token | Bearer token from [Identity Service](IDENTITY-API.md) |
| Verification key | The identity public key, `IDENTITY_JWT_PUBLIC_KEY` or `IDENTITY_JWT_PUBLIC_KEY_LOCATION`. Required: the service does not start without one (Compose names the bundled development key) |
| Headers | `Authorization: Bearer ...`; `X-Correlation-Id` on every call (generated and returned if missing). Both reads are safe, so `Idempotency-Key` is not used |
| Success answer | `{"success":true,"data":...,"timestamp":...}` |
| Errors | Shared shape, see [response format](../../README.md#api-response-format) |
| OpenAPI | `/v3/api-docs`, listed in the gateway's Swagger UI |

## Calls

| Call | Roles | What it does |
|---|---|---|
| `GET /api/v1/notifications?page=0&size=20` | PASSENGER, AGENT, DISPATCHER, ADMIN | Lists the caller's own notifications, newest first (200) |
| `GET /api/v1/notifications/{id}` | PASSENGER, AGENT, DISPATCHER, ADMIN | Reads one of the caller's own notifications (200) |

There is no create, update or delete call: notifications are made only from events. Mark-as-read and reading other users' notifications (also for ADMIN) are not offered.

| Parameter | Where | Rule |
|---|---|---|
| `page` | query | Integer, 0 or more; default `0` |
| `size` | query | Integer, 1 to 100; default `20`. Anything else is `400 VALIDATION_ERROR` (it is not clamped) |
| `id` | path | The notification id, a UUID. Anything else is `400 VALIDATION_ERROR` |

## Notification

| Field | Type | Meaning |
|---|---|---|
| `id` | string (UUID) | The notification |
| `caseId` | string (UUID) | The recovery case the event was about |
| `bagTag` | string | The bag of the case |
| `state` | string or `null` | The case state after the change: `OPEN`, `ASSESSING`, `RESERVED`, `IN_TRANSIT`, `RESOLVED`, `CANCELLED` or `CLOSED_AS_UNRECOVERABLE`; `null` when the event carried a state this version does not know (the `template` is then `CASE_UPDATED`) |
| `version` | integer | The case version of the event; rises by one on every case change |
| `template` | string | What the message is about, chosen from the state (`CASE_OPENED`, `CASE_RESERVED`, `CASE_RESOLVED`, and so on) |
| `status` | string | Delivery state: `PENDING` (not sent yet, also while it is retried), `SENT`, `FAILED` (all attempts used) |
| `attempts` | integer | Delivery tries made so far; 0 before the first |
| `createdAt` | string (UTC) | When the notification was created from the event |
| `sentAt` | string (UTC) or `null` | When delivery succeeded; `null` unless `status` is `SENT` |

Not in the answer, on purpose: the recipient (it is always the caller), the case `reason` (free text that may name people or places; it is never stored) and delivery internals such as the next attempt time.

## Paging

The list answers with the same page envelope as the [recovery cases list](RECOVERY-API.md#calls):

| Field | Meaning |
|---|---|
| `items` | The notifications of this page |
| `page` | The zero-based page number asked for |
| `size` | The page size used |
| `totalElements` | All notifications of the caller |
| `totalPages` | `ceil(totalElements / size)`; 0 when there are none |

A page past the end is `200` with empty `items`, not an error.

## Rules

| Topic | Rule |
|---|---|
| Ownership | Always `recipient = the token subject`. Nothing in the request can change it |
| Someone else's id | `404 NOT_FOUND`, the same answer as an id that does not exist, so existence is not leaked. Never `403` |
| Roles | All four roles read their own. A passenger has none until a later issue adds the owner lookup, so the list is empty |
| Order | `createdAt` descending, the notification `id` as tie-breaker, so pages are stable |
| One notification per event | The key is `(recipient, eventId, template)`. A repeated event, or a delivery retry, never makes a second one |
| Replays | A replayed older case version makes its own notification; there is no ordering filter |
| Delivery retry | The same row is updated: `attempts` rises, `status` stays `PENDING`, until `SENT` or, after 5 tries, `FAILED` |
| Retention | 90 days (`aerotrace.notification.retention`). A scheduled purge deletes only finished rows (`SENT`, or `FAILED` once its dead letter is acknowledged), oldest first, in bounded batches and at most `purge.max-batches` per run. Purging removes the dedupe key, so a replay older than the retention would notify again; a retention of 30 days or less (the dead-letter topic's) is refused at start-up |
| Times | `createdAt`, `sentAt` and every delivery time are the database's clock, not a service replica's |

## Errors

| Status | Code | When |
|---|---|---|
| 400 | `VALIDATION_ERROR` | `page` below 0 or not a number, `size` below 1, above 100 or not a number, or `id` not a UUID. `validationErrors` names the parameter |
| 401 | `UNAUTHENTICATED` | No token, or an invalid or expired one |
| 403 | `ACCESS_DENIED` | A valid token whose role is not one of the four, or any method other than `GET` (`POST`, `PUT`, `PATCH`, `DELETE`: the API is read-only, and the answer is `403`, not `405`). Not used for someone else's notification (that is 404) |
| 404 | `NOT_FOUND` | No such notification, or it belongs to someone else |
| 500 | `INTERNAL_ERROR` | An unexpected failure; the body carries the `correlationId` |

No new error code is added to `ErrorCode`.

## Examples

These are also the OpenAPI examples of the two operations.

**List** (`GET /api/v1/notifications?page=0&size=2`, 200)

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "id": "7c1d3a52-9f0e-4b7e-8a41-5d2c6e9b0f13",
        "caseId": "0b6f9c1e-3a52-4d0e-9a59-2b8f6b1d7c11",
        "bagTag": "0123456789",
        "state": "RESERVED",
        "version": 4,
        "template": "CASE_RESERVED",
        "status": "SENT",
        "attempts": 2,
        "createdAt": "2026-11-03T08:20:00Z",
        "sentAt": "2026-11-03T08:20:07Z"
      },
      {
        "id": "2e8b5f40-61a7-4c39-b0d2-9a7f13c84e55",
        "caseId": "0b6f9c1e-3a52-4d0e-9a59-2b8f6b1d7c11",
        "bagTag": "0123456789",
        "state": "ASSESSING",
        "version": 3,
        "template": "CASE_ASSESSING",
        "status": "FAILED",
        "attempts": 5,
        "createdAt": "2026-11-03T08:10:00Z",
        "sentAt": null
      }
    ],
    "page": 0,
    "size": 2,
    "totalElements": 7,
    "totalPages": 4
  },
  "timestamp": "2026-11-03T10:00:00Z"
}
```

**Read one** (`GET /api/v1/notifications/7c1d3a52-9f0e-4b7e-8a41-5d2c6e9b0f13`, 200)

```json
{
  "success": true,
  "data": {
    "id": "7c1d3a52-9f0e-4b7e-8a41-5d2c6e9b0f13",
    "caseId": "0b6f9c1e-3a52-4d0e-9a59-2b8f6b1d7c11",
    "bagTag": "0123456789",
    "state": "RESERVED",
    "version": 4,
    "template": "CASE_RESERVED",
    "status": "PENDING",
    "attempts": 1,
    "createdAt": "2026-11-03T08:20:00Z",
    "sentAt": null
  },
  "timestamp": "2026-11-03T10:00:00Z"
}
```

**400** (`size=101`)

```json
{
  "code": "VALIDATION_ERROR",
  "message": "Validation failed",
  "status": 400,
  "path": "/api/v1/notifications",
  "validationErrors": {
    "size": "size must be between 1 and 100"
  },
  "timestamp": "2026-11-03T10:00:00Z",
  "correlationId": "5f1c9e0a-7b3d-4e2a-8c64-0d9a2b7e1f38"
}
```

**401** (no token)

```json
{
  "code": "UNAUTHENTICATED",
  "message": "Authentication required",
  "status": 401,
  "path": "/api/v1/notifications",
  "validationErrors": {},
  "timestamp": "2026-11-03T10:00:00Z",
  "correlationId": "5f1c9e0a-7b3d-4e2a-8c64-0d9a2b7e1f38"
}
```

**404** (an unknown id, or one that belongs to someone else)

```json
{
  "code": "NOT_FOUND",
  "message": "Notification not found",
  "status": 404,
  "path": "/api/v1/notifications/7c1d3a52-9f0e-4b7e-8a41-5d2c6e9b0f13",
  "validationErrors": {},
  "timestamp": "2026-11-03T10:00:00Z",
  "correlationId": "5f1c9e0a-7b3d-4e2a-8c64-0d9a2b7e1f38"
}
```

## Events

| Topic | Direction | What happens |
|---|---|---|
| `recovery.case.changed.v1` | read, key = case id | Every event with an `assignedTo` makes one notification |
| `baggage.dead-letter.v1` | published, key = `eventId` | An event that can never be read, or a notification whose delivery was used up |

No new topic, and no event of its own: the service publishes nothing about created or sent notifications.

### Read: `recovery.case.changed.v1`

The payload is `CaseChangedPayload` in `common-module`; the full field list and history are in [Recovery Service API](RECOVERY-API.md#events). The envelope is the shared one: `eventId, eventType, schemaVersion, aggregateId, occurredAt, publishedAt, correlationId, payload`; `aggregateId` is the case id.

| Field | Revision | Used for |
|---|---|---|
| `caseId` | 1 | Stored; the key of the topic |
| `bagTag` | 1 | Stored |
| `state` | 1 | Stored; chooses the `template` |
| `version` | 1 | Stored |
| `assignedTo` | 2 | The recipient: the handler's token subject. Optional |
| `legId`, `openedAt`, `resolvedAt`, `trigger` | 1, 2 | Read and ignored |
| `reason` | 1 | Never stored, never logged |

| Revision | What the service does |
|---|---|
| 1 (`schemaVersion: 1`) | Has no `assignedTo`: accepted, no notification, offset committed |
| 2 | With `assignedTo`: one notification for `(assignedTo, eventId, template)`. Without it (unassigned case): accepted, no notification |
| Higher | Accepted: `schemaVersion` is additive inside the `.v1` topic. Unknown fields are ignored |

| Case | Handling |
|---|---|
| Same `eventId` twice (outbox at-least-once) | One notification: the unique key and `INSERT ... ON CONFLICT DO NOTHING` |
| Transient failure (database, broker) | Retried with exponential backoff, then dead-lettered. `reason` is the failure class, `attempts` is the configured retry attempts (default 6) |
| A `state` this version does not know | Not an error: notified with template `CASE_UPDATED` and counted as `unknown_state` |
| No `eventId`, an `eventId` outside `[A-Za-z0-9._:-]{1,100}` (for example with CR/LF), unparsable body, non-UUID `caseId`, `assignedTo` over 100 characters, missing or over-long `bagTag`, negative `version`, wrong event type, no payload | Dead-lettered at once, no retry. `reason` names the broken rule, `attempts` is 1 |
| Dead letter of an event | Keyed by the `eventId` when it is safe, else by topic, partition and offset. `rawPayload` is a copy of the event **without any `reason` field**; unreadable text that mentions a reason is not copied (`null`), other unreadable text is cut to 16 KiB |
| Offset | Committed only after the database transaction commits |

Logs carry only `caseId`, `eventId` and `template`; the recipient and `reason` are never logged.

### Published: delivery exhausted

After the last delivery attempt (default 5, see `aerotrace.notification.delivery.*`) the notification becomes `FAILED` and one message goes to `baggage.dead-letter.v1`. The envelope is the shared one; the payload is `DeadLetterPayload` from `common-module`.

| Envelope field | Value |
|---|---|
| `eventId` | A new id for this dead-letter message |
| `eventType` | `baggage.dead-letter` |
| `schemaVersion` | `1` |
| `aggregateId` | The `eventId` of the case event that could not be delivered |
| `occurredAt`, `publishedAt` | When the last attempt failed; when the message was published |
| `correlationId` | The `correlationId` of the case event, when it had a valid one |

| Payload field | Value |
|---|---|
| `originalTopic` | `recovery.case.changed.v1` |
| `originalEventId` | The case event's `eventId` |
| `reason` | `DELIVERY_EXHAUSTED` |
| `attempts` | The number of tries made |
| `rawPayload` | `null`: the original payload is not copied (it would carry `reason` and the recipient) |

```json
{
  "eventId": "a3d41b7e-0c52-4f6a-9e18-6b2d7c5f0a94",
  "eventType": "baggage.dead-letter",
  "schemaVersion": 1,
  "aggregateId": "e91c0a47-5b3d-4d82-8f16-27a0c4d9b6e3",
  "occurredAt": "2026-11-03T08:25:00Z",
  "publishedAt": "2026-11-03T08:25:01Z",
  "correlationId": "5f1c9e0a-7b3d-4e2a-8c64-0d9a2b7e1f38",
  "payload": {
    "originalTopic": "recovery.case.changed.v1",
    "originalEventId": "e91c0a47-5b3d-4d82-8f16-27a0c4d9b6e3",
    "reason": "DELIVERY_EXHAUSTED",
    "attempts": 5,
    "rawPayload": null
  }
}
```

The message is published at least once: `dead_lettered_at` is set only after Kafka acknowledges, so a failed publish is retried, and a database error right after the acknowledgement publishes it again. A consumer of the dead-letter topic must deduplicate by `originalEventId`.

## Every request in the Postman collection

Folder **Notification Service**, run top to bottom with the dev dummy accounts, with the recovery-service and the notification-service running. A notification is made asynchronously from the case event, so the reading requests of folder 3 wait about 2.5 seconds first.

| Folder | Request | Expect |
|---|---|---|
| 1 Sign in | Agent (the handler), dispatcher, admin, passenger | `200`, tokens and the agent's subject stored |
| 2 Make a notification | Open a case; move it to `ASSESSING`; assign it to the agent | `201`, `200`, `200`. Only the assignment event notifies (the first two have no handler) |
| 3 Read my notifications (agent) | List; read by id; `size=1`; a page past the end | `200` with exactly one notification for the case (`CASE_ASSESSING`, version 3, no recipient or reason), `200` `PENDING` or `SENT`, `200`, `200` empty |
| 4 Nobody else sees it | Dispatcher list; dispatcher by id; admin by id; passenger list; an unknown id | `200` without it, `404`, `404`, `200` without it, `404` |
| 5 Refused calls | No token (list, by id); `size=0`; `size=101`; `page=-1`; id not a UUID | `401`, `401`, `400`, `400`, `400`, `400` |
| 6 Service checks (port 1118) | Health; OpenAPI | `200` `UP`, `200` |

Variables: `notificationUrl` (`http://localhost:1118`), `notificationAgentUsername`, `notificationDispatcherUsername`, `notificationAdminUsername`, `notificationPassengerUsername`, `notificationPassword` (the dev accounts); the scripts fill the four tokens, `notificationAgentSubject`, `notificationBagTag`, `notificationLegId`, `notificationCaseId`, `notificationCaseVersion`, `notificationCreateKey`, `notificationMoveKey` and `notificationId`.

## Known limitations

| Topic | State |
|---|---|
| Recipient | Only the assigned handler; the passenger is not notified (needs an owner lookup in the journey-service) |
| Assignment | Cases start unassigned, so the opening event reaches nobody and a handler's first notification is the assignment event, shown with the state's template. An assignment-only event repeats the previous state, so the template cannot tell "assigned" from "state changed"; a previous handler is not told of a reassignment. A revision 3 field on the case event is the planned fix |
| Mark as read, ADMIN access | Not offered |
| Retention | 90 days, purged by a scheduled job (see above) |
| Delivery | A mock provider that can be told to fail; no real channel |
| Gateway | The route `/api/v1/notifications/**` and its docs route are in the gateway; the Kubernetes manifests come with #18 |
