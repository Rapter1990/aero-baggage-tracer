package com.aerotrace.identity.policy;

import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publishes who may do what, to any authenticated caller (clients use it to hide what is denied).
 */
@RestController
class PolicyController {

  record PolicyResponse(List<AuthorizationPolicy.Entry> permissions) {}

  private final AuthorizationPolicy policy;

  PolicyController(AuthorizationPolicy policy) {
    this.policy = policy;
  }

  @GetMapping(value = "/api/v1/auth/policy", produces = MediaType.APPLICATION_JSON_VALUE)
  PolicyResponse policy() {
    return new PolicyResponse(policy.catalog());
  }
}
