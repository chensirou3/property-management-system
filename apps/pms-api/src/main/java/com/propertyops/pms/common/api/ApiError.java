package com.propertyops.pms.common.api;

import java.time.Instant;
import java.util.List;

public record ApiError(
        String code,
        String message,
        String requestId,
        Instant timestamp,
        List<FieldViolation> violations
) {
    public record FieldViolation(String field, String message) {}

    public static ApiError of(String code, String message, String requestId) {
        return new ApiError(code, message, requestId, Instant.now(), List.of());
    }
}

