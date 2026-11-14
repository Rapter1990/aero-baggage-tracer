package com.aerotrace.identity.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.common.security.Role;
import com.aerotrace.identity.base.AbstractBaseServiceTest;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AuthorizationPolicyTest extends AbstractBaseServiceTest {

  private final AuthorizationPolicy policy = new AuthorizationPolicy();

  @Test
  void shouldAllowOnlyAdminsToManageUsers() {
    assertThat(policy.isAllowed(Permission.USER_MANAGE, Set.of(Role.ADMIN))).isTrue();
    assertThat(policy.isAllowed(Permission.USER_MANAGE, Set.of(Role.AGENT))).isFalse();
    assertThat(policy.isAllowed(Permission.USER_MANAGE, Set.of(Role.DISPATCHER))).isFalse();
    assertThat(policy.isAllowed(Permission.USER_MANAGE, Set.of(Role.PASSENGER))).isFalse();
  }

  @Test
  void shouldLetAgentsAndDispatchersMoveCases() {
    assertThat(policy.isAllowed(Permission.CASE_TRANSITION, Set.of(Role.AGENT))).isTrue();
    assertThat(policy.isAllowed(Permission.CASE_TRANSITION, Set.of(Role.DISPATCHER))).isTrue();
    assertThat(policy.isAllowed(Permission.CASE_TRANSITION, Set.of(Role.PASSENGER))).isFalse();
  }

  @Test
  void shouldAllowWhenAnyOfSeveralRolesQualifies() {
    assertThat(policy.isAllowed(Permission.CAPACITY_MANAGE, List.of(Role.PASSENGER, Role.ADMIN)))
        .isTrue();
  }

  @Test
  void shouldFailClosedOnMissingInput() {
    assertThat(policy.isAllowed(null, Set.of(Role.ADMIN))).isFalse();
    assertThat(policy.isAllowed(Permission.USER_MANAGE, null)).isFalse();
    assertThat(policy.isAllowed(Permission.USER_MANAGE, Set.of())).isFalse();
  }

  @Test
  void shouldGiveEveryPermissionAtLeastOneRole() {
    for (Permission permission : Permission.values()) {
      assertThat(permission.roles()).isNotEmpty();
      assertThat(permission.description()).isNotBlank();
    }
  }
}
