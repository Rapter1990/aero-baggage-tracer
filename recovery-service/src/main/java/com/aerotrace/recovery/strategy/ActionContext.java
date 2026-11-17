package com.aerotrace.recovery.strategy;

import java.time.Duration;
import java.util.Objects;

/**
 * What a strategy needs to decide.
 *
 * @param timeToDeparture time left until the flight leaves; negative when it already left
 * @param capacityAvailable whether a recovery vehicle slot is believed to be available
 */
public record ActionContext(Duration timeToDeparture, boolean capacityAvailable) {

  public ActionContext {
    Objects.requireNonNull(timeToDeparture, "timeToDeparture");
  }
}
