package com.aerotrace.identity.keys;

import com.aerotrace.common.api.ApiResponse;
import com.aerotrace.common.security.SecurityContextHelper;
import com.aerotrace.identity.audit.AuthAuditLog;
import com.aerotrace.identity.audit.AuthEventType;
import com.aerotrace.identity.audit.AuthOutcome;
import com.aerotrace.identity.model.dto.response.KeyRotationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Admin-only: introduce a new signing key now instead of waiting for the schedule. */
@RestController
@Tag(name = "Signing keys")
class KeyAdminController {

  private final SigningKeyService keys;
  private final AuthAuditLog audit;

  KeyAdminController(SigningKeyService keys, AuthAuditLog audit) {
    this.keys = keys;
    this.audit = audit;
  }

  /**
   * Accepted, not done: the new key is published at once but only starts signing after the publish
   * lead. Asking again before then returns the same key.
   */
  @Operation(
      summary = "Introduce a new signing key",
      description =
          "ADMIN only. The key is published at once and starts signing after the publish lead; "
              + "the key it replaces stays published until its last token has expired.",
      security = @SecurityRequirement(name = "bearerAuth"))
  @PostMapping(value = "/api/v1/auth/keys/rotate", produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.ACCEPTED)
  ApiResponse<KeyRotationResponse> rotate() {
    UUID caller = SecurityContextHelper.currentSubject().map(UUID::fromString).orElse(null);
    audit.record(AuthEventType.KEY_ROTATION_REQUESTED, AuthOutcome.SUCCESS, null, caller);
    return ApiResponse.success(new KeyRotationResponse(keys.rotate()));
  }
}
