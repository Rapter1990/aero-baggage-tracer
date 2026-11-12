package com.aerotrace.identity.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.common.security.Role;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The single place that says who may do what across the platform. */
class AuthorizationPolicyTest {

  private final AuthorizationPolicy policy = new AuthorizationPolicy();

  @Test
  void onlyAgentsAndDispatchersTransitionRecoveryCases() {
    assertThat(Permission.CASE_TRANSITION.roles())
        .containsExactlyInAnyOrder(Role.AGENT, Role.DISPATCHER);
  }

  @Test
  void onlyAdminsManageCapacityChangeRiskPoliciesAndRotateKeys() {
    assertThat(Permission.CAPACITY_MANAGE.roles()).containsExactly(Role.ADMIN);
    assertThat(Permission.RISK_POLICY_CHANGE.roles()).containsExactly(Role.ADMIN);
    assertThat(Permission.SIGNING_KEY_ROTATE.roles()).containsExactly(Role.ADMIN);
  }

  @Test
  void aPassengerHoldsNoStaffPermission() {
    for (Permission permission : Permission.values()) {
      assertThat(policy.isAllowed(permission, Set.of(Role.PASSENGER)))
          .as(permission.name())
          .isFalse();
    }
  }

  @Test
  void aRoleIsAllowedOnlyWhenTheCatalogSaysSo() {
    assertThat(policy.isAllowed(Permission.CASE_TRANSITION, Set.of(Role.AGENT))).isTrue();
    assertThat(policy.isAllowed(Permission.CASE_TRANSITION, Set.of(Role.ADMIN))).isFalse();
    assertThat(policy.isAllowed(Permission.RISK_POLICY_CHANGE, Set.of(Role.DISPATCHER))).isFalse();
    assertThat(policy.isAllowed(Permission.CAPACITY_MANAGE, Set.of(Role.AGENT, Role.ADMIN)))
        .isTrue();
  }

  @Test
  void noRolesOrNothingMeansNoAccess() {
    assertThat(policy.isAllowed(Permission.CASE_TRANSITION, Set.of())).isFalse();
    assertThat(policy.isAllowed(Permission.CASE_TRANSITION, null)).isFalse();
    assertThat(policy.isAllowed(null, Set.of(Role.ADMIN))).isFalse();
  }

  @Test
  void everyPermissionIsDocumentedAndHasAtLeastOneRole() {
    for (Permission permission : Permission.values()) {
      assertThat(permission.roles()).as(permission.name()).isNotEmpty();
      assertThat(permission.description()).as(permission.name()).isNotBlank();
    }
  }

  @Test
  void theCatalogIsListedInAStableOrderWithSortedRoles() {
    List<AuthorizationPolicy.Entry> entries = policy.catalog();

    assertThat(entries)
        .extracting(AuthorizationPolicy.Entry::name)
        .containsExactly(
            "CASE_TRANSITION", "CAPACITY_MANAGE", "RISK_POLICY_CHANGE", "SIGNING_KEY_ROTATE");
    assertThat(entries.get(0).roles()).containsExactly("AGENT", "DISPATCHER");
  }
}
