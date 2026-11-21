package com.aerotrace.search.document;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.DateFormat;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.WriteTypeHint;

/**
 * One bag's journey in the {@code journeys-read} alias: the latest scan and the legs with their
 * risk and case state (nested, so a filter on a leg matches one leg's own values). Written by the
 * indexer's scripted upserts, read by the bag search.
 */
@Document(indexName = "journeys-read", createIndex = false, writeTypeHint = WriteTypeHint.FALSE)
public record JourneyDocument(
    @Id String id,
    @Field(type = FieldType.Keyword) String docId,
    @Field(type = FieldType.Keyword) String bagTag,
    @Field(type = FieldType.Date, format = DateFormat.epoch_millis) Instant updatedAt,
    @Field(type = FieldType.Object) LatestScan latestScan,
    @Field(type = FieldType.Nested) List<Leg> legs) {

  /** The newest accepted scan of the bag. */
  public record LatestScan(
      @Field(type = FieldType.Keyword) String airport,
      @Field(type = FieldType.Keyword) String checkpoint,
      @Field(type = FieldType.Keyword) String legId,
      @Field(type = FieldType.Date, format = DateFormat.epoch_millis) Instant occurredAt,
      @Field(type = FieldType.Long) Long deviceSequence) {}

  /** One leg of the journey with its latest risk level and case. */
  public record Leg(
      @Field(type = FieldType.Keyword) String legId,
      @Field(type = FieldType.Keyword) String flightNumber,
      @Field(type = FieldType.Keyword) String airline,
      @Field(type = FieldType.Keyword) String origin,
      @Field(type = FieldType.Keyword) String destination,
      @Field(type = FieldType.Date, format = DateFormat.epoch_millis) Instant departureAt,
      @Field(type = FieldType.Keyword) String riskLevel,
      @Field(type = FieldType.Date, format = DateFormat.epoch_millis) Instant riskAt,
      @Field(type = FieldType.Keyword) String caseId,
      @Field(type = FieldType.Keyword) String caseState,
      @Field(type = FieldType.Long) Long caseVersion,
      @Field(type = FieldType.Date, format = DateFormat.epoch_millis) Instant caseAt) {}
}
