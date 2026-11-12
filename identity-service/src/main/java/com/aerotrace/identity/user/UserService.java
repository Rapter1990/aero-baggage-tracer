package com.aerotrace.identity.user;

import com.aerotrace.common.error.ApiException;
import com.aerotrace.common.error.ErrorCode;
import com.aerotrace.common.security.Role;
import com.aerotrace.identity.model.domain.AppUser;
import com.aerotrace.identity.model.entity.AppUserEntity;
import com.aerotrace.identity.model.mapper.IdentityMapper;
import com.aerotrace.identity.repository.AppUserRepository;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns users and roles: registration rules, password hashing and lookups. */
@Service
public class UserService {

  static final int MIN_PASSWORD_LENGTH = 12;

  /** BCrypt silently ignores everything past 72 bytes, so longer passwords are refused. */
  public static final int MAX_PASSWORD_BYTES = 72;

  private static final Pattern USERNAME = Pattern.compile("[a-z0-9._-]{3,64}");

  private final AppUserRepository users;
  private final PasswordEncoder encoder;
  private final IdentityMapper mapper;

  UserService(AppUserRepository users, PasswordEncoder encoder, IdentityMapper mapper) {
    this.users = users;
    this.encoder = encoder;
    this.mapper = mapper;
  }

  /**
   * Registers a user. A PASSENGER must carry a pseudonymous passenger reference; any other role
   * must not. Rule violations are reported with generic messages that never echo the input.
   */
  @Transactional
  public AppUser register(
      String username, String rawPassword, Set<Role> roles, String passengerRef) {
    String name = username == null ? "" : username.toLowerCase(Locale.ROOT);
    if (!USERNAME.matcher(name).matches()) {
      throw invalid("Username must be 3-64 characters of letters, digits, '.', '_' or '-'");
    }
    validatePassword(rawPassword);
    if (roles == null || roles.isEmpty()) {
      throw invalid("A user needs at least one role");
    }
    boolean passenger = roles.contains(Role.PASSENGER);
    if (passenger && !PassengerRef.isValid(passengerRef)) {
      throw invalid("A passenger needs a valid passenger reference");
    }
    if (!passenger && passengerRef != null) {
      throw invalid("Only a passenger can carry a passenger reference");
    }

    AppUserEntity entity =
        new AppUserEntity(
            UUID.randomUUID(), name, encoder.encode(rawPassword), passengerRef, roles);
    try {
      users.saveAndFlush(entity);
    } catch (DataIntegrityViolationException e) {
      throw new ApiException(ErrorCode.REQUEST_REJECTED, "The user could not be registered");
    }
    return mapper.toDomain(entity);
  }

  @Transactional(readOnly = true)
  public Optional<AppUser> findByUsername(String username) {
    if (username == null) {
      return Optional.empty();
    }
    return users.findByUsername(username.toLowerCase(Locale.ROOT)).map(mapper::toDomain);
  }

  public boolean passwordMatches(AppUser account, String rawPassword) {
    return rawPassword != null && encoder.matches(rawPassword, account.passwordHash());
  }

  private static void validatePassword(String password) {
    if (password == null
        || password.length() < MIN_PASSWORD_LENGTH
        || password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
      throw invalid(
          "Password must be at least " + MIN_PASSWORD_LENGTH + " characters and at most 72 bytes");
    }
  }

  private static ApiException invalid(String detail) {
    return new ApiException(ErrorCode.VALIDATION_ERROR, detail);
  }
}
