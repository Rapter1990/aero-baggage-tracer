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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creating and finding users, and checking passwords. Usernames and emails are stored in lower
 * case, so lookups do not depend on how someone typed them.
 */
@Service
public class UserService {

  private final AppUserRepository users;
  private final PasswordEncoder encoder;
  private final IdentityMapper mapper;

  public UserService(AppUserRepository users, PasswordEncoder encoder, IdentityMapper mapper) {
    this.users = users;
    this.encoder = encoder;
    this.mapper = mapper;
    // Built once at start-up, so the first unknown-user sign-in costs the same as every other.
    this.timingEqualizerHash = encoder.encode(UUID.randomUUID().toString());
  }

  /** Checked against when no account matches, so that case costs a real hash comparison. */
  private final String timingEqualizerHash;

  /**
   * Creates an account. Passengers get a generated passenger reference, staff get none.
   *
   * @throws ApiException {@code USER_ALREADY_EXISTS} when the username or email is taken, or {@code
   *     VALIDATION_ERROR} when the password is longer than BCrypt can use or no role is given
   */
  @Transactional
  public AppUser create(
      String username, String email, String fullName, String rawPassword, Set<Role> roles) {
    if (roles == null || roles.isEmpty()) {
      throw new ApiException(ErrorCode.VALIDATION_ERROR, ApiMessages.ROLE_REQUIRED);
    }
    if (!fitsBcrypt(rawPassword)) {
      throw new ApiException(ErrorCode.VALIDATION_ERROR, ApiMessages.PASSWORD_RULE);
    }
    String normalizedUsername = normalize(username);
    String normalizedEmail = normalize(email);
    if (users.findByUsername(normalizedUsername).isPresent()
        || users.findByEmail(normalizedEmail).isPresent()) {
      throw alreadyExists();
    }
    String passengerRef = roles.contains(Role.PASSENGER) ? PassengerRef.generate() : null;
    AppUserEntity entity =
        new AppUserEntity(
            UUID.randomUUID(),
            normalizedUsername,
            normalizedEmail,
            fullName.trim(),
            encoder.encode(rawPassword),
            passengerRef,
            roles);
    try {
      return mapper.toDomain(users.saveAndFlush(entity));
    } catch (DataIntegrityViolationException e) {
      // Two registrations racing for the same name: the unique index decides.
      throw alreadyExists();
    }
  }

  /** The account with this username, or this email address when the text contains an "@". */
  @Transactional(readOnly = true)
  public Optional<AppUser> findByUsernameOrEmail(String usernameOrEmail) {
    String key = normalize(usernameOrEmail);
    Optional<AppUserEntity> found =
        key.contains("@") ? users.findByEmail(key) : users.findByUsername(key);
    return found.map(mapper::toDomain);
  }

  /**
   * Checks a password for a sign-in. An unknown account, a disabled one and a password BCrypt would
   * silently cut short (so "right prefix plus junk" would pass) all fail, after the same amount of
   * work as a real check.
   */
  public boolean authenticates(Optional<AppUser> account, String rawPassword) {
    if (account.isEmpty() || !fitsBcrypt(rawPassword)) {
      encoder.matches(withinBcryptLimit(rawPassword), timingEqualizerHash);
      return false;
    }
    boolean matches = encoder.matches(rawPassword, account.get().passwordHash());
    return matches && account.get().enabled();
  }

  /** Hashes a password with the configured cost. */
  public String encode(String rawPassword) {
    return encoder.encode(rawPassword);
  }

  /** Stores an already hashed password for a user. */
  @Transactional
  public void changePasswordHash(UUID userId, String passwordHash) {
    AppUserEntity user =
        users
            .findById(userId)
            .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "User not found"));
    user.changePassword(passwordHash);
  }

  /** Throws the validation error for a password BCrypt would silently cut short. */
  public void requireFitsBcrypt(String rawPassword) {
    if (!fitsBcrypt(rawPassword)) {
      throw new ApiException(ErrorCode.VALIDATION_ERROR, ApiMessages.PASSWORD_RULE);
    }
  }

  private static ApiException alreadyExists() {
    return new ApiException(ErrorCode.USER_ALREADY_EXISTS, ApiMessages.USER_ALREADY_EXISTS);
  }

  private static String normalize(String value) {
    return value.trim().toLowerCase(Locale.ROOT);
  }

  private static boolean fitsBcrypt(String password) {
    return password.getBytes(StandardCharsets.UTF_8).length <= UserRules.MAX_PASSWORD_BYTES;
  }

  /** The longest start of the password that BCrypt accepts, cut on a character boundary. */
  private static String withinBcryptLimit(String password) {
    if (fitsBcrypt(password)) {
      return password;
    }
    StringBuilder kept = new StringBuilder();
    int bytes = 0;
    for (int i = 0; i < password.length(); ) {
      int codePoint = password.codePointAt(i);
      String character = new String(Character.toChars(codePoint));
      bytes += character.getBytes(StandardCharsets.UTF_8).length;
      if (bytes > UserRules.MAX_PASSWORD_BYTES) {
        break;
      }
      kept.append(character);
      i += Character.charCount(codePoint);
    }
    return kept.toString();
  }
}
