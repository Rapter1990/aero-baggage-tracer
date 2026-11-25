package com.aerotrace.journey.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.journey.base.AbstractBaseServiceTest;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LiquibaseContextsGuardTest extends AbstractBaseServiceTest {

  private final LiquibaseContextsGuard guard = new LiquibaseContextsGuard();

  @Test
  @DisplayName(
      "AT-17 empty Liquibase contexts become the default, so the dev sample data stays off")
  void blankContextsBecomeTheDefault() {
    for (String blank : new String[] {null, "", "  "}) {
      SpringLiquibase liquibase = new SpringLiquibase();
      liquibase.setContexts(blank);

      guard.postProcessBeforeInitialization(liquibase, "liquibase");

      assertThat(liquibase.getContexts()).isEqualTo("expand,contract");
    }
  }

  @Test
  @DisplayName("AT-17 contexts that are set are left alone")
  void setContextsAreKept() {
    SpringLiquibase liquibase = new SpringLiquibase();
    liquibase.setContexts("expand,contract,dev");

    guard.postProcessBeforeInitialization(liquibase, "liquibase");

    assertThat(liquibase.getContexts()).isEqualTo("expand,contract,dev");
  }
}
