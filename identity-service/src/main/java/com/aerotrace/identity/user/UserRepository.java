package com.aerotrace.identity.user;

import com.aerotrace.common.security.Role;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class UserRepository {

  private final JdbcClient jdbc;

  UserRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  void insert(UserAccount user) {
    jdbc.sql(
            "INSERT INTO identity.app_user (id, username, password_hash, passenger_ref, enabled) "
                + "VALUES (:id, :username, :hash, :ref, :enabled)")
        .param("id", user.id())
        .param("username", user.username())
        .param("hash", user.passwordHash())
        .param("ref", user.passengerRef())
        .param("enabled", user.enabled())
        .update();
    for (Role role : user.roles()) {
      jdbc.sql("INSERT INTO identity.user_role (user_id, role) VALUES (:id, :role)")
          .param("id", user.id())
          .param("role", role.name())
          .update();
    }
  }

  Optional<UserAccount> findByUsername(String username) {
    return jdbc.sql(
            "SELECT id, username, password_hash, passenger_ref, enabled "
                + "FROM identity.app_user WHERE username = :username")
        .param("username", username)
        .query(
            (rs, row) ->
                new UserAccount(
                    rs.getObject("id", UUID.class),
                    rs.getString("username"),
                    rs.getString("password_hash"),
                    rs.getString("passenger_ref"),
                    rs.getBoolean("enabled"),
                    Set.of()))
        .optional()
        .map(user -> withRoles(user));
  }

  private UserAccount withRoles(UserAccount user) {
    Set<Role> roles = EnumSet.noneOf(Role.class);
    jdbc.sql("SELECT role FROM identity.user_role WHERE user_id = :id")
        .param("id", user.id())
        .query(String.class)
        .list()
        .forEach(name -> roles.add(Role.valueOf(name)));
    return new UserAccount(
        user.id(),
        user.username(),
        user.passwordHash(),
        user.passengerRef(),
        user.enabled(),
        roles);
  }
}
