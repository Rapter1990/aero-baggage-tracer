package com.aerotrace.recovery.repository;

import com.aerotrace.recovery.model.entity.StateTransitionEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StateTransitionRepository extends JpaRepository<StateTransitionEntity, UUID> {

  /** The move of a case that was made with this Idempotency-Key, if any. */
  Optional<StateTransitionEntity> findByCaseIdAndIdempotencyKey(UUID caseId, String idempotencyKey);

  /** The history of one case, oldest first. */
  List<StateTransitionEntity> findByCaseIdOrderByVersionAfterAsc(UUID caseId);
}
