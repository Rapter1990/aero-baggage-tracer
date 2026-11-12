package com.aerotrace.identity.user;

import com.aerotrace.common.error.ApiException;
import com.aerotrace.common.error.ErrorCode;
import com.aerotrace.common.security.Role;
import com.aerotrace.identity.constant.ApiMessages;
import com.aerotrace.identity.constant.UserRules;
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
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns users and roles: registration rules, password hashing and lookups. */
@Service
@RequiredArgsConstructor
public class UserService {

  private static final Pattern USERNAME = Pattern.compile(UserRules.USERNAME_PATTERN);

  private final AppUserRepository users;
  private final PasswordEncoder encoder;
  private final IdentityMapper mapper;

  /**
   * Registers a user. A PASSENGER must carry a pseudonymous passenger reference; any other role
   * must not. Rule violations are reported with generic messages that never echo the input.
   */
  @Transactional
  public AppUser register(
      String username, String rawPassword, Set<Role> roles, String passengerRef) {
    String name = username == null ? "" : username.toLowerCase(Locale.ROOT);
    if (!USERNAME.matcher(name).matches()) {
      throw invalid(ApiMessages.USERNAME_RULE);
    }
    validatePassword(rawPassword);
    if (roles == null || roles.isEmpty()) {
      throw invalid(ApiMessages.ROLE_REQUIRED);
    }
    boolean passenger = roles.contains(Role.PASSENGER);
    if (passenger && !PassengerRef.isValid(passengerRef)) {
      throw invalid(ApiMessages.PASSENGER_REF_REQUIRED);
    }
    if (!passenger && passengerRef != null) {
      throw invalid(ApiMessages.PASSENGER_REF_FORBIDDEN);
    }

    AppUserEntity entity =
        new AppUserEntity(
            UUID.randomUUID(), name, encoder.encode(rawPassword), passengerRef, roles);
    try {
      users.saveAndFlush(entity);
    } catch (DataIntegrityViolationException e) {
      throw new ApiException(ErrorCode.REQUEST_REJECTED, ApiMessages.USER_NOT_REGISTERED);
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
        || password.length() < UserRules.MIN_PASSWORD_LENGTH
        || password.getBytes(StandardCharsets.UTF_8).length > UserRules.MAX_PASSWORD_BYTES) {
      throw invalid(ApiMessages.PASSWORD_RULE);
    }
  }

  private static ApiException invalid(String detail) {
    return new ApiException(ErrorCode.VALIDATION_ERROR, detail);
  }
}
