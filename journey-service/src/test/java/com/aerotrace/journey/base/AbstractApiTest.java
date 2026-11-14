package com.aerotrace.journey.base;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.aerotrace.common.security.JwtClaims;
import com.aerotrace.common.security.Role;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The full application with MockMvc and every security filter in place, on a real PostgreSQL built
 * by Liquibase. Callers are given as tokens with a role (and a passenger reference); the signature
 * check itself is covered by {@code TokenVerificationTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractApiTest extends AbstractTestContainerConfiguration {

  protected static final String ISTANBUL = "IST";
  protected static final String FRANKFURT = "FRA";
  protected static final String LONDON = "LHR";

  @Autowired protected MockMvc mockMvc;
  @Autowired protected ObjectMapper json;
  @Autowired protected JdbcClient jdbc;

  /** A caller with this role (and, for a passenger, this reference). */
  protected static RequestPostProcessor as(Role role, String passengerRef) {
    return SecurityMockMvcRequestPostProcessors.jwt()
        .jwt(
            token -> {
              token.subject("user-" + role.name().toLowerCase());
              token.claim(JwtClaims.ROLES, List.of(role.name()));
              if (passengerRef != null) {
                token.claim(JwtClaims.PASSENGER_REF, passengerRef);
              }
            })
        .authorities(new SimpleGrantedAuthority(role.authority()));
  }

  protected static RequestPostProcessor as(Role role) {
    return as(role, null);
  }

  protected static String newBagTag() {
    return String.format("%010d", ThreadLocalRandom.current().nextLong(10_000_000_000L));
  }

  protected static String newPassengerRef() {
    return String.format(
        "P-%020d", ThreadLocalRandom.current().nextLong(Long.MAX_VALUE) % 100_000_000_000_000_000L);
  }

  protected static String newKey() {
    return "key-" + java.util.UUID.randomUUID();
  }

  /** IST to FRA, then FRA to LHR with a comfortable connection, all in the future. */
  protected static Map<String, Object> registration(String bagTag, String passengerRef) {
    Instant base = Instant.now().plusSeconds(86_400);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("bagTag", bagTag);
    body.put("passengerRef", passengerRef);
    body.put(
        "legs",
        List.of(
            leg("TK1234", ISTANBUL, FRANKFURT, base, base.plusSeconds(3 * 3600)),
            leg(
                "LH902",
                FRANKFURT,
                LONDON,
                base.plusSeconds(5 * 3600),
                base.plusSeconds(7 * 3600))));
    return body;
  }

  protected static Map<String, Object> leg(
      String flight, String from, String to, Instant departure, Instant arrival) {
    Map<String, Object> leg = new LinkedHashMap<>();
    leg.put("flightNumber", flight);
    leg.put("origin", from);
    leg.put("destination", to);
    leg.put("departureAt", departure.toString());
    leg.put("arrivalAt", arrival.toString());
    return leg;
  }

  /** A barcode scan of this bag at this time (any offset). */
  protected static Map<String, Object> barcodeScan(
      String bagTag, String airport, String checkpoint, long sequence, OffsetDateTime at) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("bagTag", bagTag);
    payload.put("airport", airport);
    payload.put("checkpoint", checkpoint);
    payload.put("sequence", sequence);
    payload.put("scannedAt", at.toString());
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("protocol", "BARCODE");
    body.put("payload", payload);
    return body;
  }

  protected static OffsetDateTime minutesAgo(long minutes) {
    return OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(minutes);
  }

  protected ResultActions registerBag(Map<String, Object> body, RequestPostProcessor caller)
      throws Exception {
    return mockMvc.perform(
        post("/api/v1/bags")
            .with(caller)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body)));
  }

  protected ResultActions scan(Map<String, Object> body, String key, RequestPostProcessor caller)
      throws Exception {
    var request =
        post("/api/v1/scans")
            .with(caller)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body));
    if (key != null) {
      request.header("Idempotency-Key", key);
    }
    return mockMvc.perform(request);
  }

  protected ResultActions journey(String bagTag, RequestPostProcessor caller) throws Exception {
    return mockMvc.perform(get("/api/v1/bags/{bagTag}/journey", bagTag).with(caller));
  }

  /** Registers a bag as an agent and returns its tag. */
  protected String registeredBag(String passengerRef) throws Exception {
    String bagTag = newBagTag();
    registerBag(registration(bagTag, passengerRef), as(Role.AGENT)).andReturn();
    return bagTag;
  }

  protected JsonNode body(ResultActions result) throws Exception {
    return json.readTree(result.andReturn().getResponse().getContentAsString());
  }

  protected int count(String sql, Object... params) {
    return jdbc.sql(sql).params(params).query(Integer.class).single();
  }
}
