package com.aerotrace.recovery.policy;

import java.time.Duration;

/**
 * The recovery policy as the service uses it.
 *
 * @param minimumExpediteMinutes the least time a transfer needs; with less left a bag is rerouted
 * @param version raised on every change; an admin changes the policy against the version they saw
 */
public record PolicyView(int minimumExpediteMinutes, long version) {

  public Duration minimumExpediteTime() {
    return Duration.ofMinutes(minimumExpediteMinutes);
  }
}
