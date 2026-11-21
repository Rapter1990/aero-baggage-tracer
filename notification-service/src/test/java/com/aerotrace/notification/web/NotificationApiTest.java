package com.aerotrace.notification.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aerotrace.common.security.JwtClaims;
import com.aerotrace.common.security.Role;
import com.aerotrace.notification.base.AbstractNotificationTest;
import com.aerotrace.notification.base.CaseEvents;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The notification API through the real application, MockMvc and a real PostgreSQL built by
 * Liquibase: a user reads only their own notifications, newest first; the role x endpoint matrix;
 * paging limits. The real token decoder is {@link TokenVerificationTest}'s job.
 */
@SpringBootTest
@AutoConfigureMockMvc
class NotificationApiTest extends AbstractNotificationTest {

  private static final String NOTIFICATIONS = "/api/v1/notifications";

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper json;
  @MockitoBean KafkaTemplate<String, String> kafka;

  // ------------------------------------------------------------------ helpers

  private static RequestPostProcessor as(Role role, String subject) {
    return SecurityMockMvcRequestPostProcessors.jwt()
        .jwt(
            token -> {
              token.subject(subject);
              token.claim(JwtClaims.ROLES, List.of(role.name()));
            })
        .authorities(new SimpleGrantedAuthority(role.authority()));
  }

  private JsonNode data(ResultActions result) throws Exception {
    return json.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
  }

  private static String user() {
    return "user-" + UUID.randomUUID();
  }

