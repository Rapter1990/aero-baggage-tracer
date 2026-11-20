package com.aerotrace.recovery.web;

import com.aerotrace.common.api.ApiResponse;
import com.aerotrace.common.error.ApiException;
import com.aerotrace.common.error.ErrorCode;
import com.aerotrace.common.event.CaseChangedPayload;
import com.aerotrace.common.export.ExportApi;
import com.aerotrace.common.export.ExportPage;
import com.aerotrace.recovery.constant.RecoveryRules;
import com.aerotrace.recovery.service.CaseExportService;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /internal/v1/export/cases?after=&size=}: every case in {@code caseId} order, as {@code
 * recovery.case.changed.v1} revision 2 carries it, for the search-service's rebuild (#29). Service
 * token only (see {@code SecurityConfig}), no gateway route, not in the public OpenAPI document.
 */
@Hidden
@RestController
public class CaseExportController {

  /** A case id as this service writes it: a canonical UUID. */
  private static final Pattern CASE_ID =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

  private final CaseExportService export;

  public CaseExportController(CaseExportService export) {
    this.export = export;
  }

  @GetMapping(ExportApi.CASES)
  public ApiResponse<ExportPage<CaseChangedPayload>> cases(
      @RequestParam(value = ExportApi.AFTER, required = false) String after,
      @RequestParam(value = ExportApi.SIZE, defaultValue = "" + ExportApi.DEFAULT_SIZE) int size) {
    if (size < 1 || size > ExportApi.MAX_SIZE) {
      throw new ApiException(ErrorCode.VALIDATION_ERROR, ExportApi.SIZE_RULE);
    }
    UUID afterId = null;
    if (after != null) {
      if (!CASE_ID.matcher(after).matches()) {
        throw new ApiException(ErrorCode.VALIDATION_ERROR, RecoveryRules.EXPORT_AFTER_RULE);
      }
      afterId = UUID.fromString(after);
    }
    return ApiResponse.success(export.page(afterId, size));
  }
}
