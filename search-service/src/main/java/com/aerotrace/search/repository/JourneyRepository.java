package com.aerotrace.search.repository;

import com.aerotrace.search.document.JourneyDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

/** Journeys of the journeys-read alias. */
public interface JourneyRepository extends ElasticsearchRepository<JourneyDocument, String> {}
