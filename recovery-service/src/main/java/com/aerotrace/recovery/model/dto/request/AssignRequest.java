package com.aerotrace.recovery.model.dto.request;

import com.aerotrace.recovery.constant.RecoveryRules;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Assigns a case to a handler.
 *
 * @param assignee the agent or dispatcher taking the case: their token subject, as shown in the
 *     history of the cases they moved
 * @param expectedVersion the version the caller last saw
 */
public record AssignRequest(
    @NotBlank
        @Pattern(regexp = RecoveryRules.ASSIGNEE_PATTERN, message = RecoveryRules.ASSIGNEE_RULE)
        String assignee,
    @NotNull @Min(1) Long expectedVersion) {}
