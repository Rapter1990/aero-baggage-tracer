package com.aerotrace.recovery.web;

import com.aerotrace.common.api.ApiResponse;
import com.aerotrace.recovery.constant.ApiPaths;
import com.aerotrace.recovery.model.dto.request.UpdatePolicyRequest;
import com.aerotrace.recovery.policy.PolicyService;
import com.aerotrace.recovery.policy.PolicyView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Recovery policy")
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
  @PutMapping(ApiPaths.POLICY)
  public ApiResponse<PolicyView> change(@Valid @RequestBody UpdatePolicyRequest body) {
    return ApiResponse.success(
        service.change(body.minimumExpediteMinutes(), body.expectedVersion()));
  }
}
