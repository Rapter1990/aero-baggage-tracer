package com.aerotrace.search.document;

import java.time.Instant;
import java.util.Map;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
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
 * ({@code createIndex = false}); no {@code _class} type hint is written and the id (the document's
 * {@code _id}) is read only, because the mapping is strict. Dates are epoch milliseconds, as
 * everywhere in the read models.
 *
 * <p>Audited (ADR 0003): {@code createdAt}/{@code createdBy} are set when the job is first saved
 * (the admin's token subject, on the request thread) and {@code lastModifiedAt}/{@code
 * lastModifiedBy} on every save (the token's subject, or {@code system} for the worker thread that
 * finishes the job).
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
    @Field(type = FieldType.Object) Map<String, Object> results,
    @CreatedDate @Field(type = FieldType.Date, format = DateFormat.epoch_millis) Instant createdAt,
    @LastModifiedDate @Field(type = FieldType.Date, format = DateFormat.epoch_millis)
        Instant lastModifiedAt,
    @CreatedBy @Field(type = FieldType.Keyword) String createdBy,
    @LastModifiedBy @Field(type = FieldType.Keyword) String lastModifiedBy)
    implements Persistable<String> {

  public static final String KIND = "JOB";
  public static final String ID_PREFIX = "job:";

  @Override
  public String getId() {
    return id;
  }

  /**
   * New until it has been saved once. The id is assigned, which Spring Data would take for "not
   * new", so the creation fields would never be set; without a {@code createdAt} the document is
   * new.
   */
  @Override
  @Transient
  public boolean isNew() {
    return createdAt == null;
  }
}
