package com.aerotrace.journey.repository;

import com.aerotrace.journey.model.entity.BaggageEntity;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BaggageRepository extends JpaRepository<BaggageEntity, UUID> {

  Optional<BaggageEntity> findByBagTag(String bagTag);

  boolean existsByBagTag(String bagTag);

  /** The first bags in bag tag order: the first page of the internal export. */
  List<BaggageEntity> findAllByOrderByBagTagAsc(Limit limit);

  /** The bags after the given tag, in tag order: a following page of the internal export. */
  List<BaggageEntity> findByBagTagGreaterThanOrderByBagTagAsc(String after, Limit limit);

  /** Locks the row, so scans of one bag are applied one at a time (in any replica). */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select b from BaggageEntity b where b.bagTag = :bagTag")
  Optional<BaggageEntity> findByBagTagForUpdate(@Param("bagTag") String bagTag);
}
