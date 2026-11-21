package com.aerotrace.search.repository;

import com.aerotrace.search.document.CaseDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

/** Cases of the cases-read alias. */
public interface CaseRepository extends ElasticsearchRepository<CaseDocument, String> {}
