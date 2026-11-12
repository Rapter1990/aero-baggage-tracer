package com.aerotrace.identity.keys;

import com.aerotrace.common.security.SecurityContextHelper;
import com.aerotrace.identity.audit.AuthAuditLog;
import com.aerotrace.identity.audit.AuthEventType;
import com.aerotrace.identity.audit.AuthOutcome;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Admin-only: introduce a new signing key now instead of waiting for the schedule. */
@RestController
class KeyAdminController {

  record Rotation(String kid) {}

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
  @PostMapping(value = "/api/v1/auth/keys/rotate", produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.ACCEPTED)
  Rotation rotate() {
    UUID caller = SecurityContextHelper.currentSubject().map(UUID::fromString).orElse(null);
    audit.record(AuthEventType.KEY_ROTATION_REQUESTED, AuthOutcome.SUCCESS, null, caller);
    return new Rotation(keys.rotate());
  }
}
