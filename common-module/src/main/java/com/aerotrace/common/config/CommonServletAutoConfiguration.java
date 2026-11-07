package com.aerotrace.common.config;

import com.aerotrace.common.correlation.CorrelationIdFilter;
import com.aerotrace.common.correlation.CorrelationIdInterceptor;
import com.aerotrace.common.error.BaseExceptionHandler;
import jakarta.servlet.Filter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.DispatcherServlet;

/**
 * Servlet-only wiring. Active only in a servlet web application with Spring MVC on the classpath,
 * so the reactive api-gateway, which only uses the contract types, is unaffected. Everything backs
 * off when the service defines its own bean.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({DispatcherServlet.class, Filter.class})
public class CommonServletAutoConfiguration {

  /** Boot registers Filter beans automatically; the filter orders itself first. */
  @Bean
  @ConditionalOnMissingBean
  public CorrelationIdFilter correlationIdFilter() {
    return new CorrelationIdFilter();
  }

  /**
   * Add to a RestClient with {@code builder.requestInterceptor(interceptor)}; internal calls only.
   */
  @Bean
  @ConditionalOnMissingBean
  public CorrelationIdInterceptor correlationIdInterceptor() {
    return new CorrelationIdInterceptor();
  }

  /**
   * Default advice; a service's own subclass of {@link BaseExceptionHandler} replaces it. The
   * conditions sit on the class itself because Spring registers a nested stereotype-annotated class
   * on its own, so a condition on a factory method would never apply.
   */
  @RestControllerAdvice
  @ConditionalOnMissingBean(BaseExceptionHandler.class)
  @ConditionalOnClass(name = "org.springframework.security.access.AccessDeniedException")
  public static class DefaultExceptionAdvice extends BaseExceptionHandler {}
}
