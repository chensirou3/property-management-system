package com.propertyops.pms.adapter;

import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class IntegrationModels {
    private IntegrationModels() {}

    public record SimulatedOutboxRequest(
            @NotBlank @Size(max = 36) String communityId,
            @NotBlank @Size(max = 80) String eventType,
            Map<String, Object> payload
    ) {}

    public record SimulatedDeliveryRequest(
            @NotBlank @Size(max = 80) String adapterCode,
            @NotBlank @Pattern(regexp = "SUCCEEDED|RETRYABLE_FAILURE") String outcome
    ) {}
}
