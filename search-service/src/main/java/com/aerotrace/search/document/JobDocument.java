package com.aerotrace.search.document;

import java.time.Instant;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.data.elasticsearch.annotations.DateFormat;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.WriteTypeHint;

/**
 * A rebuild job in {@code search-jobs} ({@code _id = "job:" + jobId}, {@code kind = JOB}). The
 * rebuild lock and the idempotency keys live in the same index but need conditional writes a
 * repository cannot express, so {@code ReindexJobStore} writes them with the client.
 *
 * <p>The index is created from its template at start-up ({@code IndexAdmin}), never by Spring Data
 * ({@code createIndex = false}); no {@code _class} type hint is written, because the mapping is
 * strict (so the id, which is the document's {@code _id}, is read only and not repeated in the
 * source). Dates are epoch milliseconds, as everywhere in the read models.
 */
@Document(indexName = "search-jobs", createIndex = false, writeTypeHint = WriteTypeHint.FALSE)
public record JobDocument(
    @Id @ReadOnlyProperty String id,
    @Field(type = FieldType.Keyword) String kind,
    @Field(type = FieldType.Keyword) String jobId,
    @Field(type = FieldType.Keyword) String status,
    @Field(type = FieldType.Keyword) String scope,
    @Field(type = FieldType.Keyword) String requestedBy,
    @Field(type = FieldType.Date, format = DateFormat.epoch_millis) Instant startedAt,
    @Field(type = FieldType.Date, format = DateFormat.epoch_millis) Instant finishedAt,
    @Field(type = FieldType.Keyword) String error,
    @Field(type = FieldType.Object) Map<String, Object> results) {

  public static final String KIND = "JOB";
  public static final String ID_PREFIX = "job:";
}
