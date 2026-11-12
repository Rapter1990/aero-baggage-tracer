package com.aerotrace.identity.auth;

import com.aerotrace.common.security.Role;
import com.aerotrace.identity.constant.AuthConstants;
import com.aerotrace.identity.user.UserService;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Creates the first administrator when one is configured and none exists yet. Public registration
 * only creates passengers, so this is the only way to get the first staff account. It does nothing
 * when no username is configured, and never creates a second administrator.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class BootstrapAdmin implements ApplicationRunner {

  private final BootstrapProperties properties;
  private final UserService users;

  @Override
  public void run(ApplicationArguments args) {
    if (isBlank(properties.username())) {
      return;
    }
    if (isBlank(properties.password()) || isBlank(properties.email())) {
      throw new IllegalStateException(
          AuthConstants.BOOTSTRAP_PROPERTY_PREFIX
              + ": username, password and email must all be set to create the first administrator");
    }
    if (users.countWithRole(Role.ADMIN) > 0) {
      log.info("An administrator already exists; the bootstrap administrator is not created");
      return;
    }
    users.create(
        properties.username(),
        properties.email(),
        AuthConstants.BOOTSTRAP_ADMIN_FULL_NAME,
        properties.password(),
        Set.of(Role.ADMIN));
    log.info("Created the bootstrap administrator");
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
