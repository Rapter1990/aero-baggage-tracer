package com.aerotrace.identity.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aerotrace.common.error.ApiException;
import com.aerotrace.common.error.ErrorCode;
import com.aerotrace.common.security.Role;
import com.aerotrace.identity.base.AbstractBaseServiceTest;
import com.aerotrace.identity.model.domain.AppUser;
import com.aerotrace.identity.model.entity.AppUserEntity;
import com.aerotrace.identity.model.mapper.IdentityMapperImpl;
import com.aerotrace.identity.repository.AppUserRepository;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class UserServiceTest extends AbstractBaseServiceTest {

  private static final String PASSWORD = "correct-horse-battery";

  @Mock private AppUserRepository repository;

  private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
  private UserService service;

  @BeforeEach
  void setUp() {
    service = new UserService(repository, encoder, new IdentityMapperImpl());
    when(repository.saveAndFlush(any(AppUserEntity.class))).thenAnswer(i -> i.getArgument(0));
  }

  private AppUserEntity stored(String username, String password, Set<Role> roles) {
    return new AppUserEntity(
        UUID.randomUUID(),
        username,
        username + "@example.com",
        "Test User",
        encoder.encode(password),
        null,
        roles);
  }

  @Test
  void shouldCreateAPassengerWithAGeneratedReferenceAndAHashedPassword() {
    // When
    AppUser user =
        service.create(
            "  Jane.Doe ", " Jane@Example.COM ", " Jane Doe ", PASSWORD, Set.of(Role.PASSENGER));

    // Then
    assertThat(user.username()).isEqualTo("jane.doe");
    assertThat(user.email()).isEqualTo("jane@example.com");
    assertThat(user.fullName()).isEqualTo("Jane Doe");
    assertThat(user.passengerRef()).matches("P-[0-9]{20}");
    assertThat(user.passwordHash()).isNotEqualTo(PASSWORD);
    assertThat(encoder.matches(PASSWORD, user.passwordHash())).isTrue();
    assertThat(user.id()).isNotNull();
  }

  @Test
  void shouldGiveStaffNoPassengerReference() {
    AppUser user = service.create("ops", "ops@example.com", "Ops", PASSWORD, Set.of(Role.AGENT));

    assertThat(user.passengerRef()).isNull();
  }

  @Test
  void shouldRefuseAUserWithoutARole() {
    assertThatThrownBy(() -> service.create("ops", "ops@example.com", "Ops", PASSWORD, Set.of()))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    verify(repository, never()).saveAndFlush(any());
  }

  @Test
  void shouldRefuseAPasswordLongerThanBcryptCanUse() {
    String tooLong = "a".repeat(73);

    assertThatThrownBy(
            () -> service.create("ops", "ops@example.com", "Ops", tooLong, Set.of(Role.AGENT)))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
  }

  @Test
  void shouldCountBytesNotCharactersWhenCheckingThePasswordLength() {
    // 37 two-byte characters are 74 bytes
    String tooLong = "é".repeat(37);

    assertThatThrownBy(
            () -> service.create("ops", "ops@example.com", "Ops", tooLong, Set.of(Role.AGENT)))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void shouldRefuseADuplicateUsername() {
    when(repository.findByUsername("taken"))
        .thenReturn(Optional.of(stored("taken", PASSWORD, Set.of(Role.AGENT))));

    assertThatThrownBy(
            () ->
                service.create("Taken", "new@example.com", "New", PASSWORD, Set.of(Role.PASSENGER)))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.USER_ALREADY_EXISTS));
  }

  @Test
  void shouldRefuseADuplicateEmail() {
    when(repository.findByEmail("taken@example.com"))
        .thenReturn(Optional.of(stored("someone", PASSWORD, Set.of(Role.AGENT))));

    assertThatThrownBy(
            () ->
                service.create("new", "TAKEN@example.com", "New", PASSWORD, Set.of(Role.PASSENGER)))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.USER_ALREADY_EXISTS));
  }

  @Test
  void shouldTranslateARaceOnTheUniqueIndexIntoTheSameConflict() {
    when(repository.saveAndFlush(any(AppUserEntity.class)))
        .thenThrow(new DataIntegrityViolationException("duplicate key"));

    assertThatThrownBy(
            () ->
                service.create(
                    "race", "race@example.com", "Race", PASSWORD, Set.of(Role.PASSENGER)))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.USER_ALREADY_EXISTS));
  }

  @Test
  void shouldFindByUsernameWhenThereIsNoAtSign() {
    when(repository.findByUsername("jane"))
        .thenReturn(Optional.of(stored("jane", PASSWORD, Set.of(Role.PASSENGER))));

    assertThat(service.findByUsernameOrEmail(" Jane ")).isPresent();
    verify(repository, never()).findByEmail(any());
  }

  @Test
  void shouldFindByEmailWhenThereIsAnAtSign() {
    when(repository.findByEmail("jane@example.com"))
        .thenReturn(Optional.of(stored("jane", PASSWORD, Set.of(Role.PASSENGER))));

    assertThat(service.findByUsernameOrEmail("Jane@Example.com")).isPresent();
    verify(repository, never()).findByUsername(any());
  }

  @Test
  void shouldAuthenticateTheRightPasswordOfAnEnabledUser() {
    Optional<AppUser> account = Optional.of(user("jane", PASSWORD, true));

    assertThat(service.authenticates(account, PASSWORD)).isTrue();
  }

  @Test
  void shouldNotAuthenticateAWrongPasswordAnUnknownUserOrADisabledUser() {
    assertThat(service.authenticates(Optional.of(user("jane", PASSWORD, true)), "wrong-password-1"))
        .isFalse();
    assertThat(service.authenticates(Optional.empty(), PASSWORD)).isFalse();
    assertThat(service.authenticates(Optional.of(user("jane", PASSWORD, false)), PASSWORD))
        .isFalse();
  }

  @Test
  void shouldNotLetTheRightPrefixOfALongPasswordIn() {
    // BCrypt ignores everything past 72 bytes: the account password is exactly 72 characters.
    String seventyTwo = "p".repeat(72);
    Optional<AppUser> account = Optional.of(user("jane", seventyTwo, true));

    assertThat(service.authenticates(account, seventyTwo)).isTrue();
    assertThat(service.authenticates(account, seventyTwo + "junk")).isFalse();
    assertThat(service.authenticates(account, "é".repeat(40))).isFalse();
  }

  @Test
  void shouldChangeAPassword() {
    AppUserEntity entity = stored("jane", PASSWORD, Set.of(Role.PASSENGER));
    when(repository.findById(entity.getId())).thenReturn(Optional.of(entity));

    service.changePasswordHash(entity.getId(), service.encode("a-brand-new-password"));

    assertThat(encoder.matches("a-brand-new-password", entity.getPasswordHash())).isTrue();
    assertThat(encoder.matches(PASSWORD, entity.getPasswordHash())).isFalse();
  }

  @Test
  void shouldRefuseToChangeThePasswordOfAnUnknownUser() {
    UUID id = UUID.randomUUID();
    when(repository.findById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.changePasswordHash(id, "hash"))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
  }

  @Test
  void shouldStoreWhatItIsGiven() {
    ArgumentCaptor<AppUserEntity> captor = ArgumentCaptor.forClass(AppUserEntity.class);

    service.create("ops", "ops@example.com", "Ops", PASSWORD, Set.of(Role.DISPATCHER));

    verify(repository).saveAndFlush(captor.capture());
    assertThat(captor.getValue().getRoles()).containsExactly(Role.DISPATCHER);
    assertThat(captor.getValue().getPasswordHash()).startsWith("$2");
  }

  private AppUser user(String username, String password, boolean enabled) {
    return new AppUser(
        UUID.randomUUID(),
        username,
        username + "@example.com",
        "Test User",
        encoder.encode(password),
        null,
        enabled,
        Set.of(Role.PASSENGER),
        null);
  }
}
