package com.aerotrace.identity.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aerotrace.identity.base.AbstractRestControllerTest;
import com.aerotrace.identity.model.dto.response.ForgotPasswordResponse;
import com.aerotrace.identity.model.dto.response.LogoutResponse;
import com.aerotrace.identity.model.dto.response.RegisteredUserResponse;
import com.aerotrace.identity.model.dto.response.TokenResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The controller on its own: request validation, the envelope and the status codes, with the
 * service mocked and the security filters off.
 */
class AuthControllerTest extends AbstractRestControllerTest {

  @MockitoBean private AuthService authService;

  private ResultActions submit(String path, String json) throws Exception {
    return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json));
  }

  private static final String VALID_REGISTER =
      """
      {"username":"jane.doe","email":"jane@example.com","password":"correct-horse-battery",\
      "fullName":"Jane Doe"}""";

  private static final Instant NOW = Instant.parse("2026-11-12T10:15:30Z");

  @Test
  void shouldRegisterAndAnswer201InTheEnvelope() throws Exception {
    when(authService.register(any(), any()))
        .thenReturn(
            new RegisteredUserResponse(
                UUID.randomUUID(),
                "jane.doe",
                "jane@example.com",
                "Jane Doe",
                List.of("PASSENGER"),
                "ACTIVE",
                NOW));

    submit("/api/v1/auth/register", VALID_REGISTER)
        .andExpect(status().isCreated())
        .andExpect(
            header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.username").value("jane.doe"))
        .andExpect(jsonPath("$.data.roles[0]").value("PASSENGER"))
        .andExpect(jsonPath("$.timestamp").exists());
  }

  @Test
  void shouldNameEveryInvalidRegistrationFieldAndNeverEchoTheValue() throws Exception {
    submit(
            "/api/v1/auth/register",
            """
            {"username":"a b","email":"not-an-email","password":"short","fullName":" "}""")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.validationErrors.username").exists())
        .andExpect(jsonPath("$.validationErrors.email").exists())
        .andExpect(jsonPath("$.validationErrors.password").exists())
        .andExpect(jsonPath("$.validationErrors.fullName").exists())
        .andExpect(
            result -> {
              String body = result.getResponse().getContentAsString();
              org.assertj.core.api.Assertions.assertThat(body)
                  .doesNotContain("not-an-email")
                  .doesNotContain("short");
            });
    verify(authService, never()).register(any(), any());
  }

  @Test
  void shouldRejectAnUnknownRoleName() throws Exception {
    submit(
            "/api/v1/auth/register",
            """
            {"username":"jane.doe","email":"jane@example.com","password":"correct-horse-battery",\
            "fullName":"Jane Doe","role":"ROOT"}""")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").exists());
  }

  @Test
  void shouldLoginAndReturnBothTokens() throws Exception {
    when(authService.login(any()))
        .thenReturn(
            new TokenResponse(
                "access",
                "refresh",
                "Bearer",
                NOW,
                NOW.plusSeconds(900),
                NOW.plusSeconds(600000),
                List.of("PASSENGER")));

    submit("/api/v1/auth/login", "{\"usernameOrEmail\":\"jane\",\"password\":\"anything\"}")
        .andExpect(status().isOk())
        .andExpect(header().string("Pragma", "no-cache"))
        .andExpect(jsonPath("$.data.accessToken").value("access"))
        .andExpect(jsonPath("$.data.refreshToken").value("refresh"))
        .andExpect(jsonPath("$.data.tokenType").value("Bearer"));
  }

  @Test
  void shouldRejectABlankLoginAndAnOversizedOne() throws Exception {
    submit("/api/v1/auth/login", "{}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.validationErrors.usernameOrEmail").value("must not be blank"))
        .andExpect(jsonPath("$.validationErrors.password").value("must not be blank"));
    submit(
            "/api/v1/auth/login",
            "{\"usernameOrEmail\":\"jane\",\"password\":\"" + "x".repeat(300) + "\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.validationErrors.password").exists());
    verify(authService, never()).login(any());
  }

  @Test
  void shouldRefreshForgotResetAndLogoutThroughTheService() throws Exception {
    TokenResponse tokens =
        new TokenResponse("a", "r", "Bearer", NOW, NOW, NOW, List.of("PASSENGER"));
    when(authService.refreshToken(any())).thenReturn(tokens);
    when(authService.forgotPassword(any()))
        .thenReturn(new ForgotPasswordResponse("started", null, null));
    when(authService.resetPassword(any())).thenReturn(new LogoutResponse("reset done"));
    when(authService.logout(any())).thenReturn(new LogoutResponse("bye"));

    submit("/api/v1/auth/refresh-token", "{\"refreshToken\":\"abc\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.refreshToken").value("r"));
    submit("/api/v1/auth/forgot-password", "{\"usernameOrEmail\":\"jane\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.message").value("started"))
        .andExpect(jsonPath("$.data.resetToken").doesNotExist());
    submit(
            "/api/v1/auth/reset-password",
            "{\"resetToken\":\"abc\",\"newPassword\":\"a-brand-new-password\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.message").value("reset done"));
    submit("/api/v1/auth/logout", "{\"refreshToken\":\"abc\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.message").value("bye"));
  }

  @Test
  void shouldRejectAWeakNewPasswordAndABlankToken() throws Exception {
    submit("/api/v1/auth/reset-password", "{\"resetToken\":\"\",\"newPassword\":\"short\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.validationErrors.resetToken").exists())
        .andExpect(jsonPath("$.validationErrors.newPassword").exists());
    submit("/api/v1/auth/refresh-token", "{}").andExpect(status().isBadRequest());
    submit("/api/v1/auth/logout", "{}").andExpect(status().isBadRequest());
    submit("/api/v1/auth/forgot-password", "{}").andExpect(status().isBadRequest());
  }

  @Test
  void shouldRefuseMalformedJsonAndOtherContentTypes() throws Exception {
    submit("/api/v1/auth/login", "{not json").andExpect(status().isBadRequest());
    mockMvc
        .perform(post("/api/v1/auth/login").contentType(MediaType.TEXT_PLAIN).content("x"))
        .andExpect(status().isUnsupportedMediaType());
  }

  @Test
  void shouldNeverPrintCredentialsInRequestToString() {
    String text =
        new com.aerotrace.identity.model.dto.request.LoginRequest("jane", "secret-pw-1").toString();
    org.assertj.core.api.Assertions.assertThat(text)
        .doesNotContain("secret-pw-1")
        .doesNotContain("jane");
  }
}
