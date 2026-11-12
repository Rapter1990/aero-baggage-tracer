package com.aerotrace.identity.docs;

/**
 * Example response bodies shown in the OpenAPI definition. A test compares each with what the
 * service really returns, so they stay true. Values such as the correlation id are illustrative.
 */
public final class ApiExamples {

  private ApiExamples() {}

  public static final String UNAUTHENTICATED =
      """
      {"code":"UNAUTHENTICATED","message":"Authentication required","status":401,\
      "path":"/api/v1/auth/me","validationErrors":{},\
      "timestamp":"2026-11-12T10:15:30.123Z","correlationId":"b6c1d1e0-3a4f-4f0e-9d2a-5d7f0c2f8a11"}\
      """;

  public static final String ACCESS_DENIED =
      """
      {"code":"ACCESS_DENIED","message":"Insufficient permissions","status":403,\
      "path":"/api/v1/auth/keys/rotate","validationErrors":{},\
      "timestamp":"2026-11-12T10:15:30.123Z","correlationId":"b6c1d1e0-3a4f-4f0e-9d2a-5d7f0c2f8a11"}\
      """;

  /** Field name to rule; never the value that was sent. */
  public static final String TOKEN_VALIDATION_ERROR =
      """
      {"code":"VALIDATION_ERROR","message":"Request validation failed","status":400,\
      "path":"/api/v1/auth/token",\
      "validationErrors":{"username":"must not be blank","password":"must not be blank"},\
      "timestamp":"2026-11-12T10:15:30.123Z","correlationId":"b6c1d1e0-3a4f-4f0e-9d2a-5d7f0c2f8a11"}\
      """;

  /** The same answer for an unknown user, a wrong password and a disabled account. */
  public static final String TOKEN_INVALID_CREDENTIALS =
      """
      {"code":"UNAUTHENTICATED","message":"Invalid credentials.","status":401,\
      "path":"/api/v1/auth/token","validationErrors":{},\
      "timestamp":"2026-11-12T10:15:30.123Z","correlationId":"b6c1d1e0-3a4f-4f0e-9d2a-5d7f0c2f8a11"}\
      """;

  /** A placeholder: the shape of the answer, never a usable token. */
  public static final String TOKEN_ISSUED =
      """
      {"success":true,"data":{"accessToken":"<signed JWT: header.payload.signature>",\
      "tokenType":"Bearer","expiresIn":900},"timestamp":"2026-11-12T10:15:30.123Z"}\
      """;

  public static final String JWKS =
      """
      {"keys":[{"kty":"RSA","use":"sig","alg":"RS256","kid":"<RFC 7638 thumbprint>",\
      "n":"<base64url modulus>","e":"AQAB"}]}\
      """;
}
