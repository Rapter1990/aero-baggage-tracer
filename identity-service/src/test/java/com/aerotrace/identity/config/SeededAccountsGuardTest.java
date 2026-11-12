package com.aerotrace.identity.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aerotrace.common.security.Role;
import com.aerotrace.identity.model.domain.AppUser;
import com.aerotrace.identity.seed.SeedUsers;
import com.aerotrace.identity.user.UserService;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Accounts that an earlier development run created keep their published password even after seeding
 * is switched off. Starting such a database outside development must be refused.
 */
class SeededAccountsGuardTest {

  private static final String PUBLISHED = "aerotrace-dev-password";

  private final UserService users = mock(UserService.class);

  private static AppUser user(String name) {
    return new AppUser(UUID.randomUUID(), name, "$2a$04$hash", null, true, Set.of(Role.ADMIN));
  }

  @Test
  void aSeededAccountThatStillAcceptsThePublishedPasswordStopsTheStart() {
    AppUser admin = user("admin");
    when(users.findByUsername("admin")).thenReturn(Optional.of(admin));
    when(users.passwordMatches(eq(admin), eq(PUBLISHED))).thenReturn(true);

    assertThatThrownBy(() -> new SeededAccountsGuard(users, false).check())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("'admin'")
        .hasMessageNotContaining(PUBLISHED);
  }

  @Test
  void aSeededAccountWithADifferentPasswordIsFine() {
    AppUser admin = user("admin");
    when(users.findByUsername("admin")).thenReturn(Optional.of(admin));
    when(users.passwordMatches(any(), any())).thenReturn(false);

    assertThatCode(() -> new SeededAccountsGuard(users, false).check()).doesNotThrowAnyException();
  }

  @Test
  void aDatabaseWithoutTheTestAccountsIsFine() {
    when(users.findByUsername(any())).thenReturn(Optional.empty());

    assertThatCode(() -> new SeededAccountsGuard(users, false).check()).doesNotThrowAnyException();
  }

  @Test
  void aDevelopmentRunSkipsTheCheckEntirely() {
    new SeededAccountsGuard(users, true).check();

    verify(users, never()).findByUsername(any());
  }

  @Test
  void everyAccountSeedingCreatesIsChecked() {
    when(users.findByUsername(any())).thenReturn(Optional.empty());

    new SeededAccountsGuard(users, false).check();

    for (String username : SeedUsers.USERNAMES) {
      verify(users).findByUsername(username);
    }
    assertThat(SeedUsers.USERNAMES)
        .containsExactlyInAnyOrder("passenger", "passenger2", "agent", "dispatcher", "admin");
  }
}
