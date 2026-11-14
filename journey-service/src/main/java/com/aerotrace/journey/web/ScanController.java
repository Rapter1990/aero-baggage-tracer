package com.aerotrace.journey.web;

import com.aerotrace.common.api.ApiResponse;
import com.aerotrace.common.error.ApiException;
import com.aerotrace.common.error.ErrorCode;
import com.aerotrace.journey.constant.ApiMessages;
import com.aerotrace.journey.constant.ApiPaths;
import com.aerotrace.journey.constant.DocsConstants;
import com.aerotrace.journey.constant.JourneyRules;
import com.aerotrace.journey.model.dto.request.ScanRequest;
import com.aerotrace.journey.model.dto.response.ScanAcceptedResponse;
import com.aerotrace.journey.service.ScanIngestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = DocsConstants.TAG_SCANS)
public class ScanController {

  /** Visible ASCII only, so the key is safe in logs and headers. */
  private static final Pattern IDEMPOTENCY_KEY =
      Pattern.compile("^[\\x21-\\x7E]{1," + JourneyRules.MAX_IDEMPOTENCY_KEY_LENGTH + "}$");

  private final ScanIngestionService service;

  public ScanController(ScanIngestionService service) {
    this.service = service;
  }

  @Operation(
      summary = "Ingest a scan",
      description =
          "Accepts a scan from a barcode, RFID or conveyor device and queues its event"
              + " (baggage.scan.received.v1). Repeating the request with the same Idempotency-Key"
              + " returns the original answer and creates nothing new.",
      security = @SecurityRequirement(name = DocsConstants.BEARER_AUTH))
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "202",
        description = "Accepted and queued"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "The scan, the protocol payload or the Idempotency-Key is invalid"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "The bag is not registered")
  })
  @PostMapping(ApiPaths.SCANS)
  @ResponseStatus(HttpStatus.ACCEPTED)
  public ApiResponse<ScanAcceptedResponse> ingest(
      @Parameter(description = "A unique key per scan, chosen by the device (1-128 characters).")
          @RequestHeader(name = JourneyRules.IDEMPOTENCY_KEY_HEADER, required = false)
          String idempotencyKey,
      @Valid @RequestBody ScanRequest body) {
    if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
      throw new ApiException(ErrorCode.VALIDATION_ERROR, ApiMessages.IDEMPOTENCY_KEY_REQUIRED);
    }
    return ApiResponse.success(service.ingest(body, idempotencyKey));
  }
}
