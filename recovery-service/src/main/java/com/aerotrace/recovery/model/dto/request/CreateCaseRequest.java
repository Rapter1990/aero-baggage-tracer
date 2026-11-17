package com.aerotrace.recovery.model.dto.request;

import com.aerotrace.recovery.constant.RecoveryRules;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Opens a recovery case for one bag and one itinerary leg. */
public record CreateCaseRequest(
    @NotBlank @Pattern(regexp = RecoveryRules.BAG_TAG_PATTERN, message = RecoveryRules.BAG_TAG_RULE)
        String bagTag,
    @NotBlank @Pattern(regexp = RecoveryRules.LEG_ID_PATTERN, message = RecoveryRules.LEG_ID_RULE)
        String legId,
    @NotBlank @Size(max = RecoveryRules.MAX_REASON_LENGTH) String reason) {}
