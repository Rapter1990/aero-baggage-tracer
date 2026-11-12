package com.aerotrace.identity.seed;

import com.aerotrace.common.error.ApiException;
import com.aerotrace.common.error.ErrorCode;
import com.aerotrace.common.security.Role;
import com.aerotrace.identity.user.UserService;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Creates the test users (one per role, plus a second passenger so ownership can be tested) when
 * {@code aerotrace.identity.seed.enabled=true}. Users that already exist are left untouched, so it
 * is safe to run on every start. All of them share the configured password, which is hashed.
 */
@Component
@ConditionalOnProperty(prefix = "aerotrace.identity.seed", name = "enabled", havingValue = "true")
public class SeedUsers implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(SeedUsers.class);
  private static final int MIN_PASSWORD_LENGTH = 12;

  private record Seed(String username, Role role, String passengerRef) {}

  // Fixed references so demos, journey-service seeds and ownership tests can name a passenger.
  private static final Seed[] SEEDS = {
    new Seed("passenger", Role.PASSENGER, "P-00000000000000000001"),
    new Seed("passenger2", Role.PASSENGER, "P-00000000000000000002"),
    new Seed("agent", Role.AGENT, null),
    new Seed("dispatcher", Role.DISPATCHER, null),
    new Seed("admin", Role.ADMIN, null)
  };

  private final UserService users;
  private final SeedProperties properties;

  public SeedUsers(UserService users, SeedProperties properties) {
    this.users = users;
    this.properties = properties;
  }

  @Override
  public void run(ApplicationArguments args) {
    seed();
  }

  public void seed() {
    String password = properties.password();
    if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
      throw new IllegalStateException(
          "aerotrace.identity.seed.password must be set to at least "
              + MIN_PASSWORD_LENGTH
              + " characters when seeding is enabled");
    }
    int created = 0;
    for (Seed seed : SEEDS) {
      if (users.findByUsername(seed.username()).isEmpty()) {
        try {
          users.register(seed.username(), password, Set.of(seed.role()), seed.passengerRef());
          created++;
        } catch (ApiException e) {
          // Another replica starting at the same moment created it first: that is fine.
          if (e.errorCode() != ErrorCode.REQUEST_REJECTED) {
            throw e;
          }
        }
      }
    }
    log.warn(
        "Test users are enabled ({} created, {} already existed). They share one password: "
            + "never run this way in production.",
        created,
        SEEDS.length - created);
  }
}
