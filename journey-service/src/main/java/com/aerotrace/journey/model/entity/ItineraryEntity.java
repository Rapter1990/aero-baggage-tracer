package com.aerotrace.journey.model.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

/** The itinerary as it was when the bag was registered; never changed afterwards. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "itinerary", schema = "journey")
public class ItineraryEntity {

  @Id @GeneratedValue @UuidGenerator private UUID id;

  @Column(name = "baggage_id", nullable = false, updatable = false)
  private UUID baggageId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
  @JoinColumn(name = "itinerary_id", nullable = false, updatable = false)
  @OrderBy("legNo")
  private List<ItineraryLegEntity> legs = new ArrayList<>();

  public ItineraryEntity(UUID baggageId, Instant createdAt, List<ItineraryLegEntity> legs) {
    this.baggageId = baggageId;
    this.createdAt = createdAt;
    this.legs.addAll(legs);
  }
}
