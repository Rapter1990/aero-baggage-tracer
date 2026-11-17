package com.aerotrace.riskstreams;

/**
 * Rejected-transition count of one bagTag in one tumbling event-time window.
 *
 * @param touchedAtMs wall-clock millis of the last update; the entry is deleted once that is older
 *     than window + grace, whatever the event time of the scans was
 */
record AnomalyWindow(int count, boolean emitted, long touchedAtMs) {}
