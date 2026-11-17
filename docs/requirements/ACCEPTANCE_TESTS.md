# Acceptance Test Scenarios

Source: AeroTrace Senior Java Microservices Case Study, section 18.

| ID    | Scenario | Related rules |
|-------|----------|---------------|
| AT-01 | Register a valid baggage journey and retrieve its ordered itinerary legs. | |
| AT-02 | Reject an existing bagTag with a stable 409 error contract. | |
| AT-03 | Submit an event twice and observe one effective journey change. | |
| AT-04 | Apply a late-but-within-grace scan by event time, not arrival order. | BR-06 |
| AT-05 | Preserve an excessively late scan in audit and flag LATE_EVENT. | BR-06 |
| AT-06 | Detect three invalid transitions in a five-minute event-time window. | BR-03 |
| AT-07 | Trigger HIGH risk when an unconfirmed connecting leg departs in under 20 minutes. | BR-02 |
| AT-08 | Clear or downgrade risk on a valid loading confirmation; no false extra case. | BR-02 |
| AT-09 | Create one active case from two parallel attempts for the same bag and leg. | BR-01 |
| AT-10 | Reject an illegal transition from RESOLVED to IN_TRANSIT. | BR-05, BR-07 |
| AT-11 | Reserve only 10 slots when 50 requests arrive concurrently. | BR-04 |
| AT-12 | Release a provisional capacity hold after a recovery timeout. | |
| AT-13 | Repeat outbox publication and observe one effective notification. | BR-08 |
| AT-14 | Stop Kafka during a case transition; replay the committed event on restart. | |
| AT-15 | Block a passenger querying another passenger's bag. | |
| AT-16 | Prove an expired JWT returns 401 and an unauthorized role returns 403. | |
| AT-17 | Apply V1-V3 Liquibase migrations to preloaded data without row loss. | |
| AT-18 | Rebuild Elasticsearch from source data with no duplicate documents. | |
| AT-19 | Take Redis offline and verify no incorrect capacity reservation. | BR-04 |
| AT-20 | Propagate a trace through gateway, journey-service and Kafka consumer into Tempo. | |
| AT-21 | Collect a service log in Loki and correlate it to the matching Tempo trace. | |
| AT-22 | Restart a Minikube pod without losing committed recovery state. | |
| AT-23 | Fail SonarQube quality gate and confirm no image deployment occurs. | |
| AT-24 | Demonstrate deployment rollback and gateway smoke tests. | |

The "Related rules" column is a traceability aid added here; the case study lists the scenarios without mapping them to rules.
