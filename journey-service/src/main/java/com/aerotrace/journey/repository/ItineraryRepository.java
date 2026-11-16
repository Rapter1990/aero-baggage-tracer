package com.aerotrace.journey.repository;

import com.aerotrace.journey.model.entity.ItineraryEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItineraryRepository extends JpaRepository<ItineraryEntity, UUID> {

  Optional<ItineraryEntity> findByBaggageId(UUID baggageId);
}
