package com.propertyops.pms.setup;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/setup")
public class SetupController {
    private final SetupService setupService;

    public SetupController(SetupService setupService) {
        this.setupService = setupService;
    }

    @GetMapping("/status")
    SetupService.SetupStatus status() {
        return setupService.status();
    }

    @PostMapping("/initialize")
    SetupService.SetupResult initialize(@Valid @RequestBody InitializeRequest request) {
        return setupService.initialize(request);
    }

    public record InitializeRequest(
            @NotBlank @Size(min = 2, max = 160) String companyName,
            @NotBlank @Size(min = 2, max = 160) String projectName,
            @NotBlank @Size(min = 2, max = 80)
            @Pattern(regexp = "^[\\p{L}\\p{N}._-]+$", message = "账号只能包含中英文、数字、点、下划线或连字符")
            String adminUsername,
            @NotBlank @Size(min = 2, max = 120) String adminDisplayName,
            @NotBlank @Size(min = 12, max = 200) String adminPassword
    ) {}
}
