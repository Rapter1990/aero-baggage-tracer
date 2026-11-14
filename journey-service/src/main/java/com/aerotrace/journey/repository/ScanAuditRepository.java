package com.aerotrace.journey.repository;

import com.aerotrace.journey.model.entity.ScanAuditEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScanAuditRepository extends JpaRepository<ScanAuditEntity, UUID> {

  Optional<ScanAuditEntity> findByEventId(String eventId);

  /** The newest scans of a bag first (ties broken by arrival order), at most one page. */
  List<ScanAuditEntity> findByBagTagOrderByOccurredAtDescIngestedAtDesc(
      String bagTag, Pageable page);
}
