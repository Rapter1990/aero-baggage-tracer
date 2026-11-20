package com.aerotrace.search.document;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.DateFormat;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.WriteTypeHint;

/**
 * One recovery case in the {@code cases-read} alias (the report's source). Read only: cases are
 * written to the {@code cases-live} indexes by the indexer with the case version as the external
 * version, which a repository's {@code save} cannot do. The {@code reason} of a case is never
 * indexed.
 */
@Document(indexName = "cases-read", createIndex = false, writeTypeHint = WriteTypeHint.FALSE)
public record CaseDocument(
    @Id String id,
    @Field(type = FieldType.Keyword) String docId,
    @Field(type = FieldType.Keyword) String caseId,
    @Field(type = FieldType.Keyword) String bagTag,
    @Field(type = FieldType.Keyword) String legId,
    @Field(type = FieldType.Keyword) String state,
    @Field(type = FieldType.Keyword) String trigger,
    @Field(type = FieldType.Long) Long version,
    @Field(type = FieldType.Keyword) String airport,
    @Field(type = FieldType.Keyword) String airline,
    @Field(type = FieldType.Keyword) String flightNumber,
    @Field(type = FieldType.Date, format = DateFormat.epoch_millis) Instant openedAt,
    @Field(type = FieldType.Date, format = DateFormat.epoch_millis) Instant resolvedAt,
    @Field(type = FieldType.Date, format = DateFormat.epoch_millis) Instant updatedAt,
    @Field(type = FieldType.Long) Long resolutionSeconds) {}
