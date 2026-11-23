package com.aerotrace.notification.web;

import com.aerotrace.common.api.ApiResponse;
import com.aerotrace.common.error.ApiException;
import com.aerotrace.common.error.ErrorCode;
import com.aerotrace.common.security.SecurityContextHelper;
import com.aerotrace.notification.constant.ApiPaths;
import com.aerotrace.notification.constant.DocsConstants;
import com.aerotrace.notification.constant.ErrorMessages;
import com.aerotrace.notification.constant.NotificationRules;
import com.aerotrace.notification.docs.ApiExamples;
import com.aerotrace.notification.model.dto.response.NotificationResponse;
import com.aerotrace.notification.model.dto.response.PageResponse;
import com.aerotrace.notification.service.NotificationQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's own notifications, read only (ADR 0005, decision 13). The recipient is always the
 * token subject; nothing in the request can change it.
 */
@RestController
@Tag(name = DocsConstants.TAG_NOTIFICATIONS)
@SecurityRequirement(name = DocsConstants.BEARER_AUTH)
public class NotificationController {

  private final NotificationQueryService service;

  public NotificationController(NotificationQueryService service) {
    this.service = service;
  }

  @Operation(
      summary = "List my notifications",
      description =
          "Every role. The caller's own notifications, newest first (createdAt, then id). A page"
              + " past the end is an empty page. size is 1 to 100; anything else is 400.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "One page of the caller's notifications",
        content = @Content(examples = @ExampleObject(value = ApiExamples.LIST))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "page below 0, size outside 1 to 100, or either not a number",
        content =
            @Content(
                schema = @Schema(ref = DocsConstants.API_ERROR_REF),
                examples = @ExampleObject(value = ApiExamples.VALIDATION_ERROR)))
  })
  @GetMapping(ApiPaths.NOTIFICATIONS)
  public ApiResponse<PageResponse<NotificationResponse>> list(
      @RequestParam(value = "page", defaultValue = "0")
          @Min(value = 0, message = "page must be 0 or more")
          int page,
      @RequestParam(value = "size", defaultValue = "" + NotificationRules.DEFAULT_PAGE_SIZE)
          @Min(value = 1, message = ErrorMessages.SIZE_RULE)
          @Max(value = NotificationRules.MAX_PAGE_SIZE, message = ErrorMessages.SIZE_RULE)
          int size) {
    return ApiResponse.success(service.list(caller(), page, size));
  }

  @Operation(
      summary = "Read one of my notifications",
      description =
          "Every role. Another user's notification answers 404, exactly like one that does not"
              + " exist, so its existence is not revealed.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "The notification",
        content = @Content(examples = @ExampleObject(value = ApiExamples.ONE))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "The id is not a UUID",
        content =
            @Content(
                schema = @Schema(ref = DocsConstants.API_ERROR_REF),
                examples = @ExampleObject(value = ApiExamples.VALIDATION_ERROR))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "No such notification, or it belongs to someone else",
        content =
            @Content(
                schema = @Schema(ref = DocsConstants.API_ERROR_REF),
                examples = @ExampleObject(value = ApiExamples.NOT_FOUND)))
  })
  @GetMapping(ApiPaths.NOTIFICATION)
  public ApiResponse<NotificationResponse> get(@PathVariable("id") UUID id) {
    return ApiResponse.success(service.get(id, caller()));
  }

  /** The token subject; the security chain only lets authenticated callers this far. */
  private static String caller() {
    return SecurityContextHelper.currentSubject()
        .filter(subject -> !subject.isBlank())
        .orElseThrow(
            () -> new ApiException(ErrorCode.UNAUTHENTICATED, "Authentication is required"));
  }
}
