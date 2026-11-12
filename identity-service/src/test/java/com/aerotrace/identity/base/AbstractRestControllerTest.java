package com.aerotrace.identity.base;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Base for controller tests: the full application with MockMvc, the security filters switched off
 * so the controller and its validation are tested on their own (security is covered by the
 * integration tests), and a real PostgreSQL.
 *
 * <p>Unlike a throw-away schema, the schema is the one Liquibase builds (Hibernate only validates
 * it), so the tests run against the same tables, constraints and triggers as production.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.jpa.show-sql=false",
      "spring.liquibase.enabled=true"
    })
public abstract class AbstractRestControllerTest extends AbstractTestContainerConfiguration {

  @Autowired protected MockMvc mockMvc;

  @Autowired protected ObjectMapper objectMapper;
}
