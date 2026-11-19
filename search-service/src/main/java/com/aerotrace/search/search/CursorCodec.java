package com.aerotrace.search.search;

import com.aerotrace.common.error.ApiException;
import com.aerotrace.common.error.ErrorCode;
import com.aerotrace.search.config.SearchProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The opaque paging cursor: the point-in-time id, the sort values of the last hit, a fingerprint of
 * the filters and the time the point-in-time expires, signed with HMAC-SHA256. A cursor that was
 * changed, or used with other filters, is {@code 400}; one past its expiry is {@code 410
 * CURSOR_EXPIRED}. Nothing in it is a secret, but nothing in it can be forged either.
 */
@Component
public class CursorCodec {

  private static final Logger LOG = LoggerFactory.getLogger(CursorCodec.class);
  private static final String ALGORITHM = "HmacSHA256";
  private static final String INVALID = "The cursor is not valid for this search.";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

  /** What a cursor carries. {@code after} holds numbers and texts only. */
  public record Cursor(String pit, List<Object> after, String filters, long expiresAt) {}

  private final byte[] key;
  private final Clock clock;

  public CursorCodec(SearchProperties properties, Clock clock) {
    this.clock = clock;
    String secret = properties.cursor().secret();
    if (secret == null || secret.isBlank()) {
      LOG.warn(
          "No SEARCH_CURSOR_SECRET set: cursors are signed with a random key and only work on"
              + " this instance");
      this.key = new byte[32];
      new SecureRandom().nextBytes(this.key);
    } else {
      this.key = secret.getBytes(StandardCharsets.UTF_8);
    }
  }

  public String encode(Cursor cursor) {
    byte[] body = JSON.writeValueAsBytes(cursor);
    return ENCODER.encodeToString(body) + "." + ENCODER.encodeToString(sign(body));
  }

  /** Reads and checks a cursor for a search with these filters. */
  public Cursor decode(String token, String filters) {
    int dot = token.indexOf('.');
    if (dot <= 0 || dot != token.lastIndexOf('.')) {
      throw invalid();
    }
    byte[] body;
    byte[] signature;
    try {
      body = DECODER.decode(token.substring(0, dot));
      signature = DECODER.decode(token.substring(dot + 1));
    } catch (IllegalArgumentException notBase64) {
      throw invalid();
    }
    if (!MessageDigest.isEqual(sign(body), signature)) {
      throw invalid();
    }
    Cursor cursor;
    try {
      cursor = JSON.readValue(body, Cursor.class);
    } catch (RuntimeException unreadable) {
      throw invalid();
    }
    if (cursor.pit() == null || cursor.after() == null || !filters.equals(cursor.filters())) {
      throw invalid();
    }
    if (Instant.ofEpochMilli(cursor.expiresAt()).isBefore(clock.instant())) {
      throw expired();
    }
    return cursor;
  }

  static ApiException invalid() {
    return new ApiException(ErrorCode.VALIDATION_ERROR, INVALID);
  }

  static ApiException expired() {
    return new ApiException(
        ErrorCode.CURSOR_EXPIRED, "The cursor has expired. Start the search again.");
  }

  private byte[] sign(byte[] body) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(key, ALGORITHM));
      return mac.doFinal(body);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HMAC-SHA256 is not available", e);
    }
  }
}
