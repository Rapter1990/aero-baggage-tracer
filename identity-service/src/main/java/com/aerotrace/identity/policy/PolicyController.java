package com.aerotrace.identity.policy;

import com.aerotrace.common.api.ApiResponse;
import com.aerotrace.identity.constant.ApiPaths;
import com.aerotrace.identity.constant.DocsConstants;
import com.aerotrace.identity.model.dto.response.PolicyResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publishes who may do what, to any authenticated caller (clients use it to hide what is denied).
 */
@RestController
@RequiredArgsConstructor
@Tag(name = DocsConstants.TAG_ACCOUNT)
class PolicyController {

  private final AuthorizationPolicy policy;

  @Operation(
      summary = "Who may do what across the platform",
      description = "Any signed-in role. The permission catalog with the roles that hold each.",
      security = @SecurityRequirement(name = DocsConstants.BEARER_AUTH))
  @GetMapping(value = ApiPaths.POLICY, produces = MediaType.APPLICATION_JSON_VALUE)
  ApiResponse<PolicyResponse> policy() {
    return ApiResponse.success(new PolicyResponse(policy.catalog()));
  }
}
