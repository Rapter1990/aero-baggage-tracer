package com.aerotrace.gateway.problem;

import com.aerotrace.common.correlation.CorrelationId;
import com.aerotrace.common.error.ErrorCode;
import com.aerotrace.common.error.ProblemDetails;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.result.view.ViewResolver;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Writes gateway-originated errors in the shared RFC 7807 shape. */
@Component
public class GatewayProblems {

  /** Exchange attribute holding the correlation id chosen by the correlation filter. */
  public static final String CORRELATION_ATTRIBUTE = CorrelationId.class.getName() + ".value";

  private final ServerResponse.Context context;

  public GatewayProblems(ServerCodecConfigurer codecs) {
    List<HttpMessageWriter<?>> writers = codecs.getWriters();
    this.context =
        new ServerResponse.Context() {
          @Override
          public List<HttpMessageWriter<?>> messageWriters() {
            return writers;
          }

          @Override
          public List<ViewResolver> viewResolvers() {
            return List.of();
          }
        };
  }

  public Mono<Void> write(ServerWebExchange exchange, ErrorCode code, String detail) {
    ProblemDetail problem = ProblemDetails.of(code, detail);
    String correlationId = exchange.getAttribute(CORRELATION_ATTRIBUTE);
    if (correlationId != null) {
      problem.setProperty("correlationId", correlationId);
    }
    return ServerResponse.status(code.httpStatus())
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .bodyValue(problem)
        .flatMap(response -> response.writeTo(exchange, context));
  }
}
