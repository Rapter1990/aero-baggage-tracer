package com.aerotrace.recovery.repository;

import com.aerotrace.recovery.model.entity.StateTransitionEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StateTransitionRepository extends JpaRepository<StateTransitionEntity, UUID> {

  /** The history of one case, oldest first. */
  List<StateTransitionEntity> findByCaseIdOrderByVersionAfterAsc(UUID caseId);
}
