package com.aerotrace.recovery.model.dto.request;

import com.aerotrace.common.model.RecoveryState;
import com.aerotrace.recovery.constant.RecoveryRules;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Moves a case to another state; {@code expectedVersion} is the version the caller last saw. */
public record TransitionRequest(
    @NotNull RecoveryState targetState,
    @NotNull @Min(1) Long expectedVersion,
    @NotBlank @Size(max = RecoveryRules.MAX_REASON_LENGTH) String reason) {}
