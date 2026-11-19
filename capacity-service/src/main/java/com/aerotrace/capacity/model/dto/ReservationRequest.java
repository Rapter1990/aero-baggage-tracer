package com.aerotrace.capacity.model.dto;

import com.aerotrace.capacity.constant.CapacityRules;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * What the recovery-service sends to hold slots. The caller chooses {@code reservationId}, so a
 * hold whose answer was lost can still be released by id, and a repeated request finds the same
 * hold. {@code legId} names the resource whose slots are taken.
 */
public record ReservationRequest(
    @NotNull @Schema(description = "Chosen by the caller; repeating it never takes slots twice")
        UUID reservationId,
    @NotNull UUID caseId,
    @NotNull @Pattern(regexp = "^[0-9]{10}$", message = "must be 10 digits") String bagTag,
    @NotBlank @Size(max = CapacityRules.MAX_RESOURCE_ID_LENGTH) String legId,
    @Min(1) @Max(CapacityRules.MAX_SLOTS_PER_RESERVATION) int slots) {}
