# ADR 0005: Notification recipients, deduplication and delivery retry

Status: proposed (notification-service, issue #14, GitHub #32)

## Context

The notification-service turns `recovery.case.changed.v1` into in-app notifications (BR-08). The recovery outbox can publish the same event twice with the same `eventId`; AT-13 requires that to produce one notification. Three things were open before the build:

1. **No event says who receives a notification.** `CaseChangedPayload` (revision 2) has `caseId, bagTag, legId, state, version, reason, openedAt, resolvedAt, trigger, assignedTo`. It has no `passengerRef`; the passenger link lives only in the journey-service, and the contracts keep passenger data out of events on purpose.
2. **Two kinds of retry exist** and must not be mixed: reading an event from Kafka, and sending a notification through the delivery provider.
3. **`reason` is free text** that may name people or places.

## Decision

1. **Recipient.** The case's assigned handler: `assignedTo`, the token subject. No other service changes. An event without `assignedTo` (schema revision 1, or an unassigned case) is accepted and produces no notification. Notifying the passenger needs a journey-service owner lookup and is out of scope for this slice (a later issue).
2. **Notified states.** Every case event notifies. The template comes from the state (`CASE_OPENED`, `CASE_RESERVED`, `CASE_RESOLVED`, and so on). There is no ordering filter: a replayed older version produces its own notification, and the unique key below is the only deduplication. This is documented, accepted behavior.
3. **Deduplication (BR-08, AT-13).** Table `notification` with a unique constraint on `(recipient, event_id, template)`, written with `INSERT ... ON CONFLICT DO NOTHING`. The constraint is the guard, so there is no `processed_event` table and it holds under two replicas. The consumer commits its Kafka offset only after the database transaction commits.
4. **Stored data.** Only `caseId`, `bagTag`, `state` and `version` are kept. `reason` is never stored. Logs carry only `caseId`, `eventId` and `template`; the recipient and any free text are never logged (free text goes through `LogMasker`).
5. **Reading events.** The Kafka error handler retries transient failures with exponential backoff, then dead-letters to `baggage.dead-letter.v1` (the topic exists; `Topics.ALL` is unchanged). Malformed events (no `eventId`, unknown state, unparsable body) are dead-lettered at once without retry, as in the recovery-service.
6. **Sending.** A scheduled dispatcher claims due rows with `FOR UPDATE SKIP LOCKED`, so two replicas never send the same row, and calls a `NotificationProvider` port. The adapter is a mock that can be told to fail. A failure sets `next_attempt_at = now + min(base * 2^n, max)` with jitter and updates the same row, so a retry never creates a second notification ("regardless of delivery retries"). Defaults: 5 attempts, 2 s base, 5 min maximum (`notification.delivery.*`).
7. **Exhaustion.** After the last attempt the row becomes `FAILED` and one `DeadLetterPayload` (`originalTopic=recovery.case.changed.v1`, `originalEventId`, reason `DELIVERY_EXHAUSTED`, attempts, no raw payload) goes to `baggage.dead-letter.v1`. `dead_lettered_at` is set only after Kafka acknowledges, so a failed publish is retried. This is at-least-once: duplicates on the dead-letter topic are possible and are keyed by `eventId`.
8. **API.** `GET /api/v1/notifications?page=&size=` (size 1 to 100, else `400 VALIDATION_ERROR`) and `GET /api/v1/notifications/{id}`. Ownership is always `recipient = current subject`; another user's id answers `404`, not `403`, so existence is not leaked. All four roles may read their own. Mark-as-read and ADMIN access to other users' notifications are out of scope.
9. **Retention.** Notifications are meant to be kept 90 days. No purge job is built in this slice; the table grows until a later issue adds one.
10. **Runtime.** Port 1118, schema `notification` (own schema, own Liquibase changelog), copied from the recovery-service layout. Kubernetes manifests stay with #18.

## Consequences

- Passengers get no notification until a later issue adds the owner lookup; the handler does.
- An assignment-only event repeats the previous state, so the template cannot tell "assigned" from "state changed" without storing the previous version. Accepted for now.
- The unbounded table is a known gap (decision 9).
