package com.aerotrace.identity.docs;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Example response bodies shown in the OpenAPI definition. A test compares the error examples with
 * what the service really returns, so they stay true. Values such as the correlation id are
 * illustrative.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ApiExamples {

  public static final String UNAUTHENTICATED =
      """
      {"code":"UNAUTHENTICATED","message":"Authentication required","status":401,\
      "path":"/api/v1/auth/register","validationErrors":{},\
      "timestamp":"2026-11-12T10:15:30.123Z","correlationId":"b6c1d1e0-3a4f-4f0e-9d2a-5d7f0c2f8a11"}\
      """;

  public static final String ACCESS_DENIED =
      """
      {"code":"ACCESS_DENIED","message":"Insufficient permissions","status":403,\
      "path":"/api/v1/auth/register","validationErrors":{},\
      "timestamp":"2026-11-12T10:15:30.123Z","correlationId":"b6c1d1e0-3a4f-4f0e-9d2a-5d7f0c2f8a11"}\
      """;

  /** Field name to rule; never the value that was sent. */
  public static final String LOGIN_VALIDATION_ERROR =
      """
      {"code":"VALIDATION_ERROR","message":"Request validation failed","status":400,\
      "path":"/api/v1/auth/login",\
      "validationErrors":{"usernameOrEmail":"must not be blank","password":"must not be blank"},\
      "timestamp":"2026-11-12T10:15:30.123Z","correlationId":"b6c1d1e0-3a4f-4f0e-9d2a-5d7f0c2f8a11"}\
      """;

  public static final String REGISTER_VALIDATION_ERROR =
      """
      {"code":"VALIDATION_ERROR","message":"Request validation failed","status":400,\
      "path":"/api/v1/auth/register",\
      "validationErrors":{"password":"must be at least 12 characters and at most 72 bytes"},\
      "timestamp":"2026-11-12T10:15:30.123Z","correlationId":"b6c1d1e0-3a4f-4f0e-9d2a-5d7f0c2f8a11"}\
      """;

  public static final String USER_ALREADY_EXISTS =
      """
      {"code":"USER_ALREADY_EXISTS","message":"That username or email is already registered",\
      "status":409,"path":"/api/v1/auth/register","validationErrors":{},\
      "timestamp":"2026-11-12T10:15:30.123Z","correlationId":"b6c1d1e0-3a4f-4f0e-9d2a-5d7f0c2f8a11"}\
      """;

  /** The same answer for an unknown user, a wrong password and a disabled account. */
  public static final String INVALID_CREDENTIALS =
      """
      {"code":"UNAUTHENTICATED","message":"Invalid credentials.","status":401,\
      "path":"/api/v1/auth/login","validationErrors":{},\
      "timestamp":"2026-11-12T10:15:30.123Z","correlationId":"b6c1d1e0-3a4f-4f0e-9d2a-5d7f0c2f8a11"}\
      """;

  public static final String INVALID_REFRESH_TOKEN =
      """
      {"code":"UNAUTHENTICATED","message":"Invalid or expired refresh token.","status":401,\
      "path":"/api/v1/auth/refresh-token","validationErrors":{},\
      "timestamp":"2026-11-12T10:15:30.123Z","correlationId":"b6c1d1e0-3a4f-4f0e-9d2a-5d7f0c2f8a11"}\
      """;

  public static final String INVALID_RESET_TOKEN =
      """
      {"code":"VALIDATION_ERROR","message":"Invalid or expired password reset token.",\
      "status":400,"path":"/api/v1/auth/reset-password","validationErrors":{},\
      "timestamp":"2026-11-12T10:15:30.123Z","correlationId":"b6c1d1e0-3a4f-4f0e-9d2a-5d7f0c2f8a11"}\
      """;

  public static final String REGISTERED =
      """
      {"success":true,"data":{"id":"3f0c5e1e-8d1b-4b0e-9a55-0c7c1d1f6a10","username":"ayse.kaya",\
      "email":"ayse.kaya@example.com","fullName":"Ayse Kaya","roles":["PASSENGER"],\
      "status":"ACTIVE","createdAt":"2026-11-12T10:15:30.123Z"},\
      "timestamp":"2026-11-12T10:15:30.123Z"}\
      """;

  /** Placeholders: the shape of the answer, never usable tokens. */
  public static final String TOKEN_ISSUED =
      """
      {"success":true,"data":{"accessToken":"<signed JWT: header.payload.signature>",\
      "refreshToken":"<opaque random token>","tokenType":"Bearer",\
      "issuedAt":"2026-11-12T10:15:30Z","accessTokenExpiresAt":"2026-11-12T10:30:30Z",\
      "refreshTokenExpiresAt":"2026-11-19T10:15:30Z","roles":["PASSENGER"]},\
      "timestamp":"2026-11-12T10:15:30.123Z"}\
      """;

  public static final String PASSWORD_RESET_STARTED =
      """
      {"success":true,"data":{"message":"If the account exists, a password reset has been started."},\
      "timestamp":"2026-11-12T10:15:30.123Z"}\
      """;

  public static final String JWKS =
      """
      {"keys":[{"kty":"RSA","use":"sig","alg":"RS256","kid":"<RFC 7638 thumbprint>",\
      "n":"<base64url modulus>","e":"AQAB"}]}\
      """;
}
