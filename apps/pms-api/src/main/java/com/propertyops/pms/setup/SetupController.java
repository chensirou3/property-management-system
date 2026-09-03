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
            @NotBlank(message = "物业企业名称不能为空")
            @Size(min = 2, max = 160, message = "物业企业名称长度必须为 2–160 个字符") String companyName,
            @NotBlank(message = "项目名称不能为空")
            @Size(min = 2, max = 160, message = "项目名称长度必须为 2–160 个字符") String projectName,
            @NotBlank(message = "登录账号不能为空")
            @Size(min = 2, max = 80, message = "登录账号长度必须为 2–80 个字符")
            @Pattern(regexp = "^[\\p{L}\\p{N}._-]+$", message = "账号只能包含中英文、数字、点、下划线或连字符")
            String adminUsername,
            @NotBlank(message = "管理员姓名不能为空")
            @Size(min = 2, max = 120, message = "管理员姓名长度必须为 2–120 个字符") String adminDisplayName,
            @NotBlank(message = "登录密码不能为空")
            @Size(min = 12, max = 200, message = "登录密码长度必须为 12–200 个字符") String adminPassword
    ) {}
}
