# ADR-0002: Event contract versioning and compatibility

- Status: Accepted
- Date: 2026-11-08
- Context issue: backlog #4 Define Common Module (GitHub #7), PR #8
- Found by: `/architecture-review` (findings B3, S1, S2)

## Context

Events travel through Kafka and are replayed (spec section 8, AT-14). Services are upgraded by
rolling update, so during a deploy producers and consumers run different versions at the same time,
and replay feeds old events to new consumers. Compile-time safety inside one repository does not
protect against that: the wire format is the real contract.

The first implementation was inconsistent. The envelope carried `schemaVersion` and
`requireSchemaVersionAtMost` sent anything newer to the dead-letter topic, while the JSON reader
ignored unknown fields. An additive change published during a rolling deploy would have
dead-lettered every event at the not-yet-upgraded consumers.

## Decision

1. **The topic suffix `.vN` is the only breaking version.** A breaking change (rename, removal,
   type change, changed meaning) means a new topic, for example `baggage.risk.changed.v2`. The
   producer publishes to both topics until every consumer has moved over.
2. **`schemaVersion` is an additive revision inside a topic.** It starts at 1 and goes up when
   fields are added. Consumers must accept higher values. A consumer may only demand a minimum with
   `EventEnvelope.requireSchemaVersionAtLeast(n)`, for example when it needs a field older revisions
   lack. Dead-lettering is for unknown event types and payloads that cannot be read at all, not for
   a higher version.
3. **Tolerant reader.** `EventJson` ignores unknown properties. Services must use `EventJson` (or a
   serde built on it) so there is one wire format.
4. **Enums on the wire are open.** An unknown enum value reads as `null`
   (`READ_UNKNOWN_ENUM_VALUES_AS_NULL`). Consumers handle `null` with a default branch that logs and
   skips, instead of failing the message.
5. **Payload evolution rules.** Fields are only added, never renamed, removed or retyped. New fields
   are nullable boxed types so an older event that lacks them is distinguishable from a real zero or
   `false`.
6. **Read the version first.** `EventJson.readHeader` reads `eventType` and `schemaVersion` without
   binding the payload, so a consumer can decide before a changed payload shape fails binding.
7. **Message keys are part of the contract.** `bagTag`, `bagTag:legId`, `caseId`, `resourceId` and
   `eventId` per topic, as documented on `Topics`. Per-bag ordering depends on every producer
   formatting the key identically.

## Consequences

- Rolling deploys and replay of old events are safe for additive changes.
- Removing or changing a field costs a new topic and a dual-publish period. That is deliberate.
- Consumers must write a default branch for unknown enum values.
- `eventId` is generated once per logical event. The outbox must persist it and reuse it on every
  relay attempt, otherwise deduplication on `processed_event` fails. This belongs to the exactly-once
  ADR planned with Kafka infrastructure (backlog #15).

## Not decided here / follow-up

- **Golden JSON samples.** Each topic needs a sample file in the test resources that must keep
  deserializing. Not done in PR #8.
- **Boxed types.** `long version`, `int attempts`, `boolean lateEvent`, `totalSlots` and
  `remainingSlots` are still primitives in the current records; rule 5 applies when they next change.
- **`eventType` constants** and a topic-to-`eventType` mapping.
- **Key builders** (for example `riskKey(bagTag, legId)`) on `Topics`.
- **Dead-letter payload** needs the original key, partition, offset and exception class, and a
  decision on retention and access because `rawPayload` may contain passenger data.
- The payload field sets were inferred from the backlog; spec sections 7 and 8 are not in the
  repository, so they are not verified against the spec text.
- Correlation id through Kafka (copy MDC into new envelopes, restore it in consumers) belongs to the
  observability ADR.

## Alternatives considered

- **`schemaVersion` as a breaking version with dead-lettering of newer events.** This was the first
  implementation. It breaks rolling deploys and replay.
- **A schema registry (Avro or Protobuf).** Stronger guarantees, but adds infrastructure beyond the
  case-study stack. JSON plus the rules above is enough here.
