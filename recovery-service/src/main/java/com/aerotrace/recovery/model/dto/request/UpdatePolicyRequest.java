package com.aerotrace.recovery.model.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Changes the recovery policy; {@code expectedVersion} is the version the admin last saw.
 *
 * @param minimumExpediteMinutes the least time a transfer needs, 0 to 720 minutes
 */
public record UpdatePolicyRequest(
    @NotNull @Min(0) @Max(720) Integer minimumExpediteMinutes,
    @NotNull @Min(1) Long expectedVersion) {}
