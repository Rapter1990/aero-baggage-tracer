package com.aerotrace.identity.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aerotrace.common.error.ApiException;
import com.aerotrace.common.error.ErrorCode;
import com.aerotrace.common.security.Role;
import com.aerotrace.identity.model.domain.AppUser;
import com.aerotrace.identity.support.IdentityTestBase;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Registering users: hashing, role rules and the passenger binding. */
class UserServiceTest extends IdentityTestBase {

  private static final String PASSWORD = "a-long-test-password";

  @Autowired private UserService users;
  @Autowired private PasswordEncoder encoder;
  @Autowired private JdbcClient jdbc;

  private static String name() {
    return "user-" + UUID.randomUUID().toString().substring(0, 8);
  }

  @Test
  void storesOnlyABcryptHashOfThePassword() {
    String username = name();

    AppUser saved = users.register(username, PASSWORD, Set.of(Role.AGENT), null);

    assertThat(saved.passwordHash()).startsWith("$2").doesNotContain(PASSWORD);
    String stored =
        jdbc.sql("select password_hash from identity.app_user where username = :u")
            .param("u", username)
            .query(String.class)
            .single();
    assertThat(encoder.matches(PASSWORD, stored)).isTrue();
    assertThat(encoder.matches("another-password-1", stored)).isFalse();
  }

  @Test
  void savesAndReloadsTheRoles() {
    String username = name();

    users.register(username, PASSWORD, Set.of(Role.AGENT, Role.DISPATCHER), null);

    AppUser loaded = users.findByUsername(username).orElseThrow();
    assertThat(loaded.roles()).containsExactlyInAnyOrder(Role.AGENT, Role.DISPATCHER);
    assertThat(loaded.enabled()).isTrue();
    assertThat(loaded.passengerRef()).isNull();
  }

  @Test
  void aPassengerIsBoundToAPseudonymousReference() {
    String username = name();
    String ref = PassengerRef.generate();

    users.register(username, PASSWORD, Set.of(Role.PASSENGER), ref);

    assertThat(users.findByUsername(username).orElseThrow().passengerRef()).isEqualTo(ref);
  }

  @Test
  void generatedPassengerReferencesAreUniqueAndPseudonymous() {
    String first = PassengerRef.generate();

    assertThat(first).matches("P-\\d{20}").isNotEqualTo(PassengerRef.generate());
  }

  @Test
  void aPassengerWithoutAReferenceIsRejected() {
    assertThatThrownBy(() -> users.register(name(), PASSWORD, Set.of(Role.PASSENGER), null))
        .isInstanceOf(ApiException.class)
        .extracting(e -> ((ApiException) e).errorCode())
        .isEqualTo(ErrorCode.VALIDATION_ERROR);
  }

  @Test
  void aNonPassengerCannotCarryAPassengerReference() {
    assertThatThrownBy(
            () -> users.register(name(), PASSWORD, Set.of(Role.AGENT), PassengerRef.generate()))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void aUserNeedsAtLeastOneRole() {
    assertThatThrownBy(() -> users.register(name(), PASSWORD, Set.of(), null))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void weakPasswordsAreRejected() {
    assertThatThrownBy(() -> users.register(name(), "short", Set.of(Role.AGENT), null))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void aDuplicateUsernameIsRejectedWithoutRevealingAnythingElse() {
    String username = name();
    users.register(username, PASSWORD, Set.of(Role.AGENT), null);

    assertThatThrownBy(() -> users.register(username, PASSWORD, Set.of(Role.ADMIN), null))
        .isInstanceOf(ApiException.class)
        .extracting(e -> ((ApiException) e).errorCode())
        .isEqualTo(ErrorCode.REQUEST_REJECTED);
  }

  @Test
  void aFailedRegistrationLeavesNoHalfCreatedUser() {
    String username = name();

    assertThatThrownBy(() -> users.register(username, PASSWORD, Set.of(Role.PASSENGER), "bad"))
        .isInstanceOf(ApiException.class);

    assertThat(users.findByUsername(username)).isEmpty();
  }

  @Test
  void unknownUsersAreNotFound() {
    assertThat(users.findByUsername(name())).isEmpty();
  }

  @Test
  void passwordsAreCheckedAgainstTheStoredHash() {
    String username = name();
    users.register(username, PASSWORD, Set.of(Role.AGENT), null);
    AppUser account = users.findByUsername(username).orElseThrow();

    assertThat(users.passwordMatches(account, PASSWORD)).isTrue();
    assertThat(users.passwordMatches(account, "wrong-password-123")).isFalse();
  }

  @Test
  void theAccountNeverPrintsItsHashOrReference() {
    String username = name();
    AppUser saved =
        users.register(username, PASSWORD, Set.of(Role.PASSENGER), PassengerRef.generate());

    assertThat(saved.toString())
        .doesNotContain(saved.passwordHash())
        .doesNotContain(saved.passengerRef());
  }
}
