package com.aerotrace.identity.model.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.common.security.Role;
import com.aerotrace.identity.model.domain.AppUser;
import com.aerotrace.identity.model.entity.AppUserEntity;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IdentityMapperTest {

  private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final String HASH = "$2a$04$abcdefghijklmnopqrstuuabcdefghijklmnopqrstuvwxyz01234";
  private static final String REF = "P-00000000000000000007";

  private IdentityMapper mapper;

  @BeforeEach
  void setUp() {
    mapper = new IdentityMapperImpl();
  }

  @Test
  void shouldMapEveryFieldOfAStoredUser() {
    // Given
    AppUserEntity entity =
        new AppUserEntity(ID, "jane", HASH, REF, Set.of(Role.PASSENGER, Role.AGENT));

    // When
    AppUser user = mapper.toDomain(entity);

    // Then
    assertThat(user.id()).isEqualTo(ID);
    assertThat(user.username()).isEqualTo("jane");
    assertThat(user.passwordHash()).isEqualTo(HASH);
    assertThat(user.passengerRef()).isEqualTo(REF);
    assertThat(user.enabled()).isTrue();
    assertThat(user.roles()).containsExactlyInAnyOrder(Role.PASSENGER, Role.AGENT);
  }

  @Test
  void shouldMapAStaffUserWithoutAPassengerReference() {
    // Given
    AppUserEntity entity = new AppUserEntity(ID, "ops", HASH, null, Set.of(Role.ADMIN));

    // When
    AppUser user = mapper.toDomain(entity);

    // Then
    assertThat(user.passengerRef()).isNull();
    assertThat(user.roles()).containsExactly(Role.ADMIN);
  }

  @Test
  void shouldNotShareTheRoleSetWithTheEntity() {
    // Given
    AppUserEntity entity = new AppUserEntity(ID, "jane", HASH, REF, Set.of(Role.PASSENGER));
    AppUser user = mapper.toDomain(entity);

    // When the entity changes afterwards
    entity.getRoles().add(Role.ADMIN);

    // Then the mapped user is unaffected
    assertThat(user.roles()).containsExactly(Role.PASSENGER);
  }

  @Test
  void shouldNeverPrintTheHashOrTheReference() {
    // Given
    AppUser user =
        mapper.toDomain(new AppUserEntity(ID, "jane", HASH, REF, Set.of(Role.PASSENGER)));

    // Then
    assertThat(user.toString()).doesNotContain(HASH).doesNotContain(REF);
  }
}
