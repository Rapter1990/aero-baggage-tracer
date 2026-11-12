package com.aerotrace.identity.docs;

/**
 * Example response bodies shown in the OpenAPI definition. A test compares each with what the
 * service really returns, so they stay true. Values such as the correlation id are illustrative.
 */
public final class ProblemExamples {

  private ProblemExamples() {}

  public static final String UNAUTHENTICATED =
      """
      {"type":"urn:aerotrace:problem:UNAUTHENTICATED","title":"Authentication required",\
      "status":401,"detail":"Authentication required","errorCode":"UNAUTHENTICATED",\
      "correlationId":"b6c1d1e0-3a4f-4f0e-9d2a-5d7f0c2f8a11","timestamp":"2026-11-12T10:15:30.123Z"}\
      """;

  public static final String ACCESS_DENIED =
      """
      {"type":"urn:aerotrace:problem:ACCESS_DENIED","title":"Access denied",\
      "status":403,"detail":"Insufficient permissions","errorCode":"ACCESS_DENIED",\
      "correlationId":"b6c1d1e0-3a4f-4f0e-9d2a-5d7f0c2f8a11","timestamp":"2026-11-12T10:15:30.123Z"}\
      """;

  public static final String TOKEN_VALIDATION_FAILED =
      """
      {"type":"urn:aerotrace:problem:VALIDATION_FAILED","title":"Request validation failed",\
      "status":400,"detail":"username and password are required","instance":"/api/v1/auth/token","errorCode":"VALIDATION_FAILED",\
      "correlationId":"b6c1d1e0-3a4f-4f0e-9d2a-5d7f0c2f8a11","timestamp":"2026-11-12T10:15:30.123Z"}\
      """;

  /** The same answer for an unknown user, a wrong password and a disabled account. */
  public static final String TOKEN_INVALID_CREDENTIALS =
      """
      {"type":"urn:aerotrace:problem:UNAUTHENTICATED","title":"Authentication required",\
      "status":401,"detail":"Invalid credentials.","instance":"/api/v1/auth/token","errorCode":"UNAUTHENTICATED",\
      "correlationId":"b6c1d1e0-3a4f-4f0e-9d2a-5d7f0c2f8a11","timestamp":"2026-11-12T10:15:30.123Z"}\
      """;

  /** A placeholder: the shape of the answer, never a usable token. */
  public static final String TOKEN_ISSUED =
      """
      {"access_token":"<signed JWT: header.payload.signature>","token_type":"Bearer","expires_in":900}\
      """;

  public static final String JWKS =
      """
      {"keys":[{"kty":"RSA","use":"sig","alg":"RS256","kid":"<RFC 7638 thumbprint>",\
      "n":"<base64url modulus>","e":"AQAB"}]}\
      """;
}
