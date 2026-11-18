# ADR 0001: Redis caches the recovery policy; PostgreSQL decides everything exact

Status: accepted (recovery-service, issue #11)

## Context

The case study (section 12) asks that Redis cache read-only data "with explicit TTL and invalidation
after policy changes; database remains authoritative", and that Redis "may be unavailable without
permitting duplicate case creation or overbooking", with the degraded-mode behaviour documented.

The recovery-service has one setting an admin changes and that is read often: the **recovery
policy**, today a single value, the least time (minutes) a transfer needs. With less time left a bag
is rerouted instead of expedited (the action strategy). It is stored in
`recovery.recovery_policy` (one row, `DEFAULT`, with an optimistic `version`).

Which service owns "risk policies" in the case study is not settled; this ADR covers the policy the
recovery-service owns. The risk threshold of the risk-streams-service is not part of it.

## Decision

1. **PostgreSQL is the only source of truth.** `PUT /api/v1/recovery-policy` (ADMIN only) changes
   the row with a guarded update on `version` (409 `VERSION_CONFLICT` when it moved).
2. **Redis is a read cache** under the key `recovery:policy:DEFAULT`, value `minutes:version`, with
   an explicit TTL (`aerotrace.recovery.policy.cache-ttl`, default 5 minutes).
3. **Reads:** Redis first. A miss, an entry this service did not write, or any Redis failure reads
   the row and tries to cache it. Failures are counted (`recovery.policy.cache`, outcomes `hit`,
   `miss`, `corrupt`, `error`) and logged by exception class only.
4. **Invalidation:** after the change commits, the cached copy is evicted. The next read caches the
   new value.
5. **Redis is never consulted for anything exact.** One active case per bag and leg is the partial
   unique index `uq_recovery_case_active`; versions and state moves are guarded updates; capacity is
   decided by the capacity-service's database. No code path asks Redis whether a case or a slot may
   be created.

## Degraded mode (Redis unavailable)

| Behaviour | Redis up | Redis down |
|---|---|---|
| Open a case, move a case | database | database, unchanged: **no duplicate case is possible** |
| Reserve capacity | capacity-service database | unchanged: no overbooking from this service |
| Read the policy | cache, else database | database; each read pays at most the Redis timeout (500 ms by default) first |
| Change the policy | database, then evict | database; the eviction fails and is only logged |
| Consume events | database | database, unchanged |
| `/actuator/health` | UP | UP: the Redis health indicator is switched off on purpose, so a Redis outage never makes the pod unready |

## Consequences and limits

- **Staleness is bounded by the TTL, not zero.** If Redis cannot be reached to evict after a
  change, the old policy can be served until the entry expires (at most the TTL). A reader that
  loaded the old row just before a commit can also cache it just after the eviction: the same bound.
  For a minutes-level business setting that is accepted; a stricter need would put the version into
  the cache key and read it from the database on every request.
- Timeouts are short on purpose (`REDIS_TIMEOUT`, `REDIS_CONNECT_TIMEOUT`), so an unreachable Redis
  costs a request a fraction of a second, not the default of several seconds.
- Redis requires a password (`REDIS_PASSWORD`, no default), like the api-gateway.

## Tests

`PolicyCacheTest` (real Redis): fill with a TTL, serve from the cache, expiry, corrupt entry,
eviction on change, 409 on a stale version, 403 for everyone but ADMIN, the strategy following the
policy. `RedisDownTest` (Redis pointed at a dead port): policy read and change still work, and ten
parallel attempts create exactly one case.
