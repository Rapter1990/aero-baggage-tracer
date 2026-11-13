package com.aerotrace.identity.model.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * The tokens of a sign-in or refresh. Neither token is part of {@link #toString()}.
 *
 * @param accessToken the signed JWT; send it as {@code Authorization: Bearer <token>}
 * @param refreshToken a signed JWT (RS256, like the access token, but for another audience, so it
 *     is refused as an access token) that is exchanged once for a new pair
 * @param tokenType always {@code Bearer}
 * @param issuedAt when the tokens were issued
 * @param accessTokenExpiresAt when the access token expires
 * @param refreshTokenExpiresAt when the refresh token expires
 * @param roles the user roles, sorted
 */
public record TokenResponse(
    String accessToken,
    String refreshToken,
    String tokenType,
    Instant issuedAt,
    Instant accessTokenExpiresAt,
    Instant refreshTokenExpiresAt,
    List<String> roles) {

  @Override
  public String toString() {
    return "TokenResponse[tokenType=" + tokenType + ", roles=" + roles + "]";
  }
}
