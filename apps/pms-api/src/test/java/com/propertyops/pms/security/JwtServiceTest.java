package com.propertyops.pms.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

class JwtServiceTest {
    @Test
    void issuesAndVerifiesScopedAccessToken() {
        SecurityProperties properties = new SecurityProperties();
        properties.setJwtSecret("test-only-secret-with-at-least-thirty-two-characters");
        properties.setAccessTokenMinutes(30);
        Clock clock = Clock.fixed(Instant.parse("2035-07-20T00:00:00Z"), ZoneOffset.UTC);
        JwtService service = new JwtService(properties, clock);
        AuthPrincipal principal = new AuthPrincipal(
                "user-1", "admin", "管理员", Set.of("PROJECT_MANAGER"),
                Set.of("property:read"), Set.of("community-1"));

        JwtService.Token token = service.issue(principal, 7);
        JwtService.VerifiedToken verifiedToken = service.verify(token.value());
        AuthPrincipal verified = verifiedToken.principal();

        assertThat(verified.userId()).isEqualTo("user-1");
        assertThat(verified.hasProject("community-1")).isTrue();
        assertThat(verified.authorities()).contains("ROLE_PROJECT_MANAGER", "property:read");
        assertThat(verifiedToken.sessionVersion()).isEqualTo(7);
        assertThat(token.expiresAt()).isEqualTo(Instant.parse("2035-07-20T00:30:00Z"));
    }

    @Test
    void rejectsLoginProtectionSettingsThatWouldDisableLockout() {
        SecurityProperties properties = new SecurityProperties();
        properties.setLoginMaxFailures(0);
        properties.setLoginWindowMinutes(0);
        properties.setLoginLockMinutes(0);

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(properties))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .containsExactlyInAnyOrder("loginMaxFailures", "loginWindowMinutes", "loginLockMinutes");
        }
    }
}
