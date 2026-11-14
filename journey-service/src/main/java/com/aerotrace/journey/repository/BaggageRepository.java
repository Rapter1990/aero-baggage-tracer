package com.aerotrace.journey.repository;

import com.aerotrace.journey.model.entity.BaggageEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BaggageRepository extends JpaRepository<BaggageEntity, UUID> {

  Optional<BaggageEntity> findByBagTag(String bagTag);

  boolean existsByBagTag(String bagTag);

  /** Locks the row, so scans of one bag are applied one at a time (in any replica). */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select b from BaggageEntity b where b.bagTag = :bagTag")
  Optional<BaggageEntity> findByBagTagForUpdate(@Param("bagTag") String bagTag);
}
