package com.propertyops.pms.visitor;

import java.time.LocalDateTime;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class VisitorModels {
    private VisitorModels() {}

    public record RegisterRequest(
            @NotBlank String communityId,
            @NotBlank @Size(max = 120) String visitorNameMasked,
            @NotBlank @Pattern(regexp = "\\d{3}\\*{4}\\d{4}", message = "联系方式必须为脱敏格式，例如 138****0001") String visitorMobileMasked,
            @NotBlank @Size(max = 120) String hostNameMasked,
            @NotBlank @Size(max = 160) String assetName,
            @NotNull @FutureOrPresent LocalDateTime scheduledAt) {}

    public record TransitionRequest(@NotBlank String communityId, @Min(0) long expectedVersion) {}
}
