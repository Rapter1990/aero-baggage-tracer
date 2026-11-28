package com.aerotrace.journey.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.journey.base.AbstractTestContainerConfiguration;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * An empty contexts setting (an empty environment variable) must not make the application run the
 * {@code dev} sample data: Liquibase would otherwise run every changeset, whatever its context.
 */
@SpringBootTest(properties = "spring.liquibase.contexts=")
class EmptyLiquibaseContextsTest extends AbstractTestContainerConfiguration {

  @Autowired private EntityManager entityManager;
  @Autowired private PlatformTransactionManager transactionManager;

  @Test
  @DisplayName("AT-17 an empty JOURNEY_LIQUIBASE_CONTEXTS does not run the sample data")
  void emptyContextsDoNotRunTheDevSampleData() {
    Number sampleChangeset =
        new TransactionTemplate(transactionManager)
            .execute(
                status ->
                    (Number)
                        entityManager
                            .createNativeQuery(
                                "SELECT count(*) FROM journey.databasechangelog "
                                    + "WHERE id = '009-dev-sample-journeys'")
                            .getSingleResult());

    assertThat(sampleChangeset.longValue()).isZero();
  }
}
