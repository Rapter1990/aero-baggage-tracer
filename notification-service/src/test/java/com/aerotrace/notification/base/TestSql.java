package com.aerotrace.notification.base;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Native SQL for test fixtures (moving stored times back, forcing a status, reading catalog
 * tables), through JPA's {@link EntityManager} instead of {@code JdbcTemplate}. A
 * {@code @Repository}, so a failing statement surfaces as Spring's {@code DataAccessException}
 * family, and each call is its own transaction. Parameters are written as {@code ?} and bound in
 * order.
 */
@Repository
public class TestSql {

  @PersistenceContext private EntityManager entityManager;

  /** Runs an INSERT, UPDATE or DELETE; returns the number of rows it changed. */
  @Transactional
  public int update(String sql, Object... args) {
    return query(sql, args).executeUpdate();
  }

  /** Runs a SELECT of one column; returns its values. */
  @Transactional(readOnly = true)
  public <T> List<T> queryForList(String sql, Class<T> type, Object... args) {
    return query(sql, args).getResultList().stream().map(type::cast).toList();
  }

  /** The database's current time. */
  @Transactional(readOnly = true)
  public Instant now() {
    Object value = query("select now()").getSingleResult();
    if (value instanceof Instant instant) {
      return instant;
    }
    if (value instanceof OffsetDateTime offset) {
      return offset.toInstant();
    }
    return ((Timestamp) value).toInstant();
  }

  private Query query(String sql, Object... args) {
    StringBuilder numbered = new StringBuilder();
    int index = 0;
    for (char c : sql.toCharArray()) {
      numbered.append(c);
      if (c == '?') {
        numbered.append(++index);
      }
    }
    Query query = entityManager.createNativeQuery(numbered.toString());
    for (int i = 0; i < args.length; i++) {
      query.setParameter(i + 1, args[i]);
    }
    return query;
  }
}
