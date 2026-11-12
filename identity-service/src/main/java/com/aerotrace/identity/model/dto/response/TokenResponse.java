package com.aerotrace.identity.model.dto.response;

/**
 * The issued access token. The token is kept out of {@link #toString()}.
 *
 * @param accessToken the signed JWT; send it as {@code Authorization: Bearer <token>}
 * @param tokenType always {@code Bearer}
 * @param expiresIn seconds until the token expires
 */
public record TokenResponse(String accessToken, String tokenType, long expiresIn) {

  @Override
  public String toString() {
    return "TokenResponse[tokenType=" + tokenType + ", expiresIn=" + expiresIn + "]";
  }
}
