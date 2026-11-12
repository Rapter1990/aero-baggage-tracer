package com.aerotrace.identity.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aerotrace.common.security.Role;
import com.aerotrace.identity.model.domain.AppUser;
import com.aerotrace.identity.support.IdentityTestBase;
import com.aerotrace.identity.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

/** Test users for every role are created when, and only when, seeding is switched on. */
@TestPropertySource(
    properties = {
      "aerotrace.identity.seed.enabled=true",
      "aerotrace.identity.seed.password=seed-test-password-1"
    })
class SeedUsersTest extends IdentityTestBase {

  @Autowired private UserService users;
  @Autowired private SeedUsers seed;
  @Autowired private PasswordEncoder encoder;

  private AppUser user(String username) {
    return users.findByUsername(username).orElseThrow();
  }

  @Test
  void createsOneUserPerRole() {
    assertThat(user("agent").roles()).containsExactly(Role.AGENT);
    assertThat(user("dispatcher").roles()).containsExactly(Role.DISPATCHER);
    assertThat(user("admin").roles()).containsExactly(Role.ADMIN);
    assertThat(user("passenger").roles()).containsExactly(Role.PASSENGER);
  }

  @Test
  void createsTwoPassengersSoOwnershipCanBeTested() {
    AppUser first = user("passenger");
    AppUser second = user("passenger2");

    assertThat(first.passengerRef()).matches("P-\\d{20}");
    assertThat(second.passengerRef()).matches("P-\\d{20}").isNotEqualTo(first.passengerRef());
  }

  @Test
  void staffAccountsHaveNoPassengerReference() {
    assertThat(user("agent").passengerRef()).isNull();
    assertThat(user("admin").passengerRef()).isNull();
  }

  @Test
  void seededPasswordsAreStoredHashed() {
    AppUser agent = user("agent");

    assertThat(agent.passwordHash()).startsWith("$2");
    assertThat(encoder.matches("seed-test-password-1", agent.passwordHash())).isTrue();
  }

  @Test
  void runningTheSeedAgainChangesNothing() {
    String hashBefore = user("agent").passwordHash();

    seed.seed();

    assertThat(user("agent").passwordHash()).isEqualTo(hashBefore);
  }

  @Test
  void seedingWithoutAPasswordFailsInsteadOfUsingADefault() {
    assertThatThrownBy(() -> new SeedUsers(users, new SeedProperties(true, "")).seed())
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new SeedUsers(users, new SeedProperties(true, null)).seed())
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void aTooShortSeedPasswordFails() {
    assertThatThrownBy(() -> new SeedUsers(users, new SeedProperties(true, "short")).seed())
        .isInstanceOf(IllegalStateException.class);
  }
}
