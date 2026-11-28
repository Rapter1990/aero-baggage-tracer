package com.aerotrace.journey.config;

import liquibase.integration.spring.SpringLiquibase;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.util.StringUtils;

/**
 * Liquibase reads empty contexts as "no filter: run every changeset", which would also run the
 * {@code dev} sample data. A setting that is present but empty (an empty environment variable, a
 * template that always writes the key) must not do that, so blank contexts become the default
 * before Liquibase runs (ADR 0007).
 */
public class LiquibaseContextsGuard implements BeanPostProcessor {

  public static final String DEFAULT_CONTEXTS = "expand,contract";

  @Override
  public Object postProcessBeforeInitialization(Object bean, String beanName) {
    if (bean instanceof SpringLiquibase liquibase
        && !StringUtils.hasText(liquibase.getContexts())) {
      liquibase.setContexts(DEFAULT_CONTEXTS);
    }
    return bean;
  }
}
