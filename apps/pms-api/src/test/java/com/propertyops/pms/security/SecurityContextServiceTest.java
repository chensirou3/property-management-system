package com.propertyops.pms.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.propertyops.pms.common.api.BusinessException;

class SecurityContextServiceTest {
    private final SecurityContextService security = new SecurityContextService();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void ordinaryEmployeeCanOnlyAccessAssignedProjectsAndPermissions() {
        authenticate(new AuthPrincipal(
                "employee-1",
                "employee",
                "普通员工",
                Set.of("PROJECT_EMPLOYEE"),
                Set.of("property:read"),
                Set.of("community-a", "community-b")));

        assertThatCode(() -> security.requireProject("community-a")).doesNotThrowAnyException();
        assertThatCode(() -> security.requireProject("community-b")).doesNotThrowAnyException();
        assertThatCode(() -> security.requirePermission("property:read")).doesNotThrowAnyException();

        assertThatThrownBy(() -> security.requireProject("community-outside"))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    org.assertj.core.api.Assertions.assertThat(error.getCode()).isEqualTo("PROJECT_ACCESS_DENIED");
                    org.assertj.core.api.Assertions.assertThat(error.getStatus().value()).isEqualTo(403);
                });
        assertThatThrownBy(() -> security.requirePermission("fee:write"))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        org.assertj.core.api.Assertions.assertThat(error.getCode()).isEqualTo("PERMISSION_DENIED"));
    }

    @Test
    void platformAdministratorMayCrossProjectBoundaryButNotBypassExplicitPermissions() {
        authenticate(new AuthPrincipal(
                "admin-1",
                "platform-admin",
                "平台管理员",
                Set.of("PLATFORM_ADMIN"),
                Set.of("dashboard:read"),
                Set.of()));

        assertThatCode(() -> security.requireProject("new-community")).doesNotThrowAnyException();
        assertThatThrownBy(() -> security.requirePermission("fee:write"))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        org.assertj.core.api.Assertions.assertThat(error.getCode()).isEqualTo("PERMISSION_DENIED"));
    }

    private void authenticate(AuthPrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        principal.authorities().stream().map(SimpleGrantedAuthority::new).toList()));
    }
}
