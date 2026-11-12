package com.aerotrace.identity.security;

import com.aerotrace.common.api.ApiResponse;
import com.aerotrace.common.security.Role;
import com.aerotrace.common.security.SecurityContextHelper;
import com.aerotrace.identity.model.dto.response.MeResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Who the presented token belongs to. Deliberately no passenger reference or other personal data.
 */
@RestController
@Tag(name = "Account")
class MeController {

  @Operation(
      summary = "Who the presented token belongs to",
      description = "Any signed-in role. Returns the subject and roles only.",
      security = @SecurityRequirement(name = "bearerAuth"))
  @GetMapping(value = "/api/v1/auth/me", produces = MediaType.APPLICATION_JSON_VALUE)
  ApiResponse<MeResponse> me() {
    return ApiResponse.success(
        new MeResponse(
            SecurityContextHelper.currentSubject().orElse(null),
            SecurityContextHelper.currentRoles().stream().map(Role::name).sorted().toList()));
  }
}
