package com.aerotrace.riskstreams;

/** Rejected-transition count of one bagTag in one tumbling event-time window. */
record AnomalyWindow(int count, boolean emitted) {}
