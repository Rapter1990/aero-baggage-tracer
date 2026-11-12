package com.aerotrace.identity.model.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.common.security.Role;
import com.aerotrace.identity.base.AbstractBaseServiceTest;
import com.aerotrace.identity.model.domain.AppUser;
import com.aerotrace.identity.model.dto.response.RegisteredUserResponse;
import com.aerotrace.identity.model.entity.AppUserEntity;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IdentityMapperTest extends AbstractBaseServiceTest {

  private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final String HASH = "$2a$04$abcdefghijklmnopqrstuuabcdefghijklmnopqrstuvwxyz01234";
  private static final String REF = "P-00000000000000000007";

  private IdentityMapper mapper;

  @BeforeEach
  void setUp() {
    mapper = new IdentityMapperImpl();
  }

  private static AppUserEntity entity(String ref, Set<Role> roles) {
    return new AppUserEntity(ID, "jane", "jane@example.com", "Jane Doe", HASH, ref, roles);
  }

  @Test
  void shouldMapEveryFieldOfAStoredUser() {
    // Given
    AppUserEntity entity = entity(REF, Set.of(Role.PASSENGER, Role.AGENT));

    // When
    AppUser user = mapper.toDomain(entity);

    // Then
    assertThat(user.id()).isEqualTo(ID);
    assertThat(user.username()).isEqualTo("jane");
    assertThat(user.email()).isEqualTo("jane@example.com");
    assertThat(user.fullName()).isEqualTo("Jane Doe");
    assertThat(user.passwordHash()).isEqualTo(HASH);
    assertThat(user.passengerRef()).isEqualTo(REF);
    assertThat(user.enabled()).isTrue();
    assertThat(user.roles()).containsExactlyInAnyOrder(Role.PASSENGER, Role.AGENT);
  }

  @Test
  void shouldMapAStaffUserWithoutAPassengerReference() {
    // When
    AppUser user = mapper.toDomain(entity(null, Set.of(Role.ADMIN)));

    // Then
    assertThat(user.passengerRef()).isNull();
    assertThat(user.roles()).containsExactly(Role.ADMIN);
  }

  @Test
  void shouldNotShareTheRoleSetWithTheEntity() {
    // Given
    AppUserEntity entity = entity(REF, Set.of(Role.PASSENGER));
    AppUser user = mapper.toDomain(entity);

    // When the entity changes afterwards
    entity.getRoles().add(Role.ADMIN);

    // Then the mapped user is unaffected
    assertThat(user.roles()).containsExactly(Role.PASSENGER);
  }

  @Test
  void shouldNeverPrintTheHashTheReferenceOrTheEmail() {
    // When
    AppUser user = mapper.toDomain(entity(REF, Set.of(Role.PASSENGER)));

    // Then
    assertThat(user.toString())
        .doesNotContain(HASH)
        .doesNotContain(REF)
        .doesNotContain("jane@example.com");
  }

  @Test
  void shouldMapARegistrationResponseWithSortedRolesAndNoSecrets() {
    // Given
    AppUser user =
        new AppUser(
            ID,
            "jane",
            "jane@example.com",
            "Jane Doe",
            HASH,
            REF,
            true,
            Set.of(Role.PASSENGER, Role.ADMIN),
            Instant.parse("2026-11-12T10:15:30Z"));

    // When
    RegisteredUserResponse response = mapper.toRegisteredUserResponse(user);

    // Then
    assertThat(response.id()).isEqualTo(ID);
    assertThat(response.username()).isEqualTo("jane");
    assertThat(response.email()).isEqualTo("jane@example.com");
    assertThat(response.fullName()).isEqualTo("Jane Doe");
    assertThat(response.roles()).containsExactly("ADMIN", "PASSENGER");
    assertThat(response.status()).isEqualTo("ACTIVE");
    assertThat(response.createdAt()).isEqualTo(Instant.parse("2026-11-12T10:15:30Z"));
    assertThat(response.toString()).doesNotContain(HASH).doesNotContain(REF);
  }

  @Test
  void shouldReportADisabledUserAsDisabled() {
    // Given
    AppUser user =
        new AppUser(
            ID, "jane", "j@example.com", "Jane Doe", HASH, null, false, Set.of(Role.AGENT), null);

    // Then
    assertThat(mapper.toRegisteredUserResponse(user).status()).isEqualTo("DISABLED");
  }
}
