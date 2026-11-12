package com.aerotrace.identity.config;

import com.aerotrace.identity.seed.SeedUsers;
import com.aerotrace.identity.user.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Stops the service if a test account created by an earlier development run still accepts the
 * published development password. Turning seeding off, or changing the seed password, does not
 * change accounts that already exist: a database that ever ran the development stack keeps a
 * working {@code admin} login for anyone who has read this repository.
 *
 * <p>Only the five known test usernames are checked, and only against the one published password;
 * it cannot know about any other weak password. A development run sets {@code
 * aerotrace.identity.allow-dev-defaults=true}.
 */
@Component
class SeededAccountsGuard implements ApplicationRunner {

  private final UserService users;
  private final boolean allowDevDefaults;

  SeededAccountsGuard(
      UserService users,
      @Value("${aerotrace.identity.allow-dev-defaults:false}") boolean allowDevDefaults) {
    this.users = users;
    this.allowDevDefaults = allowDevDefaults;
  }

  @Override
  public void run(ApplicationArguments args) {
    check();
  }

  void check() {
    if (allowDevDefaults) {
      return;
    }
    for (String username : SeedUsers.USERNAMES) {
      users
          .findByUsername(username)
          .filter(user -> users.passwordMatches(user, DevDefaultsGuard.PUBLISHED_SEED_PASSWORD))
          .ifPresent(
              user -> {
                throw new IllegalStateException(
                    "The account '"
                        + username
                        + "' still accepts the published development password, left by an earlier"
                        + " development run. Change its password or remove it from the database,"
                        + " or set IDENTITY_ALLOW_DEV_DEFAULTS=true for a throwaway development"
                        + " run.");
              });
    }
  }
}
