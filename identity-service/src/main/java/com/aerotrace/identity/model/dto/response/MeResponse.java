package com.aerotrace.identity.model.dto.response;

import java.util.List;

/**
 * Who a token belongs to. Deliberately no passenger reference or other personal data.
 *
 * @param subject the user id from the token
 * @param roles the user's roles, sorted
 */
public record MeResponse(String subject, List<String> roles) {}
