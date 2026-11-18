package com.aerotrace.recovery.policy;

import com.aerotrace.common.error.ApiException;
import com.aerotrace.common.error.ErrorCode;
import com.aerotrace.common.security.SecurityContextHelper;
import com.aerotrace.recovery.constant.RecoveryRules;
import com.aerotrace.recovery.model.entity.RecoveryPolicyEntity;
import com.aerotrace.recovery.repository.RecoveryPolicyRepository;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The recovery policy: read through a Redis cache, changed by an admin in PostgreSQL.
 *
 * <p>The database is the authority. A read tries Redis, and on a miss, an unreadable entry or an
 * unreachable Redis it reads the row and tries to cache it. A change is committed first and the
 * cached copy is evicted after the commit; if Redis cannot be reached to evict it, the old copy
 * lives until its TTL. A reader that loaded the old row just before the commit can cache it just
 * after the eviction, with the same bound: the TTL. Redis is never consulted for anything that must
 * be exact (one active case per bag and leg, capacity): those are decided by the database alone.
 */
@Service
public class PolicyService {

  private static final Logger LOG = LoggerFactory.getLogger(PolicyService.class);
  static final String DEFAULT_POLICY = "DEFAULT";

  private final RecoveryPolicyRepository policies;
  private final PolicyCache cache;
  private final Clock clock;
  private final TransactionTemplate transaction;

  public PolicyService(
      RecoveryPolicyRepository policies,
      PolicyCache cache,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.policies = policies;
    this.cache = cache;
    this.clock = clock;
    this.transaction = new TransactionTemplate(transactionManager);
  }

  /** The current policy, from Redis when it has it, else from the database. */
  public PolicyView current() {
    return cache
        .get(DEFAULT_POLICY)
        .orElseGet(
            () -> {
              PolicyView fromDatabase = load();
              cache.put(DEFAULT_POLICY, fromDatabase);
              return fromDatabase;
            });
  }

  /**
   * Changes the policy against the version the admin saw (409 VERSION_CONFLICT when it moved), then
   * evicts the cached copy so the next read sees the change.
   */
  public PolicyView change(int minimumExpediteMinutes, long expectedVersion) {
    String actor = actor();
    Integer moved =
        transaction.execute(
            status ->
                policies.change(
                    DEFAULT_POLICY,
                    expectedVersion,
                    minimumExpediteMinutes,
                    actor,
                    Instant.now(clock)));
    if (moved == null || moved == 0) {
      throw new ApiException(ErrorCode.VERSION_CONFLICT, RecoveryRules.POLICY_VERSION_MISMATCH);
    }
    cache.evict(DEFAULT_POLICY);
    PolicyView changed = load();
    // The audit trail: who changed which setting to what. No personal data, only the actor id.
    LOG.info(
        "Recovery policy changed by {} to {} minutes (version {})",
        actor,
        changed.minimumExpediteMinutes(),
        changed.version());
    return changed;
  }

  private PolicyView load() {
    RecoveryPolicyEntity row =
        policies
            .findById(DEFAULT_POLICY)
            .orElseThrow(
                () -> new IllegalStateException("The DEFAULT recovery policy row is missing"));
    return new PolicyView(row.getMinimumExpediteMinutes(), row.getVersion());
  }

  private static String actor() {
    String subject = SecurityContextHelper.currentSubject().orElse(RecoveryRules.SYSTEM_ACTOR);
    return subject.length() > RecoveryRules.MAX_ACTOR_LENGTH
        ? subject.substring(0, RecoveryRules.MAX_ACTOR_LENGTH)
        : subject;
  }
}
