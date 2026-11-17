package com.aerotrace.recovery.web;

import com.aerotrace.common.api.ApiResponse;
import com.aerotrace.recovery.constant.ApiPaths;
import com.aerotrace.recovery.constant.DocsConstants;
import com.aerotrace.recovery.docs.ApiExamples;
import com.aerotrace.recovery.model.dto.request.UpdatePolicyRequest;
import com.aerotrace.recovery.policy.PolicyService;
import com.aerotrace.recovery.policy.PolicyView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = DocsConstants.TAG_POLICY)
@SecurityRequirement(name = DocsConstants.BEARER_AUTH)
public class PolicyController {

  private final PolicyService service;

  public PolicyController(PolicyService service) {
    this.service = service;
  }

  @Operation(
      summary = "The recovery policy",
      description =
          "Agents, dispatchers and admins. The least time a transfer needs: with less left a bag"
              + " is rerouted. Served from a Redis cache with a short TTL; when Redis is down the"
              + " database answers.")
  @GetMapping(ApiPaths.POLICY)
  public ApiResponse<PolicyView> get() {
    return ApiResponse.success(service.current());
  }

  @Operation(
      summary = "Change the recovery policy",
      description =
          "Admins only. expectedVersion must be the version last read (409 VERSION_CONFLICT"
              + " otherwise). The cached copy is evicted, so the change is seen at once.")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "Changed: the policy with its new version"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "A value is missing or outside 0 to 720 minutes",
        content =
            @Content(
                schema = @Schema(ref = DocsConstants.API_ERROR_REF),
                examples = @ExampleObject(value = ApiExamples.VALIDATION_ERROR))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "409",
        description = "The policy was changed by someone else (VERSION_CONFLICT)",
        content =
            @Content(
                schema = @Schema(ref = DocsConstants.API_ERROR_REF),
                examples = @ExampleObject(value = ApiExamples.POLICY_VERSION_CONFLICT)))
  })
  @PutMapping(ApiPaths.POLICY)
  public ApiResponse<PolicyView> change(@Valid @RequestBody UpdatePolicyRequest body) {
    return ApiResponse.success(
        service.change(body.minimumExpediteMinutes(), body.expectedVersion()));
  }
}
