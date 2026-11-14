package com.aerotrace.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.common.error.ApiErrorWriter;
import com.aerotrace.gateway.ratelimit.RateLimiter.Decision;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * The stricter, per-endpoint bucket that guards the authentication endpoints, and the address it is
 * keyed on, without a server or Redis.
 */
class RateLimitFilterTest {

  private final List<String> generalKeys = new ArrayList<>();
  private final List<String> authKeys = new ArrayList<>();
  private Function<String, Decision> authDecision = key -> new Decision(true, 5);

  private final ApiErrorWriter errors = new ApiErrorWriter(JsonMapper.builder().build());

  private RateLimitFilter filter(List<String> trustedProxies) {
    return new RateLimitFilter(
        key -> {
          generalKeys.add(key);
          return new Decision(true, 10);
        },
        new AuthRateLimiter(
            key -> {
              authKeys.add(key);
              return authDecision.apply(key);
            }),
        new ClientAddressResolver(trustedProxies),
        errors);
  }

  private static MockHttpServletRequest post(String path, String peer) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
    request.setRemoteAddr(peer);
    return request;
  }

  private MockFilterChain run(
      RateLimitFilter filter, MockHttpServletRequest request, MockHttpServletResponse response)
      throws Exception {
    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(request, response, chain);
    return chain;
  }

  @Test
  void anAuthEndpointUsesItsOwnBucketPerEndpointAndAddress() throws Exception {
    RateLimitFilter filter = filter(List.of());

    run(filter, post("/api/v1/auth/login", "198.51.100.7"), new MockHttpServletResponse());
    run(filter, post("/api/v1/auth/register", "198.51.100.7"), new MockHttpServletResponse());
    run(filter, post("/api/v1/auth/login", "198.51.100.8"), new MockHttpServletResponse());

    assertThat(authKeys)
        .containsExactly(
            "auth:login:198.51.100.7", "auth:register:198.51.100.7", "auth:login:198.51.100.8");
    assertThat(generalKeys).hasSize(3);
  }

  @Test
  void otherEndpointsNeverTouchTheAuthBucket() throws Exception {
    RateLimitFilter filter = filter(List.of());

    run(
        filter,
        post("/api/v1/bags/AB123456/journey", "198.51.100.7"),
        new MockHttpServletResponse());

    assertThat(authKeys).isEmpty();
    assertThat(generalKeys).containsExactly("198.51.100.7");
  }

  @Test
  void aDeniedAuthCallIsA429WithRetryAfterAndNeverReachesTheService() throws Exception {
    authDecision = key -> new Decision(false, 0, 42);
    MockHttpServletResponse response = new MockHttpServletResponse();

    MockFilterChain chain =
        run(filter(List.of()), post("/api/v1/auth/login", "198.51.100.7"), response);

    assertThat(response.getStatus()).isEqualTo(429);
    assertThat(response.getHeader("Retry-After")).isEqualTo("42");
    assertThat(response.getContentAsString()).contains("\"code\":\"RATE_LIMITED\"");
    assertThat(chain.getRequest()).isNull();
    // The refused call does not also spend from the caller's general bucket.
    assertThat(generalKeys).isEmpty();
  }

  @Test
  void retryAfterIsAtLeastOneSecond() throws Exception {
    authDecision = key -> new Decision(false, 0, 0);
    MockHttpServletResponse response = new MockHttpServletResponse();

    run(filter(List.of()), post("/api/v1/auth/login", "198.51.100.7"), response);

    assertThat(response.getHeader("Retry-After")).isEqualTo("1");
  }

  @Test
  void anAllowedAuthCallStillCountsAgainstTheGeneralBucketAndReachesTheService() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();

    MockFilterChain chain =
        run(filter(List.of()), post("/api/v1/auth/login", "198.51.100.7"), response);

    assertThat(chain.getRequest()).isNotNull();
    assertThat(generalKeys).containsExactly("198.51.100.7");
  }

  @Test
  void aForgedForwardedForHeaderDoesNotChangeTheBucketUnlessTheProxyIsTrusted() throws Exception {
    MockHttpServletRequest untrusted = post("/api/v1/auth/login", "203.0.113.9");
    untrusted.addHeader("X-Forwarded-For", "6.6.6.6");
    MockHttpServletRequest again = post("/api/v1/auth/login", "203.0.113.9");
    again.addHeader("X-Forwarded-For", "7.7.7.7");

    run(filter(List.of("10.0.0.0/8")), untrusted, new MockHttpServletResponse());
    run(filter(List.of("10.0.0.0/8")), again, new MockHttpServletResponse());

    assertThat(authKeys).containsExactly("auth:login:203.0.113.9", "auth:login:203.0.113.9");
  }

  @Test
  void behindATrustedProxyTheRealClientAddressIsUsed() throws Exception {
    MockHttpServletRequest first = post("/api/v1/auth/login", "10.0.0.5");
    first.addHeader("X-Forwarded-For", "6.6.6.6, 203.0.113.7");
    MockHttpServletRequest second = post("/api/v1/auth/login", "10.0.0.5");
    second.addHeader("X-Forwarded-For", "5.5.5.5, 203.0.113.7");
    MockHttpServletRequest other = post("/api/v1/auth/login", "10.0.0.5");
    other.addHeader("X-Forwarded-For", "203.0.113.8");
    RateLimitFilter filter = filter(List.of("10.0.0.0/8"));

    run(filter, first, new MockHttpServletResponse());
    run(filter, second, new MockHttpServletResponse());
    run(filter, other, new MockHttpServletResponse());

    assertThat(authKeys)
        .containsExactly(
            "auth:login:203.0.113.7", "auth:login:203.0.113.7", "auth:login:203.0.113.8");
    assertThat(generalKeys).startsWith("203.0.113.7");
  }

  @Test
  void unknownPathsUnderAuthShareOneBucketSoTheyCannotMakeUnlimitedKeys() throws Exception {
    RateLimitFilter filter = filter(List.of());

    run(filter, post("/api/v1/auth/x1", "198.51.100.7"), new MockHttpServletResponse());
    run(filter, post("/api/v1/auth/x2", "198.51.100.7"), new MockHttpServletResponse());
    run(filter, post("/api/v1/auth/login/", "198.51.100.7"), new MockHttpServletResponse());

    assertThat(authKeys)
        .containsExactly(
            "auth:other:198.51.100.7", "auth:other:198.51.100.7", "auth:other:198.51.100.7");
  }

  @Test
  void everyDocumentedAuthEndpointHasItsOwnName() throws Exception {
    RateLimitFilter filter = filter(List.of());

    for (String endpoint :
        List.of(
            "login", "register", "refresh-token", "forgot-password", "reset-password", "logout")) {
      run(filter, post("/api/v1/auth/" + endpoint, "198.51.100.7"), new MockHttpServletResponse());
    }

    assertThat(authKeys)
        .containsExactly(
            "auth:login:198.51.100.7",
            "auth:register:198.51.100.7",
            "auth:refresh-token:198.51.100.7",
            "auth:forgot-password:198.51.100.7",
            "auth:reset-password:198.51.100.7",
            "auth:logout:198.51.100.7");
  }

  @Test
  void callsOutsideTheApiAreNotLimitedAtAll() throws Exception {
    run(filter(List.of()), post("/actuator/health", "198.51.100.7"), new MockHttpServletResponse());

    assertThat(authKeys).isEmpty();
    assertThat(generalKeys).isEmpty();
  }
}
