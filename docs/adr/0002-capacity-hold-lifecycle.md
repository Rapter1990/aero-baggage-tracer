# ADR 0002: The recovery-service confirms and releases holds; the capacity-service expires the rest

Status: accepted (capacity-service, issue #12; recovery-service change in the same PR)

## Context

Moving a case to `RESERVED` is a saga over two systems that cannot share a transaction: the
recovery-service asks the capacity-service for a slot (HTTP), then commits the case move in its own
database. Three things can leave a hold that no case owns, or take the slot of a case that needs it:

1. The recovery-service dies between holding and committing. The hold exists; no case knows it.
2. The reserve call times out. The recovery-service releases the id it chose, but the hold was only
   slow: the release finds nothing (404, "treated as released"), then the hold commits. The hold
   exists; nobody will ever release it.
3. A hold that does belong to a `RESERVED` or `IN_TRANSIT` case must never expire, however long the
   bag is on its way.

The first version of the capacity-service had expiry switched off, because it could not tell (1) from
(3): the case-changed event carries no reservation id, and the recovery-service did not confirm
anything. That left (1) and (2) as permanent leaks (found in the architecture review).

## Decision

1. **A hold is provisional until its owner confirms it.** `PUT /api/v1/capacity/reservations/{id}/confirmation`
   marks it. The recovery-service calls it right after the case move commits. A failed call does not
   fail the move: the case stores `hold_confirmed_at` only on success, and the sweeper (every 30 s)
   repeats it for `RESERVED` and `IN_TRANSIT` cases that have a hold and no confirmation. A new
   hold on the same case starts unconfirmed again.
2. **The capacity-service expires unconfirmed holds** older than `aerotrace.capacity.hold.ttl` (1 h),
   and the job is **on by default**. A confirmed hold is never expired; its owner releases it when the
   case is `RESOLVED`, `CANCELLED` or `CLOSED_AS_UNRECOVERABLE`. The TTL must stay far above the
   recovery-service's read timeout (5 s), its sweep delay (30 s) and the case-commit time, so a live
   case is always confirmed long before its hold could expire.
3. **A release of an unknown id leaves a tombstone.** The capacity-service inserts a `RELEASED` row
   under that id (still answering 404). A hold that arrives late meets the row on the primary key and
   is refused (`409 VERSION_CONFLICT`), so it can never take a slot nobody owns. A release racing the
   hold of the same id is resolved by the primary key: the second insert waits for the first, and the
   release then closes the real hold.
4. **Errors are told apart by code, not by status.** `CAPACITY_EXCEEDED` (409) means the leg is full.
   A leg with no resource is `CAPACITY_NOT_PROVISIONED` (404): a set-up problem, counted and logged
   apart (`capacity_reservation_not_provisioned_total`), though the recovery-service still tells its
   own caller there is no capacity. Any other answer on reserve is treated as unavailable (503,
   hold released by id).

## Consequences

- No hold leaks silently: orphans (1) end by expiry, late holds (2) are refused, live ones (3) are
  confirmed.
- An unconfirmed live hold exists for a short time after every move. If the recovery-service and
  the capacity-service are both down for longer than the TTL right after a move, the hold of that
  active case expires and its slot can be taken by another bag. This is the one remaining window; it
  is logged on the recovery side (`recovery.capacity.confirm` outcome `lost`) when it is found.
- Tombstones are small rows. Anyone allowed to release can create one by releasing a random id, so
  the expiry job also deletes tombstones older than the hold time to live (a late hold can only
  arrive within the caller's request timeout, seconds). The rate of creating them is still bounded
  only by the gateway's per-user limit.
- Both services depend on the confirmation contract; `docs/apis/CAPACITY-API.md` describes it.

## Not decided here

How a service proves who it is (today a shared `X-Service-Token`), and whether the east-west call
should go through service discovery. Both are follow-ups.
