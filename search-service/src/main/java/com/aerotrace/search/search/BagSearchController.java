package com.aerotrace.search.search;

import com.aerotrace.common.api.ApiResponse;
import com.aerotrace.common.model.RecoveryState;
import com.aerotrace.common.model.RiskLevel;
import com.aerotrace.search.constant.ApiPaths;
import com.aerotrace.search.constant.SearchRules;
import com.aerotrace.search.search.BagSearchModels.Criteria;
import com.aerotrace.search.search.BagSearchModels.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Search", description = "Bag search for staff (AGENT, DISPATCHER, ADMIN)")
class BagSearchController {

  private final BagSearchService search;

  BagSearchController(BagSearchService search) {
    this.search = search;
  }

  @GetMapping(ApiPaths.SEARCH_BAGS)
  @Operation(
      summary = "Search bags",
      description =
          "Optional filters, combined with AND. Sorted by updatedAt (newest first), then bag. "
              + "Pass nextCursor as cursor (with the same filters) for the next page; a changed "
              + "cursor is 400, an expired one 410 CURSOR_EXPIRED. 503 SEARCH_UNAVAILABLE while "
              + "the index cannot be reached.")
  ApiResponse<Page> search(
      @RequestParam(required = false)
          @Pattern(regexp = SearchRules.BAG_TAG_PATTERN, message = SearchRules.BAG_TAG_RULE)
          String bagTag,
      @RequestParam(required = false)
          @Parameter(description = "The latest scan's airport, or a leg's origin or destination")
          @Pattern(regexp = SearchRules.AIRPORT_PATTERN, message = SearchRules.AIRPORT_RULE)
          String airport,
      @RequestParam(required = false)
          @Pattern(regexp = SearchRules.AIRLINE_PATTERN, message = SearchRules.AIRLINE_RULE)
          String airline,
      @RequestParam(required = false)
          @Pattern(regexp = SearchRules.FLIGHT_PATTERN, message = SearchRules.FLIGHT_RULE)
          String flight,
      @RequestParam(required = false) RiskLevel riskLevel,
      @RequestParam(required = false) RecoveryState caseState,
      @RequestParam(defaultValue = "" + SearchRules.DEFAULT_PAGE_SIZE)
          @Min(value = 1, message = SearchRules.SIZE_RULE)
          @Max(value = SearchRules.MAX_PAGE_SIZE, message = SearchRules.SIZE_RULE)
          int size,
      @RequestParam(required = false)
          @Size(min = 1, max = SearchRules.MAX_CURSOR_LENGTH, message = "is not a valid cursor")
          String cursor) {
    Criteria criteria = new Criteria(bagTag, airport, airline, flight, riskLevel, caseState);
    return ApiResponse.success(search.search(criteria, size, cursor));
  }
}
