package com.aerotrace.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aerotrace.common.model.RecoveryState;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The mock channel runs in every deployment of this slice, so its memory must stay bounded. */
class MockNotificationProviderTest {

  private static OutgoingNotification notification() {
    return new OutgoingNotification(
        UUID.randomUUID(),
        "handler-a",
        "CASE_OPENED",
        UUID.randomUUID(),
        "0123456789",
        RecoveryState.OPEN,
        1);
  }

  @Test
  @DisplayName(
      "BR-08 the mock keeps only the most recent calls, so a long run does not grow memory")
  void historyIsBounded() {
    MockNotificationProvider provider = new MockNotificationProvider();
    List<UUID> ids = new ArrayList<>();
    int calls = MockNotificationProvider.HISTORY_LIMIT + 25;
    for (int i = 0; i < calls; i++) {
      OutgoingNotification n = notification();
      ids.add(n.id());
      provider.send(n);
    }

    List<UUID> recent = ids.subList(25, calls);
    assertThat(provider.attemptedIds()).containsExactlyElementsOf(recent);
    assertThat(provider.sentIds()).containsExactlyElementsOf(recent);
  }

  @Test
  @DisplayName("BR-08 the mock can still be told to fail and records attempts and successes apart")
  void testHooksStillWork() {
    MockNotificationProvider provider = new MockNotificationProvider();
    OutgoingNotification failing = notification();
    OutgoingNotification working = notification();

    provider.failNext(1);
    assertThatThrownBy(() -> provider.send(failing)).isInstanceOf(DeliveryFailedException.class);
    provider.send(working);

    assertThat(provider.attemptedIds()).containsExactly(failing.id(), working.id());
    assertThat(provider.sentIds()).containsExactly(working.id());
    provider.reset();
    assertThat(provider.attemptedIds()).isEmpty();
    assertThat(provider.sentIds()).isEmpty();
  }
}
