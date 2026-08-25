package com.propertyops.pms.dashboard;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class DashboardConfigurationModels {
    private DashboardConfigurationModels() {}

    public record CreateWidgetRequest(
            @NotBlank String communityId,
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,79}") String roleCode,
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,79}") String widgetCode,
            @NotBlank @Size(max = 160) String widgetName,
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,79}") String metricCode,
            @NotBlank @Pattern(regexp = "SUMMARY|MAIN|SIDE") String positionCode,
            @NotNull Boolean visible,
            @Min(30) @Max(3600) int refreshIntervalSeconds,
            @Min(1) @Max(100) int displayOrder) {}

    public record UpdateWidgetRequest(
            @NotBlank @Size(max = 160) String widgetName,
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,79}") String metricCode,
            @NotBlank @Pattern(regexp = "SUMMARY|MAIN|SIDE") String positionCode,
            @NotNull Boolean visible,
            @Min(30) @Max(3600) int refreshIntervalSeconds,
            @Min(0) long expectedVersion) {}

    public record VersionReference(@NotBlank String id, @Min(0) long expectedVersion) {}

    public record ScopeCommand(
            @NotBlank String communityId,
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,79}") String roleCode,
            @NotEmpty List<@Valid @NotNull VersionReference> widgets) {}
}
