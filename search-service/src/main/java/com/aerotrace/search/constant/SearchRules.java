package com.aerotrace.search.constant;

/** Validation rules of the search and report parameters (the journey-service's own formats). */
public final class SearchRules {

  public static final String BAG_TAG_PATTERN = "^[0-9]{10}$";
  public static final String BAG_TAG_RULE = "must be a 10-digit IATA bag tag";

  public static final String AIRPORT_PATTERN = "^[A-Z]{3}$";
  public static final String AIRPORT_RULE = "must be a 3-letter IATA airport code";

  public static final String AIRLINE_PATTERN = "^[A-Z0-9]{2}$";
  public static final String AIRLINE_RULE = "must be a 2-character IATA airline code";

  public static final String FLIGHT_PATTERN = "^[A-Z0-9]{2}[0-9]{1,4}[A-Z]?$";
  public static final String FLIGHT_RULE = "must be a flight number such as TK1981";

  public static final int DEFAULT_PAGE_SIZE = 20;
  public static final int MAX_PAGE_SIZE = 100;
  public static final String SIZE_RULE = "must be between 1 and 100";

  public static final int MAX_CURSOR_LENGTH = 8_192;

  private SearchRules() {}
}
