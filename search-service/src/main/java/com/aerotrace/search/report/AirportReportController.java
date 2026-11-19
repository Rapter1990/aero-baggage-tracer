package com.aerotrace.search.report;

import com.aerotrace.common.api.ApiResponse;
import com.aerotrace.common.error.ApiException;
import com.aerotrace.common.error.ErrorCode;
import com.aerotrace.search.config.SearchProperties;
import com.aerotrace.search.constant.ApiPaths;
import com.aerotrace.search.constant.SearchRules;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Reports", description = "Hourly airport report for staff (AGENT, DISPATCHER, ADMIN)")
class AirportReportController {

  private final AirportReportService reports;
  private final Duration maxRange;

  AirportReportController(AirportReportService reports, SearchProperties properties) {
    this.reports = reports;
    this.maxRange = properties.report().maxRange();
  }

  @GetMapping(ApiPaths.REPORT_AIRPORT)
  @Operation(
      summary = "Hourly airport report",
      description =
          "Hourly UTC buckets over [from, to): risky bags, resolved recoveries, retries and the "
              + "mean resolution time (null when nothing was resolved). from and to are whole "
              + "UTC hours, from <= to, at most 31 days apart. Hours without data are zeros; an "
              + "airport without data is 200. Eventually consistent: see indexedThrough and "
              + "lagSeconds.")
  ApiResponse<AirportReport> airport(
      @PathVariable
          @Pattern(regexp = SearchRules.AIRPORT_PATTERN, message = SearchRules.AIRPORT_RULE)
          String iata,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
    if (from.isAfter(to)) {
      throw invalid("from must not be after to");
    }
    if (!from.truncatedTo(ChronoUnit.HOURS).equals(from)
        || !to.truncatedTo(ChronoUnit.HOURS).equals(to)) {
      throw invalid("from and to must be whole UTC hours");
    }
    if (Duration.between(from, to).compareTo(maxRange) > 0) {
      throw invalid("the range must not be longer than " + maxRange.toDays() + " days");
    }
    return ApiResponse.success(reports.report(iata, from, to));
  }

  private static ApiException invalid(String message) {
    return new ApiException(ErrorCode.VALIDATION_ERROR, message);
  }
}
