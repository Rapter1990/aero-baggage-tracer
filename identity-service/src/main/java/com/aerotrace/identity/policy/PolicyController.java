package com.aerotrace.identity.policy;

import com.aerotrace.common.api.ApiResponse;
import com.aerotrace.identity.model.dto.response.PolicyResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publishes who may do what, to any authenticated caller (clients use it to hide what is denied).
 */
@RestController
@Tag(name = "Account")
class PolicyController {

  private final AuthorizationPolicy policy;

  PolicyController(AuthorizationPolicy policy) {
    this.policy = policy;
  }

  @Operation(
      summary = "Who may do what across the platform",
      description = "Any signed-in role. The permission catalog with the roles that hold each.",
      security = @SecurityRequirement(name = "bearerAuth"))
  @GetMapping(value = "/api/v1/auth/policy", produces = MediaType.APPLICATION_JSON_VALUE)
  ApiResponse<PolicyResponse> policy() {
    return ApiResponse.success(new PolicyResponse(policy.catalog()));
  }
}
