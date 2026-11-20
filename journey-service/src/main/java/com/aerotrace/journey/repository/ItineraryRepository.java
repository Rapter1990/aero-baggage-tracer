package com.aerotrace.journey.repository;

import com.aerotrace.journey.model.entity.ItineraryEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ItineraryRepository extends JpaRepository<ItineraryEntity, UUID> {

  Optional<ItineraryEntity> findByBaggageId(UUID baggageId);

  /** The itineraries of several bags with their legs, in one query (a page of the export). */
  @Query(
      "select distinct i from ItineraryEntity i left join fetch i.legs"
          + " where i.baggageId in :baggageIds")
  List<ItineraryEntity> findWithLegsByBaggageIdIn(@Param("baggageIds") Collection<UUID> baggageIds);
}
