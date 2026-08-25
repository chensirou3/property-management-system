package com.propertyops.pms.iam;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.propertyops.pms.common.api.BusinessException;
import com.propertyops.pms.security.AuthPrincipal;
import com.propertyops.pms.security.JwtService;
import com.propertyops.pms.security.SecurityContextService;

@Service
public class AuthService {
    private final AuthRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final SecurityContextService securityContext;

    public AuthService(AuthRepository repository, PasswordEncoder passwordEncoder, JwtService jwtService,
                       SecurityContextService securityContext) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.securityContext = securityContext;
    }

    public LoginResult login(String username, String password) {
        AuthRepository.UserAccount account = repository.findByUsername(username.trim())
                .orElseThrow(this::invalidCredentials);
        if (!account.enabled() || !passwordEncoder.matches(password, account.passwordHash())) {
            throw invalidCredentials();
        }
        AuthPrincipal principal = repository.loadPrincipal(account);
        JwtService.Token token = jwtService.issue(principal);
        repository.markLogin(account.id());
        return new LoginResult(token.value(), token.expiresAt().toString(),
                UserProfile.from(principal, account.passwordChangeRequired()));
    }

    public UserProfile me() {
        AuthPrincipal principal = securityContext.requirePrincipal();
        boolean passwordChangeRequired = repository.findByUsername(principal.username())
                .map(AuthRepository.UserAccount::passwordChangeRequired).orElse(false);
        return UserProfile.from(principal, passwordChangeRequired);
    }

    private BusinessException invalidCredentials() {
        return new BusinessException("INVALID_CREDENTIALS", "账号或密码错误", HttpStatus.UNAUTHORIZED);
    }

    public record LoginResult(String accessToken, String expiresAt, UserProfile user) {}

    public record UserProfile(String id, String username, String displayName,
                              java.util.Set<String> roles, java.util.Set<String> permissions,
                              java.util.Set<String> projectIds, boolean passwordChangeRequired) {
        static UserProfile from(AuthPrincipal principal, boolean passwordChangeRequired) {
            return new UserProfile(principal.userId(), principal.username(), principal.displayName(),
                    principal.roles(), principal.permissions(), principal.projectIds(), passwordChangeRequired);
        }
    }
}
