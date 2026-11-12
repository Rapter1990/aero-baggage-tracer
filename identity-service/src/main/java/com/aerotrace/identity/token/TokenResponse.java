package com.aerotrace.identity.token;

import com.fasterxml.jackson.annotation.JsonProperty;

/** The OAuth-style token response. The token is kept out of {@link #toString()}. */
record TokenResponse(
    @JsonProperty("access_token") String accessToken,
    @JsonProperty("token_type") String tokenType,
    @JsonProperty("expires_in") long expiresIn) {

  @Override
  public String toString() {
    return "TokenResponse[tokenType=" + tokenType + ", expiresIn=" + expiresIn + "]";
  }
}
