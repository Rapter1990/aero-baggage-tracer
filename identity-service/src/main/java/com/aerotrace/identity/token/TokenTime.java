package com.aerotrace.identity.token;

import java.time.Clock;

/**
 * The clock that stamps tokens. Kept apart from the database-synced clock used for key lifecycle:
 * the gateway and resource servers judge {@code iat} and {@code exp} with their own clocks, so the
 * tokens must carry this machine's time, not the database's.
 *
 * @param clock the clock to stamp tokens with
 */
public record TokenTime(Clock clock) {}
