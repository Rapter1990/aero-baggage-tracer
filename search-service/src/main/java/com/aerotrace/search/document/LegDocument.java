package com.aerotrace.search.document;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.DateFormat;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.WriteTypeHint;

/**
 * The leg lookup in {@code search-legs} ({@code _id = legId}): the airport, airline and flight of a
 * leg, so a case event, which carries only the leg id, can be indexed with them. Read with a
 * realtime multi-get ({@code ElasticsearchOperations#multiGet}); written by the indexer's bulk
 * requests (scripted, external version).
 */
@Document(indexName = "search-legs", createIndex = false, writeTypeHint = WriteTypeHint.FALSE)
public record LegDocument(
    @Id String id,
    @Field(type = FieldType.Keyword) String legId,
    @Field(type = FieldType.Keyword) String bagTag,
    @Field(type = FieldType.Keyword) String flightNumber,
    @Field(type = FieldType.Keyword) String airline,
    @Field(type = FieldType.Keyword) String origin,
    @Field(type = FieldType.Keyword) String destination,
    @Field(type = FieldType.Date, format = DateFormat.epoch_millis) Instant departureAt) {}
