package com.aerotrace.journey.web;

import com.aerotrace.common.api.ApiResponse;
import com.aerotrace.common.error.ApiException;
import com.aerotrace.common.error.ErrorCode;
import com.aerotrace.common.export.BagExport;
import com.aerotrace.common.export.ExportApi;
import com.aerotrace.common.export.ExportPage;
import com.aerotrace.journey.constant.JourneyRules;
import com.aerotrace.journey.service.BagExportService;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.regex.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /internal/v1/export/bags?after=&size=}: every bag in {@code bagTag} order with its
 * legs and latest accepted scan, for the search-service's rebuild (#28). Service token only (see
 * {@code SecurityConfig}), no gateway route, not in the public OpenAPI document.
 */
@Hidden
@RestController
public class BagExportController {

  private static final Pattern BAG_TAG = Pattern.compile(JourneyRules.BAG_TAG_PATTERN);

  private final BagExportService export;

  public BagExportController(BagExportService export) {
    this.export = export;
  }

  @GetMapping(ExportApi.BAGS)
  public ApiResponse<ExportPage<BagExport>> bags(
      @RequestParam(value = ExportApi.AFTER, required = false) String after,
      @RequestParam(value = ExportApi.SIZE, defaultValue = "" + ExportApi.DEFAULT_SIZE) int size) {
    if (size < 1 || size > ExportApi.MAX_SIZE) {
      throw new ApiException(ErrorCode.VALIDATION_ERROR, ExportApi.SIZE_RULE);
    }
    if (after != null && !BAG_TAG.matcher(after).matches()) {
      throw new ApiException(ErrorCode.VALIDATION_ERROR, JourneyRules.EXPORT_AFTER_RULE);
    }
    return ApiResponse.success(export.page(after, size));
  }
}
