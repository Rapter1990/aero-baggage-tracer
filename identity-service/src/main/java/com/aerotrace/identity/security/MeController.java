package com.aerotrace.identity.security;

import com.aerotrace.common.security.Role;
import com.aerotrace.common.security.SecurityContextHelper;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Who the presented token belongs to. Deliberately no passenger reference or other personal data.
 */
@RestController
class MeController {

  record Me(String subject, List<String> roles) {}

  @GetMapping(value = "/api/v1/auth/me", produces = MediaType.APPLICATION_JSON_VALUE)
  Me me() {
    return new Me(
        SecurityContextHelper.currentSubject().orElse(null),
        SecurityContextHelper.currentRoles().stream().map(Role::name).sorted().toList());
  }
}
