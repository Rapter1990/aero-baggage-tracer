package com.aerotrace.identity.seed;

import com.aerotrace.common.error.ApiException;
import com.aerotrace.common.error.ErrorCode;
import com.aerotrace.common.security.Role;
import com.aerotrace.identity.constant.SeedConstants;
import com.aerotrace.identity.constant.UserRules;
import com.aerotrace.identity.user.UserService;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Creates the test users (one per role, plus a second passenger so ownership can be tested) when
 * {@code aerotrace.identity.seed.enabled=true}. Users that already exist are left untouched, so it
 * is safe to run on every start. All of them share the configured password, which is hashed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "aerotrace.identity.seed", name = "enabled", havingValue = "true")
public class SeedUsers implements ApplicationRunner {

  private record Seed(String username, Role role, String passengerRef) {}

  private static final List<Seed> SEEDS =
      List.of(
          new Seed(SeedConstants.PASSENGER, Role.PASSENGER, SeedConstants.PASSENGER_REF),
          new Seed(SeedConstants.PASSENGER_2, Role.PASSENGER, SeedConstants.PASSENGER_2_REF),
          new Seed(SeedConstants.AGENT, Role.AGENT, null),
          new Seed(SeedConstants.DISPATCHER, Role.DISPATCHER, null),
          new Seed(SeedConstants.ADMIN, Role.ADMIN, null));

  private final UserService users;
  private final SeedProperties properties;

  @Override
  public void run(ApplicationArguments args) {
    seed();
  }

  public void seed() {
    String password = properties.password();
    if (password == null || password.length() < UserRules.MIN_PASSWORD_LENGTH) {
      throw new IllegalStateException(
          "aerotrace.identity.seed.password must be set to at least "
              + UserRules.MIN_PASSWORD_LENGTH
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
        SEEDS.size() - created);
  }
}
