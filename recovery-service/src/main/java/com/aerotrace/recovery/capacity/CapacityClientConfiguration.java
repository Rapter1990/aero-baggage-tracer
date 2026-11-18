package com.aerotrace.recovery.capacity;

import com.aerotrace.common.correlation.CorrelationId;
import com.aerotrace.recovery.config.RecoveryProperties;
import java.net.http.HttpClient;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/**
 * Builds the capacity client with hard timeouts, so a slow capacity-service can never hold a
 * request thread for long, and forwards the correlation id and the caller's token.
 */
@Configuration(proxyBeanMethods = false)
class CapacityClientConfiguration {

  @Bean
  CapacityClient capacityClient(RecoveryProperties properties) {
    RecoveryProperties.Capacity settings = properties.capacity();
    HttpClient http =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(settings.connectTimeout())
            .build();
    JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(http);
    requests.setReadTimeout(settings.readTimeout());
    RestClient client =
        RestClient.builder()
            .baseUrl(settings.baseUrl())
            .requestFactory(requests)
            .requestInterceptor(propagateCallContext())
            .build();
    return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(client))
        .build()
        .createClient(CapacityClient.class);
  }

  /**
   * Carries the correlation id (a new one when the call has none, for example from the sweeper)
   * and, when a user is calling, the user's token. Neither is ever logged. Calls made by the
   * sweeper have no token: see {@code CapacityGateway}.
   */
  private static ClientHttpRequestInterceptor propagateCallContext() {
    return (request, body, execution) -> {
      String correlationId = MDC.get(CorrelationId.MDC_KEY);
      request
          .getHeaders()
          .set(
              CorrelationId.HEADER,
              CorrelationId.isValid(correlationId) ? correlationId : CorrelationId.generate());
      Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
      if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
        request.getHeaders().setBearerAuth(jwt.getTokenValue());
      } else {
        request.getHeaders().remove(HttpHeaders.AUTHORIZATION);
      }
      return execution.execute(request, body);
    };
  }
}
