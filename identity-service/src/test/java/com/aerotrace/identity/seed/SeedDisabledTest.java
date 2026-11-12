package com.aerotrace.identity.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.identity.support.IdentityTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** Without the switch no test users exist: production never gets seeded accounts. */
class SeedDisabledTest extends IdentityTestBase {

  @Autowired private ApplicationContext context;

  @Test
  void theSeedIsNotWiredByDefault() {
    assertThat(context.getBeanNamesForType(SeedUsers.class)).isEmpty();
  }
}
