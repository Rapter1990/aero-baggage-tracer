package com.aerotrace.capacity.web;

import com.aerotrace.capacity.constant.ApiPaths;
import com.aerotrace.capacity.constant.CapacityRules;
import com.aerotrace.capacity.constant.DocsConstants;
import com.aerotrace.capacity.docs.ApiExamples;
import com.aerotrace.capacity.model.dto.ResourceRequest;
import com.aerotrace.capacity.model.dto.ResourceResponse;
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
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = DocsConstants.TAG_RESOURCES)
@SecurityRequirement(name = DocsConstants.BEARER_AUTH)
public class ResourceController {

  /** Letters, digits, dot, dash and underscore: safe in a path, a log line and an event key. */
  private static final Pattern RESOURCE_ID =
      Pattern.compile("^[A-Za-z0-9._-]{1," + CapacityRules.MAX_RESOURCE_ID_LENGTH + "}$");

  private final CapacityService service;

  public ResourceController(CapacityService service) {
    this.service = service;
  }

  @Operation(
      summary = "Create a resource or set its total",
      description =
          "Admins only. Creates the resource, or sets its total: the free slots move by the same"
              + " difference. A total lower than the slots already held answers 409"
              + " CAPACITY_EXCEEDED.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "The resource with its total and free slots"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "The id or the total breaks a rule",
        content =
            @Content(
                schema = @Schema(ref = DocsConstants.API_ERROR_REF),
                examples = @ExampleObject(value = ApiExamples.VALIDATION_ERROR))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "409",
        description = "The total is lower than the slots held (CAPACITY_EXCEEDED)",
        content =
            @Content(
                schema = @Schema(ref = DocsConstants.API_ERROR_REF),
                examples = @ExampleObject(value = ApiExamples.CAPACITY_EXCEEDED)))
  })
  @PutMapping(ApiPaths.RESOURCE)
  public ApiResponse<ResourceResponse> put(
      @PathVariable("id") String id, @Valid @RequestBody ResourceRequest body) {
    if (!RESOURCE_ID.matcher(id).matches()) {
      throw new ApiException(ErrorCode.VALIDATION_ERROR, CapacityRules.NO_SUCH_RESOURCE);
    }
    return ApiResponse.success(service.setTotal(id, body.totalSlots()));
  }

  @Operation(summary = "Read one resource", description = "Staff only.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "No such resource",
        content =
            @Content(
                schema = @Schema(ref = DocsConstants.API_ERROR_REF),
                examples = @ExampleObject(value = ApiExamples.RESOURCE_NOT_FOUND)))
  })
  @GetMapping(ApiPaths.RESOURCE)
  public ApiResponse<ResourceResponse> get(@PathVariable("id") String id) {
    return ApiResponse.success(service.getResource(id));
  }

  @Operation(summary = "List the resources", description = "Staff only. Ordered by id.")
  @GetMapping(ApiPaths.RESOURCES)
  public ApiResponse<List<ResourceResponse>> list() {
    return ApiResponse.success(service.listResources());
  }
}
