package com.aerotrace.capacity.web;

import com.aerotrace.capacity.constant.ApiPaths;
import com.aerotrace.capacity.constant.CapacityRules;
import com.aerotrace.capacity.constant.DocsConstants;
import com.aerotrace.capacity.docs.ApiExamples;
import com.aerotrace.capacity.model.dto.ReservationRequest;
import com.aerotrace.capacity.model.dto.ReservationResponse;
import com.aerotrace.capacity.service.CapacityService;
import com.aerotrace.common.api.ApiResponse;
import com.aerotrace.common.error.ApiException;
import com.aerotrace.common.error.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = DocsConstants.TAG_RESERVATIONS)
@SecurityRequirement(name = DocsConstants.BEARER_AUTH)
public class ReservationController {

  /** Visible ASCII only, so the key is safe in logs and headers. */
  private static final Pattern IDEMPOTENCY_KEY =
      Pattern.compile("^[\\x21-\\x7E]{1," + CapacityRules.MAX_IDEMPOTENCY_KEY_LENGTH + "}$");

  private final CapacityService service;

  public ReservationController(CapacityService service) {
    this.service = service;
  }

  @Operation(
      summary = "Hold slots",
      description =
          "Agents, dispatchers and admins. The caller chooses reservationId; legId names the"
              + " resource. The slots are taken atomically: 50 parallel requests for 10 slots"
              + " succeed exactly 10 times. Repeating a request (same reservationId and content,"
              + " or the same Idempotency-Key) answers with the same hold and takes nothing more.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "201",
        description = "Held (or the existing hold of a repeated request)"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "A field or the Idempotency-Key breaks a rule",
        content =
            @Content(
                schema = @Schema(ref = DocsConstants.API_ERROR_REF),
                examples = @ExampleObject(value = ApiExamples.VALIDATION_ERROR))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description =
            "The leg has no capacity resource (CAPACITY_NOT_PROVISIONED): a set-up problem, not"
                + " a full leg; nothing was held",
        content =
            @Content(
                schema = @Schema(ref = DocsConstants.API_ERROR_REF),
                examples = @ExampleObject(value = ApiExamples.CAPACITY_NOT_PROVISIONED))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "409",
        description =
            "No capacity is left for the leg (CAPACITY_EXCEEDED, the contract the"
                + " recovery-service relies on); or the reservationId or Idempotency-Key was used"
                + " for a different request, or the hold is already released (VERSION_CONFLICT)",
        content =
            @Content(
                schema = @Schema(ref = DocsConstants.API_ERROR_REF),
                examples = {
                  @ExampleObject(name = "capacityExceeded", value = ApiExamples.CAPACITY_EXCEEDED),
                  @ExampleObject(
                      name = "reservationIdReused",
                      value = ApiExamples.RESERVATION_ID_REUSED)
                }))
  })
  @PostMapping(ApiPaths.RESERVATIONS)
  @ResponseStatus(HttpStatus.CREATED)
  public ApiResponse<ReservationResponse> reserve(
      @Valid @RequestBody ReservationRequest body,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    requireValidKey(idempotencyKey);
    return ApiResponse.success(service.reserve(body, idempotencyKey));
  }

  @Operation(
      summary = "Release a hold",
      description =
          "Agents, dispatchers, admins, or a service with the service token (the recovery-service"
              + " sweeper has no user token). Returns the slots once: releasing a hold that is"
              + " already released changes nothing. 404 means there is no such hold, which the"
              + " caller treats as released.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "204",
        description = "Released (or it already was)"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "No such hold",
        content =
            @Content(
                schema = @Schema(ref = DocsConstants.API_ERROR_REF),
                examples = @ExampleObject(value = ApiExamples.RESERVATION_NOT_FOUND)))
  })
  @DeleteMapping(ApiPaths.RESERVATION)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void release(@PathVariable("id") UUID id) {
    if (!service.release(id)) {
      throw new ApiException(ErrorCode.NOT_FOUND, CapacityRules.NO_SUCH_RESERVATION);
    }
  }

  @Operation(
      summary = "Confirm a hold",
      description =
          "Agents, dispatchers, admins, or a service with the service token. Says that a case move"
              + " kept the hold, so the expiry job never takes its slots. Repeating it changes"
              + " nothing; a released or expired hold answers 409.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "Confirmed: the hold with state CONFIRMED"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "No such hold",
        content =
            @Content(
                schema = @Schema(ref = DocsConstants.API_ERROR_REF),
                examples = @ExampleObject(value = ApiExamples.RESERVATION_NOT_FOUND))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "409",
        description = "The hold is already released or expired (ILLEGAL_TRANSITION)",
        content = @Content(schema = @Schema(ref = DocsConstants.API_ERROR_REF)))
  })
  @PutMapping(ApiPaths.CONFIRMATION)
  public ApiResponse<ReservationResponse> confirm(@PathVariable("id") UUID id) {
    return ApiResponse.success(service.confirm(id));
  }

  private static void requireValidKey(String idempotencyKey) {
    if (idempotencyKey != null && !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
      throw new ApiException(ErrorCode.VALIDATION_ERROR, CapacityRules.IDEMPOTENCY_KEY_INVALID);
    }
  }
}
