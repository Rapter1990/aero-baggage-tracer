package com.aerotrace.search.repository;

import com.aerotrace.search.document.JobDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

/**
 * Rebuild job documents (the lock and idempotency keys of the same index are written by
 * ReindexJobStore with conditional writes).
 */
public interface ReindexJobRepository extends ElasticsearchRepository<JobDocument, String> {}
