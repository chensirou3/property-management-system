package com.propertyops.pms.iam;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    AuthService.LoginResult login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request.username(), request.password());
    }

    @GetMapping("/me")
    AuthService.UserProfile me() {
        return authService.me();
    }

    @PutMapping("/change-password")
    AuthService.LoginResult changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        return authService.changePassword(request.currentPassword(), request.newPassword());
    }

    @PostMapping("/sessions:revoke")
    void revokeAllSessions() {
        authService.revokeAllSessions();
    }

    public record LoginRequest(
            @NotBlank @Size(max = 80) String username,
            @NotBlank @Size(min = 8, max = 200) String password
    ) {}

    public record ChangePasswordRequest(
            @NotBlank @Size(min = 8, max = 200) String currentPassword,
            @NotBlank @Size(min = 12, max = 200) String newPassword
    ) {}
}
