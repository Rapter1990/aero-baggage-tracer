package com.aerotrace.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.common.correlation.CorrelationIdFilter;
import com.aerotrace.common.correlation.CorrelationIdInterceptor;
import com.aerotrace.common.error.ApiErrorWriter;
import com.aerotrace.common.error.BaseExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.DispatcherServlet;

class CommonServletAutoConfigurationTest {

  private static final AutoConfigurations CONFIG =
      AutoConfigurations.of(CommonServletAutoConfiguration.class);

  @Test
  void registersFilterInterceptorAndAdviceInServletApp() {
    new WebApplicationContextRunner()
        .withConfiguration(CONFIG)
        .run(
            ctx -> {
              assertThat(ctx).hasSingleBean(CorrelationIdFilter.class);
              assertThat(ctx).hasSingleBean(CorrelationIdInterceptor.class);
              assertThat(ctx).hasSingleBean(BaseExceptionHandler.class);
              assertThat(ctx).hasSingleBean(ApiErrorWriter.class);
              assertThat(ctx.getBean(CorrelationIdFilter.class).getOrder())
                  .isEqualTo(Ordered.HIGHEST_PRECEDENCE);
            });
  }

  @Test
  void doesNothingInReactiveApp() {
    new ReactiveWebApplicationContextRunner()
        .withConfiguration(CONFIG)
        .run(
            ctx -> {
              assertThat(ctx).doesNotHaveBean(CorrelationIdFilter.class);
              assertThat(ctx).doesNotHaveBean(BaseExceptionHandler.class);
            });
  }

  @Test
  void doesNothingWithoutSpringMvcOnClasspath() {
    new WebApplicationContextRunner()
        .withClassLoader(new FilteredClassLoader(DispatcherServlet.class))
        .withConfiguration(CONFIG)
        .run(ctx -> assertThat(ctx).doesNotHaveBean(CorrelationIdFilter.class));
  }

  @Test
  void servicesOwnAdviceReplacesTheDefaultSoThereIsOnlyOne() {
    new WebApplicationContextRunner()
        .withConfiguration(CONFIG)
        .withUserConfiguration(ServiceConfig.class)
        .run(
            ctx -> {
              assertThat(ctx).hasSingleBean(BaseExceptionHandler.class);
              assertThat(ctx.getBean(BaseExceptionHandler.class)).isInstanceOf(ServiceAdvice.class);
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class ServiceConfig {
    @Bean
    ServiceAdvice serviceAdvice() {
      return new ServiceAdvice();
    }
  }

  @RestControllerAdvice
  static class ServiceAdvice extends BaseExceptionHandler {}
}
