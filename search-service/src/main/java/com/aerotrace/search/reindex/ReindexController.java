package com.aerotrace.search.reindex;

import com.aerotrace.common.api.ApiResponse;
import com.aerotrace.common.error.ApiException;
import com.aerotrace.common.error.ErrorCode;
import com.aerotrace.search.constant.ApiPaths;
import com.aerotrace.search.reindex.ReindexJob.Scope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Rebuild", description = "Rebuild the search indexes from the owning services (ADMIN)")
class ReindexController {

  private final ReindexService reindex;

  ReindexController(ReindexService reindex) {
    this.reindex = reindex;
  }

  @PostMapping(ApiPaths.REINDEX)
  @Operation(
      summary = "Start a rebuild",
      description =
          "Builds new indexes from the export endpoints of the owning services and the risk "
              + "topic, checks them, then swaps the read aliases; searches keep working "
              + "meanwhile. 202 with the job; the same Idempotency-Key returns the same job. "
              + "409 REINDEX_IN_PROGRESS while another rebuild runs.")
  ResponseEntity<ApiResponse<ReindexJob>> start(
      @RequestHeader(ApiPaths.IDEMPOTENCY_KEY)
          @Size(min = 1, max = 128, message = "must be 1 to 128 characters")
          @Pattern(regexp = "^[\\x21-\\x7E]+$", message = "must be printable ASCII")
          String idempotencyKey,
      @RequestParam(defaultValue = "ALL") Scope scope,
      @AuthenticationPrincipal Jwt caller) {
    ReindexJob job = reindex.start(scope, idempotencyKey, caller.getSubject());
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .location(URI.create(ApiPaths.REINDEX + "/" + job.jobId()))
        .body(ApiResponse.success(job));
  }

  @GetMapping(ApiPaths.REINDEX_JOB)
  @Operation(summary = "Read a rebuild job")
  ApiResponse<ReindexJob> job(
      @PathVariable @Pattern(regexp = "^[0-9a-f-]{36}$", message = "is not a job id")
          String jobId) {
    return ApiResponse.success(
        reindex
            .find(jobId)
            .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No such rebuild job.")));
  }
}
