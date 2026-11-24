package com.aerotrace.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aerotrace.notification.config.NotificationProperties;
import com.aerotrace.notification.repository.DeliveryRepository;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** A purge run stops after {@code purge.max-batches} statements, even if more rows are due. */
class RetentionPurgerLimitTest {

  private static NotificationProperties properties(int batchSize, int maxBatches) {
    return new Binder(
            new MapConfigurationPropertySource(
                Map.of(
                    "aerotrace.notification.jwt.public-key-location", "classpath:keys/public.pem",
                    "aerotrace.notification.purge.batch-size", String.valueOf(batchSize),
                    "aerotrace.notification.purge.max-batches", String.valueOf(maxBatches))))
        .bindOrCreate("aerotrace.notification", NotificationProperties.class);
  }

  @Test
  @DisplayName(
      "BR-08 one purge run deletes at most max-batches full batches, then waits for the next")
  void purgeRunStopsAtTheBatchLimit() {
    DeliveryRepository deliveries = mock(DeliveryRepository.class);
    // Always a full batch: without the limit the run would never end.
    when(deliveries.purgeFinished(any(Duration.class), anyInt())).thenReturn(10);

    int deleted = new RetentionPurger(deliveries, properties(10, 3)).purge();

    assertThat(deleted).isEqualTo(30);
    verify(deliveries, times(3)).purgeFinished(Duration.ofDays(90), 10);
  }

  @Test
  @DisplayName("BR-08 a purge run ends at the first batch that is not full")
  void purgeRunEndsAtAPartialBatch() {
    DeliveryRepository deliveries = mock(DeliveryRepository.class);
    when(deliveries.purgeFinished(any(Duration.class), anyInt())).thenReturn(10, 10, 4);

    int deleted = new RetentionPurger(deliveries, properties(10, 100)).purge();

    assertThat(deleted).isEqualTo(24);
    verify(deliveries, times(3)).purgeFinished(Duration.ofDays(90), 10);
  }
}
