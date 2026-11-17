package com.aerotrace.recovery.web;

import com.aerotrace.common.api.ApiResponse;
import com.aerotrace.common.error.ApiException;
import com.aerotrace.common.error.ErrorCode;
import com.aerotrace.common.model.RecoveryState;
import com.aerotrace.recovery.constant.ApiPaths;
import com.aerotrace.recovery.constant.RecoveryRules;
import com.aerotrace.recovery.model.dto.request.CreateCaseRequest;
import com.aerotrace.recovery.model.dto.request.TransitionRequest;
import com.aerotrace.recovery.model.dto.response.CaseResponse;
import com.aerotrace.recovery.model.dto.response.PageResponse;
import com.aerotrace.recovery.service.RecoveryCaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Recovery cases")
public class RecoveryCaseController {

  /** Visible ASCII only, so the key is safe in logs and headers. */
  private static final Pattern IDEMPOTENCY_KEY =
      Pattern.compile("^[\\x21-\\x7E]{1," + RecoveryRules.MAX_IDEMPOTENCY_KEY_LENGTH + "}$");

  private final RecoveryCaseService service;

  public RecoveryCaseController(RecoveryCaseService service) {
    this.service = service;
  }

  @Operation(
      summary = "Open a recovery case",
      description =
          "Agents, dispatchers and admins. One active case can exist per bag and leg: a second"
              + " request answers 409 DUPLICATE_RECOVERY_CASE. Repeating a request with the same"
              + " Idempotency-Key returns the original case.")
  @PostMapping(ApiPaths.CASES)
  @ResponseStatus(HttpStatus.CREATED)
  public ApiResponse<CaseResponse> create(
      @Valid @RequestBody CreateCaseRequest body,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    if (idempotencyKey != null && !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
      throw new ApiException(ErrorCode.VALIDATION_ERROR, RecoveryRules.IDEMPOTENCY_KEY_INVALID);
    }
    return ApiResponse.success(service.create(body, idempotencyKey));
  }

  @Operation(
      summary = "Move a case to another state",
      description =
          "Agents and dispatchers only. The move must follow the state machine (409"
              + " ILLEGAL_TRANSITION otherwise) and expectedVersion must be the current version (409"
              + " VERSION_CONFLICT otherwise). Who, when and why are recorded.")
  @PatchMapping(ApiPaths.TRANSITIONS)
  public ApiResponse<CaseResponse> transition(
      @PathVariable("id") UUID id, @Valid @RequestBody TransitionRequest body) {
    return ApiResponse.success(service.transition(id, body));
  }

  @Operation(
      summary = "List recovery cases",
      description =
          "Staff only. Oldest first with the case id as tie-breaker, so pages are stable. Filter"
              + " by state; size is capped at 100.")
  @GetMapping(ApiPaths.CASES)
  public ApiResponse<PageResponse<CaseResponse>> list(
      @RequestParam(value = "state", required = false) RecoveryState state,
      @RequestParam(value = "page", defaultValue = "0") int page,
      @RequestParam(value = "size", defaultValue = "" + RecoveryRules.DEFAULT_PAGE_SIZE) int size) {
    return ApiResponse.success(service.list(state, page, size));
  }
}
