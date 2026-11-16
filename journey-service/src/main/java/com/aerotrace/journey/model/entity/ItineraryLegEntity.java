package com.aerotrace.journey.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;

/** One flight of an itinerary snapshot. Times are UTC instants. */
@Entity
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "itinerary_leg", schema = "journey")
public class ItineraryLegEntity {

  @Id @GeneratedValue @UuidGenerator private UUID id;

  @Column(name = "leg_no", nullable = false, updatable = false)
  private int legNo;

  @Column(name = "flight_number", nullable = false, updatable = false, length = 8)
  private String flightNumber;

  @Column(nullable = false, updatable = false, length = 3)
  private String origin;

  @Column(nullable = false, updatable = false, length = 3)
  private String destination;

  @Column(name = "departure_at", nullable = false, updatable = false)
  private Instant departureAt;

  @Column(name = "arrival_at", nullable = false, updatable = false)
  private Instant arrivalAt;

  public ItineraryLegEntity(
      int legNo,
      String flightNumber,
      String origin,
      String destination,
      Instant departureAt,
      Instant arrivalAt) {
    this.legNo = legNo;
    this.flightNumber = flightNumber;
    this.origin = origin;
    this.destination = destination;
    this.departureAt = departureAt;
    this.arrivalAt = arrivalAt;
  }
}
