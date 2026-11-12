package com.aerotrace.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aerotrace.common.security.Role;
import com.aerotrace.identity.base.AbstractBaseServiceTest;
import com.aerotrace.identity.user.UserService;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class BootstrapAdminTest extends AbstractBaseServiceTest {

  @Mock private UserService users;

  private BootstrapAdmin runner(String username, String password, String email) {
    return new BootstrapAdmin(new BootstrapProperties(username, password, email), users);
  }

  @Test
  void shouldCreateNothingWhenNoUsernameIsConfigured() {
    runner("", "", "").run(null);
    runner(null, null, null).run(null);

    verify(users, never()).create(any(), any(), any(), any(), any());
  }

  @Test
  void shouldCreateTheFirstAdministrator() {
    when(users.countWithRole(Role.ADMIN)).thenReturn(0L);

    runner("root", "a-long-bootstrap-password", "root@example.com").run(null);

    verify(users)
        .create(
            "root",
            "root@example.com",
            "Administrator",
            "a-long-bootstrap-password",
            Set.of(Role.ADMIN));
  }

  @Test
  void shouldNeverCreateASecondAdministrator() {
    when(users.countWithRole(Role.ADMIN)).thenReturn(1L);

    runner("root", "a-long-bootstrap-password", "root@example.com").run(null);

    verify(users, never()).create(any(), any(), any(), any(), any());
  }

  @Test
  void shouldRefuseToStartWithAnIncompleteConfiguration() {
    assertThatThrownBy(() -> runner("root", "", "root@example.com").run(null))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> runner("root", "a-long-bootstrap-password", " ").run(null))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void shouldNeverPrintThePassword() {
    assertThat(new BootstrapProperties("root", "super-secret-password", "r@example.com").toString())
        .doesNotContain("super-secret-password");
  }
}
