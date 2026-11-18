# Explicit Business Rules

Source: AeroTrace Senior Java Microservices Case Study, section 6.

| Rule  | Acceptance criterion |
|-------|----------------------|
| BR-01 | A bag may have at most one active recovery case per itinerary leg. |
| BR-02 | Risk is HIGH when departure is within 20 minutes and expected load confirmation is absent. |
| BR-03 | Three rejected transitions for one bag in a 5-minute event-time window produce a SCANNER_ANOMALY event. |
| BR-04 | Capacity reservation cannot exceed remaining slots, including with 50 parallel requests. |
| BR-05 | Recovery state moves only along the defined state machine; each move records who/when/why. |
| BR-06 | An event older than the grace interval is preserved for audit and flagged LATE_EVENT. |
| BR-07 | A resolved case cannot be reopened; a new leg requires a new case key. |
| BR-08 | Notification is unique by (recipient, eventId, template), regardless of delivery retries. |

## Related notes from the case study

- BR-02: the 20-minute threshold is configurable by airport (section 5.2).
- BR-05: recovery states are `OPEN -> ASSESSING -> RESERVED -> IN_TRANSIT -> RESOLVED`; `CLOSED_AS_UNRECOVERABLE` and `CANCELLED` are terminal alternatives. Invalid moves return HTTP 409 (section 5.3).
- BR-01 / BR-07: the active-case natural key is bag plus itinerary leg, enforced by a unique constraint (section 9).
