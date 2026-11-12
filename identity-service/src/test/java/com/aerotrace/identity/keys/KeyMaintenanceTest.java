package com.aerotrace.identity.keys;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

/**
 * Introducing a key and erasing expired ones are independent: one failing must not stop the other.
 */
class KeyMaintenanceTest {

  private final SigningKeyService keys = mock(SigningKeyService.class);
  private final KeyMaintenance maintenance = new KeyMaintenance(keys);

  @Test
  void expiredKeysAreStillRetiredWhenIntroducingAKeyFails() {
    when(keys.rotateIfDue()).thenThrow(new IllegalStateException("cannot encrypt"));

    assertThatCode(maintenance::maintain).doesNotThrowAnyException();

    verify(keys).retireExpired();
  }

  @Test
  void aKeyIsStillIntroducedWhenRetiringFails() {
    doThrow(new IllegalStateException("database busy")).when(keys).retireExpired();

    assertThatCode(maintenance::maintain).doesNotThrowAnyException();

    verify(keys).rotateIfDue();
  }
}