  /**
   * One notification per minute for {@code recipient}, oldest first; returns the ids in that order.
   */
  private List<UUID> seedFor(String recipient, int count) {
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      ids.add(
          seed(
              recipient,
              CaseEvents.eventId(),
              "CASE_OPENED",
              clock.instant().plus(Duration.ofMinutes(i))));
    }
    return ids;
  }

  private ResultActions list(RequestPostProcessor caller, String... params) throws Exception {
    var request = get(NOTIFICATIONS).with(caller);
    for (int i = 0; i + 1 < params.length; i += 2) {
      request.param(params[i], params[i + 1]);
    }
    return mockMvc.perform(request);
  }

  // ------------------------------------------------------------------ listing

  @Test
  @DisplayName("BR-08 a user lists only their own notifications, newest first")
  void userListsOnlyOwnNewestFirst() throws Exception {
    String me = user();
    String someoneElse = user();
    List<UUID> mine = seedFor(me, 3);
    seedFor(someoneElse, 2);

    ResultActions result = list(as(Role.AGENT, me)).andExpect(status().isOk());

    JsonNode items = data(result).get("items");
    assertThat(items).hasSize(3);
    List<String> listed = new ArrayList<>();
    items.forEach(item -> listed.add(item.get("id").asString()));
    assertThat(listed)
        .containsExactly(mine.get(2).toString(), mine.get(1).toString(), mine.get(0).toString());
    assertThat(data(result).get("totalElements").asLong()).isEqualTo(3);
  }

  @Test
  @DisplayName("BR-08 a notification shows its case, template and status, and not its recipient")
  void notificationShowsItsCase() throws Exception {
    String me = user();
    UUID id = seedFor(me, 1).getFirst();

    JsonNode item =
        data(list(as(Role.DISPATCHER, me)).andExpect(status().isOk())).get("items").get(0);

    assertThat(item.get("id").asString()).isEqualTo(id.toString());
    assertThat(item.get("template").asString()).isEqualTo("CASE_OPENED");
    assertThat(item.get("status").asString()).isEqualTo("PENDING");
    assertThat(item.get("caseId").asString()).isNotBlank();
    assertThat(item.has("recipient")).isFalse();
  }

  @Test
  @DisplayName("BR-08 a user with no notifications gets an empty page, not an error")
  void userWithoutNotificationsGetsAnEmptyPage() throws Exception {
    ResultActions result = list(as(Role.AGENT, user())).andExpect(status().isOk());

    assertThat(data(result).get("items")).isEmpty();
    assertThat(data(result).get("totalElements").asLong()).isZero();
  }

  @Test
  @DisplayName("BR-08 paging walks through the user's notifications, newest first")
  void pagingWalksThroughTheNotifications() throws Exception {
    String me = user();
    List<UUID> mine = seedFor(me, 3);

    JsonNode first = data(list(as(Role.AGENT, me), "page", "0", "size", "2"));
    JsonNode second = data(list(as(Role.AGENT, me), "page", "1", "size", "2"));

    assertThat(first.get("items")).hasSize(2);
    assertThat(first.get("totalPages").asInt()).isEqualTo(2);
    assertThat(first.get("items").get(0).get("id").asString()).isEqualTo(mine.get(2).toString());
    assertThat(second.get("items")).hasSize(1);
    assertThat(second.get("items").get(0).get("id").asString()).isEqualTo(mine.get(0).toString());
  }

  // ------------------------------------------------------------------ one notification

  @Test
  @DisplayName("BR-08 a user reads their own notification by id")
  void userReadsOwnNotification() throws Exception {
    String me = user();
    UUID id = seedFor(me, 1).getFirst();

    mockMvc
        .perform(get(NOTIFICATIONS + "/{id}", id).with(as(Role.PASSENGER, me)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(id.toString()))
        .andExpect(jsonPath("$.data.template").value("CASE_OPENED"));
  }

  @Test
  @DisplayName("BR-08 another user's notification id is a 404, never a 403, so it does not leak")
  void anotherUsersIdIsNotFound() throws Exception {
    UUID theirs = seedFor(user(), 1).getFirst();

    mockMvc
        .perform(get(NOTIFICATIONS + "/{id}", theirs).with(as(Role.ADMIN, user())))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  @DisplayName("BR-08 another user's id and an id that does not exist answer the same way")
  void foreignAndUnknownIdsLookAlike() throws Exception {
    String me = user();
    UUID theirs = seedFor(user(), 1).getFirst();

    String foreign =
        mockMvc
            .perform(get(NOTIFICATIONS + "/{id}", theirs).with(as(Role.AGENT, me)))
            .andExpect(status().isNotFound())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String unknown =
        mockMvc
            .perform(get(NOTIFICATIONS + "/{id}", UUID.randomUUID()).with(as(Role.AGENT, me)))
            .andExpect(status().isNotFound())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(json.readTree(foreign).get("code")).isEqualTo(json.readTree(unknown).get("code"));
    assertThat(json.readTree(foreign).get("status"))
        .isEqualTo(json.readTree(unknown).get("status"));
  }

  // ------------------------------------------------------------------ security matrix

  @ParameterizedTest
  @EnumSource(Role.class)
  @DisplayName("BR-08 every role may list and read its own notifications")
  void everyRoleReadsItsOwn(Role role) throws Exception {
    String me = user();
    UUID id = seedFor(me, 1).getFirst();

    list(as(role, me)).andExpect(status().isOk());
    mockMvc.perform(get(NOTIFICATIONS + "/{id}", id).with(as(role, me))).andExpect(status().isOk());
  }

  @ParameterizedTest
  @EnumSource(Role.class)
  @DisplayName("BR-08 no role, ADMIN included, reads another user's notification")
  void noRoleReadsAnotherUsersNotification(Role role) throws Exception {
    UUID theirs = seedFor(user(), 1).getFirst();

    mockMvc
        .perform(get(NOTIFICATIONS + "/{id}", theirs).with(as(role, user())))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("AT-16 no token is a 401")
  void noTokenIsUnauthorized() throws Exception {
    UUID id = seedFor(user(), 1).getFirst();

    mockMvc
        .perform(get(NOTIFICATIONS))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    mockMvc
        .perform(get(NOTIFICATIONS + "/{id}", id))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
  }

  @Test
  @DisplayName("BR-08 notifications cannot be written through the API")
  void apiIsReadOnly() throws Exception {
    String me = user();
    UUID id = seedFor(me, 1).getFirst();
    RequestPostProcessor admin = as(Role.ADMIN, me);

    mockMvc
        .perform(post(NOTIFICATIONS).with(admin).contentType("application/json").content("{}"))
        .andExpect(status().is4xxClientError());
    mockMvc
        .perform(
            put(NOTIFICATIONS + "/{id}", id)
                .with(admin)
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().is4xxClientError());
    mockMvc
        .perform(delete(NOTIFICATIONS + "/{id}", id).with(admin))
        .andExpect(status().is4xxClientError());
    assertThat(notifications.count()).isEqualTo(1);
  }

  // ------------------------------------------------------------------ paging limits

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1", "101", "5000", "abc"})
  @DisplayName("BR-08 a page size outside 1..100 is a 400 VALIDATION_ERROR")
  void pageSizeOutsideTheRangeIsRefused(String size) throws Exception {
    list(as(Role.AGENT, user()), "size", size)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"1", "50", "100"})
  @DisplayName("BR-08 a page size from 1 to 100 is accepted")
  void pageSizeInsideTheRangeIsAccepted(String size) throws Exception {
    ResultActions result = list(as(Role.AGENT, user()), "size", size).andExpect(status().isOk());

    assertThat(data(result).get("size").asInt()).isEqualTo(Integer.parseInt(size));
  }

  @Test
  @DisplayName("BR-08 a negative page number is a 400 VALIDATION_ERROR")
  void negativePageIsRefused() throws Exception {
    list(as(Role.AGENT, user()), "page", "-1")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }
}
