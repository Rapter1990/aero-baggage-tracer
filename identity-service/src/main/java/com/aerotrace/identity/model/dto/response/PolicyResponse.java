package com.aerotrace.identity.model.dto.response;

import com.aerotrace.identity.policy.AuthorizationPolicy;
import java.util.List;

/**
 * Who may do what across the platform.
 *
 * @param permissions every permission with the roles that hold it
 */
public record PolicyResponse(List<AuthorizationPolicy.Entry> permissions) {}
