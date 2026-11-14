package com.aerotrace.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.identity.base.AbstractIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * Repeated wrong passwords and repeated reset requests are slowed down: the whole API over HTTP,
 * with the real database and the default limits (5 failed sign-ins, 3 reset requests).
 */
class LoginLockoutTest extends AbstractIntegrationTest {

  private static final String LOGIN = "/api/v1/auth/login";
  private static final String FORGOT = "/api/v1/auth/forgot-password";
  private static final String RESET = "/api/v1/auth/reset-password";
  private static final int MAX_FAILED = 5;
  private static final int MAX_RESET_REQUESTS = 3;

  @Autowired private JdbcClient jdbc;
  @Autowired private MeterRegistry meters;

  private HttpResponse<String> attempt(String name, String password) {
    return postJson(LOGIN, body("usernameOrEmail", name, "password", password));
  }

  private HttpResponse<String> forgot(String name) {
    return postJson(FORGOT, body("usernameOrEmail", name));
  }

  private void failTimes(String name, int times) {
    for (int i = 0; i < times; i++) {
      assertThat(attempt(name, "wrong-password-" + i).statusCode()).isEqualTo(401);
    }
  }

  @Test
  void wrongPasswordsLockTheNameAndEvenTheRightPasswordIsRefusedWith429() {
    String name = registerPassenger();

    failTimes(name, MAX_FAILED);
    HttpResponse<String> locked = attempt(name, PASSWORD);

    assertThat(locked.statusCode()).isEqualTo(429);
    JsonNode error = json(locked);
    assertThat(error.get("code").asString()).isEqualTo("RATE_LIMITED");
    assertThat(error.get("status").asInt()).isEqualTo(429);
    long retryAfter = Long.parseLong(locked.headers().firstValue("Retry-After").orElseThrow());
    assertThat(retryAfter).isBetween(1L, 15L * 60);
    assertThat(locked.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
    assertThat(locked.body()).doesNotContain("accessToken");
  }

  @Test
  void aNameThatDoesNotExistIsLockedAndAnswered_exactlyLikeARealOne() {
    String real = registerPassenger();
    String ghost = "ghost-" + unique();

    failTimes(real, MAX_FAILED);
    failTimes(ghost, MAX_FAILED);
    HttpResponse<String> realLocked = attempt(real, PASSWORD);
    HttpResponse<String> ghostLocked = attempt(ghost, PASSWORD);

    assertThat(realLocked.statusCode()).isEqualTo(429).isEqualTo(ghostLocked.statusCode());
    assertThat(json(ghostLocked).get("message").asString())
        .isEqualTo(json(realLocked).get("message").asString());
    assertThat(json(ghostLocked).get("code").asString())
        .isEqualTo(json(realLocked).get("code").asString());
  }

  @Test
  void theLockIsPerName_otherAccountsStillSignIn() {
    String victim = registerPassenger();
    String other = registerPassenger();

    failTimes(victim, MAX_FAILED);

    assertThat(attempt(victim, PASSWORD).statusCode()).isEqualTo(429);
    assertThat(attempt(other, PASSWORD).statusCode()).isEqualTo(200);
  }

  @Test
  void theNameIsMatchedWithoutRegardToCaseOrSpaces() {
    String name = registerPassenger();

    failTimes(name.toUpperCase(), MAX_FAILED);

    assertThat(attempt("  " + name + " ", PASSWORD).statusCode()).isEqualTo(429);
  }

  @Test
  void aSuccessfulSignInStartsTheCountAgain() {
    String name = registerPassenger();

    failTimes(name, MAX_FAILED - 1);
    assertThat(attempt(name, PASSWORD).statusCode()).isEqualTo(200);
    failTimes(name, MAX_FAILED - 1);

    assertThat(attempt(name, PASSWORD).statusCode()).isEqualTo(200);
  }

  @Test
  void theLockIsAuditedOnceNotForEveryRefusedAttempt() {
    String name = registerPassenger();

    failTimes(name, MAX_FAILED);
    for (int i = 0; i < 4; i++) {
      assertThat(attempt(name, PASSWORD).statusCode()).isEqualTo(429);
    }

    int locked =
        jdbc.sql(
                "select count(*) from identity.auth_audit "
                    + "where username = :u and event_type = 'LOGIN_LOCKED'")
            .param("u", name)
            .query(Integer.class)
            .single();
    int failed =
        jdbc.sql(
                "select count(*) from identity.auth_audit "
                    + "where username = :u and event_type = 'LOGIN_FAILED'")
            .param("u", name)
            .query(Integer.class)
            .single();
    assertThat(locked).isEqualTo(1);
    assertThat(failed).isEqualTo(MAX_FAILED);
  }

  @Test
  void refusedAttemptsAreCountedByReasonAndNeverByName() {
    String name = registerPassenger();
    double before = meters.counter("identity.auth.refused", "reason", "locked").count();

    failTimes(name, MAX_FAILED);
    attempt(name, PASSWORD);
    attempt(name, PASSWORD);

    assertThat(meters.counter("identity.auth.refused", "reason", "locked").count() - before)
        .isEqualTo(2.0);
    assertThat(
            meters.getMeters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .map(tag -> tag.getValue())
                .toList())
        .doesNotContain(name);
  }

  @Test
  void anAttackerCannotKeepALockGoingByAttemptingWhileItRuns() {
    String name = registerPassenger();
    failTimes(name, MAX_FAILED);
    long first =
        Long.parseLong(attempt(name, PASSWORD).headers().firstValue("Retry-After").orElseThrow());

    for (int i = 0; i < 20; i++) {
      attempt(name, "still-wrong-" + i);
    }
    long later =
        Long.parseLong(attempt(name, PASSWORD).headers().firstValue("Retry-After").orElseThrow());

    assertThat(later).isLessThanOrEqualTo(first);
  }

  @Test
  void passwordResetRequestsAreLimitedPerNameAndAnswerTheSameForUnknownNames() {
    String real = registerPassenger();
    String ghost = "ghost-" + unique();

    for (int i = 0; i < MAX_RESET_REQUESTS; i++) {
      assertThat(forgot(real).statusCode()).isEqualTo(200);
      assertThat(forgot(ghost).statusCode()).isEqualTo(200);
    }
    HttpResponse<String> realRefused = forgot(real);
    HttpResponse<String> ghostRefused = forgot(ghost);

    assertThat(realRefused.statusCode()).isEqualTo(429).isEqualTo(ghostRefused.statusCode());
    assertThat(json(realRefused).get("code").asString()).isEqualTo("RATE_LIMITED");
    assertThat(realRefused.headers().firstValue("Retry-After")).isPresent();
    assertThat(realRefused.body()).doesNotContain("resetToken");
    assertThat(json(ghostRefused).get("message").asString())
        .isEqualTo(json(realRefused).get("message").asString());
  }

  @Test
  void resettingWithAValidTokenIsNotBlockedByTheNameBeingLockedForSignIn() {
    String name = registerPassenger();
    String token = json(forgot(name)).get("data").get("resetToken").asString();
    failTimes(name, MAX_FAILED);

    HttpResponse<String> reset =
        postJson(RESET, body("resetToken", token, "newPassword", "brand-new-password-1"));

    assertThat(reset.statusCode()).isEqualTo(200);
  }
}
