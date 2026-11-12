package com.aerotrace.identity.auth;

import com.aerotrace.identity.constant.AuthConstants;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The optional first administrator. Public registration only creates passengers, so without this
 * nobody could create staff accounts. Nothing is created unless a username is set, and never when
 * an administrator already exists.
 *
 * @param username the administrator's username, or empty to create nothing
 * @param password the administrator's password
 * @param email the administrator's email address
 */
@ConfigurationProperties(AuthConstants.BOOTSTRAP_PROPERTY_PREFIX)
public record BootstrapProperties(String username, String password, String email) {

  /** Never prints the password. */
  @Override
  public String toString() {
    return "BootstrapProperties[configured=" + (username != null && !username.isBlank()) + "]";
  }
}
