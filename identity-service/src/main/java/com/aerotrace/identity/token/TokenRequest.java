package com.aerotrace.identity.token;

/** Credentials posted to the token endpoint. Never printed, so a log line cannot leak them. */
record TokenRequest(String username, String password) {

  @Override
  public String toString() {
    return "TokenRequest[redacted]";
  }
}
