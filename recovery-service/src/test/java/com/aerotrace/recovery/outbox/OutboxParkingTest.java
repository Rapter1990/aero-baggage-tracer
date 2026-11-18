package com.aerotrace.recovery.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Only a failure that belongs to one event may park it; anything else must keep it pending. */
class OutboxParkingTest {

  @Test
  @DisplayName(
      "AT-14 an event that is too large, has a bad topic or cannot be serialized is parked")
  void failuresOfTheEventParkIt() {
    assertThat(OutboxRelay.refusedForGood(new RecordTooLargeException("big"))).isTrue();
    assertThat(OutboxRelay.refusedForGood(new InvalidTopicException("bad topic"))).isTrue();
    assertThat(OutboxRelay.refusedForGood(new SerializationException("bad bytes"))).isTrue();
    // As the producer reports it: wrapped in the future's ExecutionException.
    assertThat(
            OutboxRelay.refusedForGood(new ExecutionException(new RecordTooLargeException("big"))))
        .isTrue();
  }

  @Test
  @DisplayName("AT-14 a broker or configuration problem never parks events, they stay pending")
  void brokerProblemsKeepTheEventPending() {
    assertThat(OutboxRelay.refusedForGood(new TopicAuthorizationException("no access"))).isFalse();
    assertThat(OutboxRelay.refusedForGood(new AuthorizationException("no access"))).isFalse();
    assertThat(OutboxRelay.refusedForGood(new KafkaException("something else"))).isFalse();
    assertThat(OutboxRelay.refusedForGood(new TimeoutException("slow"))).isFalse();
    assertThat(OutboxRelay.refusedForGood(new ExecutionException(new IllegalStateException("x"))))
        .isFalse();
  }
}
