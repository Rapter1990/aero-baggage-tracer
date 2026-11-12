package com.aerotrace.identity.constant;

import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** The test users that seeding creates. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SeedConstants {

  public static final String PASSENGER = "passenger";
  public static final String PASSENGER_2 = "passenger2";
  public static final String AGENT = "agent";
  public static final String DISPATCHER = "dispatcher";
  public static final String ADMIN = "admin";

  /** Fixed references so demos, journey-service seeds and ownership tests can name a passenger. */
  public static final String PASSENGER_REF = "P-00000000000000000001";

  public static final String PASSENGER_2_REF = "P-00000000000000000002";

  public static final List<String> USERNAMES =
      List.of(PASSENGER, PASSENGER_2, AGENT, DISPATCHER, ADMIN);
}
