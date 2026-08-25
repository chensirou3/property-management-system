package com.propertyops.pms.iam;

import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.propertyops.pms.common.api.BusinessException;
import com.propertyops.pms.common.audit.AuditService;
import com.propertyops.pms.security.AuthPrincipal;
import com.propertyops.pms.security.JwtService;
import com.propertyops.pms.security.SecurityContextService;

@Service
public class AuthService {
    private final AuthRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final SecurityContextService securityContext;
    private final LoginSecurityService loginSecurity;
    private final PasswordPolicy passwordPolicy;
    private final AuditService audit;
    private final String dummyPasswordHash;

    public AuthService(AuthRepository repository, PasswordEncoder passwordEncoder, JwtService jwtService,
                       SecurityContextService securityContext, LoginSecurityService loginSecurity,
                       PasswordPolicy passwordPolicy, AuditService audit) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.securityContext = securityContext;
        this.loginSecurity = loginSecurity;
        this.passwordPolicy = passwordPolicy;
        this.audit = audit;
        this.dummyPasswordHash = passwordEncoder.encode("constant-time-missing-account-password");
    }

    public LoginResult login(String username, String password) {
        String suppliedUsername = username.trim();
        String normalizedUsername = suppliedUsername.toLowerCase(Locale.ROOT);
        loginSecurity.assertAllowed(normalizedUsername);
        var found = repository.findByUsername(suppliedUsername);
        boolean passwordMatches = passwordEncoder.matches(password,
                found.map(AuthRepository.UserAccount::passwordHash).orElse(dummyPasswordHash));
        if (found.isEmpty() || !found.get().enabled() || !passwordMatches) {
            boolean locked = loginSecurity.recordFailure(normalizedUsername,
                    found.map(AuthRepository.UserAccount::id).orElse(null));
            if (locked) throw loginSecurity.rateLimited();
            throw invalidCredentials();
        }
        AuthRepository.UserAccount account = found.get();
        AuthPrincipal principal = repository.loadPrincipal(account);
        JwtService.Token token = jwtService.issue(principal, account.sessionVersion());
        repository.markLogin(account.id());
        loginSecurity.recordSuccess(normalizedUsername, account.id());
        return new LoginResult(token.value(), token.expiresAt().toString(),
                UserProfile.from(principal, account.passwordChangeRequired()));
    }

    public UserProfile me() {
        AuthPrincipal principal = securityContext.requirePrincipal();
        boolean passwordChangeRequired = repository.findById(principal.userId())
                .map(AuthRepository.UserAccount::passwordChangeRequired).orElse(false);
        return UserProfile.from(principal, passwordChangeRequired);
    }

    @Transactional
    public LoginResult changePassword(String currentPassword, String newPassword) {
        AuthPrincipal current = securityContext.requirePrincipal();
        AuthRepository.UserAccount account = repository.findById(current.userId())
                .filter(AuthRepository.UserAccount::enabled)
                .orElseThrow(this::invalidCredentials);
        if (!passwordEncoder.matches(currentPassword, account.passwordHash())) {
            throw new BusinessException("INVALID_CURRENT_PASSWORD", "当前密码错误", HttpStatus.UNAUTHORIZED);
        }
        passwordPolicy.validate(account.username(), newPassword);
        if (passwordEncoder.matches(newPassword, account.passwordHash())) {
            throw new BusinessException("PASSWORD_REUSE_FORBIDDEN", "新密码不能与当前密码相同",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        int changed = repository.changePassword(account.id(), account.sessionVersion(),
                passwordEncoder.encode(newPassword));
        if (changed != 1) {
            throw new BusinessException("SESSION_STATE_CONFLICT", "账号会话状态已变化，请重新登录",
                    HttpStatus.CONFLICT);
        }
        AuthRepository.UserAccount refreshed = repository.findById(account.id()).orElseThrow(this::invalidCredentials);
        AuthPrincipal principal = repository.loadPrincipal(refreshed);
        JwtService.Token token = jwtService.issue(principal, refreshed.sessionVersion());
        audit.success(null, "auth:password-change", "user", account.id(), java.util.Map.of("allSessionsRevoked", true));
        return new LoginResult(token.value(), token.expiresAt().toString(), UserProfile.from(principal, false));
    }

    @Transactional
    public void revokeAllSessions() {
        AuthPrincipal current = securityContext.requirePrincipal();
        repository.revokeSessions(current.userId());
        audit.success(null, "auth:sessions-revoke", "user", current.userId(),
                java.util.Map.of("scope", "ALL"));
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
