package com.propertyops.pms.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class IamProjectIsolationIntegrationTest {
    private static final String PRIMARY_PROJECT = "30000000-0000-0000-0000-000000000001";
    private static final String ISOLATED_PROJECT = "30000000-0000-0000-0000-000000000002";
    private static final String PROJECT_MANAGER_ROLE = "10000000-0000-0000-0000-000000000002";
    private static final String SEEDED_EMPLOYEE = "34000000-0000-0000-0000-000000000001";
    private static final String ADMIN_USERNAME = "integration-admin";
    private static final String ADMIN_PASSWORD = "integration-admin-password";
    private static final String EMPLOYEE_USERNAME = "integration-project-manager";
    private static final String EMPLOYEE_PASSWORD = "Integration-Employee-2026!";
    private static final String CHANGED_PASSWORD = "Changed-Employee-2026!";

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("pms_iam_test")
            .withUsername("pms_test")
            .withPassword("pms_test_password");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("pms.bootstrap.admin-username", () -> ADMIN_USERNAME);
        registry.add("pms.bootstrap.admin-password", () -> ADMIN_PASSWORD);
        registry.add("pms.security.jwt-secret", () -> "integration-only-jwt-secret-with-more-than-32-characters");
        registry.add("pms.security.login-max-failures", () -> "3");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired NamedParameterJdbcTemplate jdbc;

    @Test
    void ordinaryAccountSeesOnlyGrantedProjectAndCannotUseIamAdministration() throws Exception {
        String adminToken = login(ADMIN_USERNAME, ADMIN_PASSWORD);

        mockMvc.perform(get("/api/v1/iam/projects").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        String request = objectMapper.writeValueAsString(Map.of(
                "username", EMPLOYEE_USERNAME,
                "password", EMPLOYEE_PASSWORD,
                "displayName", "隔离测试项目经理",
                "employeeId", SEEDED_EMPLOYEE,
                "enabled", true,
                "roleIds", Set.of(PROJECT_MANAGER_ROLE),
                "projectIds", Set.of(PRIMARY_PROJECT)
        ));
        mockMvc.perform(post("/api/v1/iam/users")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(EMPLOYEE_USERNAME))
                .andExpect(jsonPath("$.projectIds.length()").value(1))
                .andExpect(jsonPath("$.passwordChangeRequired").value(true));

        String temporaryToken = login(EMPLOYEE_USERNAME, EMPLOYEE_PASSWORD);
        mockMvc.perform(get("/api/v1/data/communities")
                        .header("Authorization", bearer(temporaryToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));

        String passwordResponse = mockMvc.perform(put("/api/v1/auth/change-password")
                        .header("Authorization", bearer(temporaryToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "currentPassword", EMPLOYEE_PASSWORD,
                                "newPassword", CHANGED_PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.passwordChangeRequired").value(false))
                .andReturn().getResponse().getContentAsString();
        String employeeToken = objectMapper.readTree(passwordResponse).path("accessToken").asText();

        mockMvc.perform(get("/api/v1/data/communities")
                        .header("Authorization", bearer(employeeToken))
                        .param("page", "1").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(PRIMARY_PROJECT));

        mockMvc.perform(get("/api/v1/data/assets")
                        .header("Authorization", bearer(employeeToken))
                        .param("communityId", PRIMARY_PROJECT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(609));

        mockMvc.perform(get("/api/v1/data/assets")
                        .header("Authorization", bearer(employeeToken))
                        .param("communityId", ISOLATED_PROJECT))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PROJECT_ACCESS_DENIED"));

        mockMvc.perform(get("/api/v1/iam/users").header("Authorization", bearer(employeeToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));

        mockMvc.perform(put("/api/v1/iam/users/{id}", userId(EMPLOYEE_USERNAME))
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "displayName", "隔离测试项目经理",
                                "enabled", true,
                                "passwordChangeRequired", false,
                                "roleIds", Set.of(PROJECT_MANAGER_ROLE),
                                "projectIds", Set.of(PRIMARY_PROJECT),
                                "expectedVersion", 1))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/data/communities")
                        .header("Authorization", bearer(employeeToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void repeatedInvalidLoginsAreRateLimitedAndAuditedWithoutRawIdentity() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "username", "missing-rate-limit-account", "password", "Wrong-Password-2026!"));
        for (int attempt = 1; attempt <= 2; attempt++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .with(request -> { request.setRemoteAddr("198.51.100.24"); return request; })
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }
        mockMvc.perform(post("/api/v1/auth/login")
                        .with(request -> { request.setRemoteAddr("198.51.100.24"); return request; })
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_LOGIN_ATTEMPTS"));
        mockMvc.perform(post("/api/v1/auth/login")
                        .with(request -> { request.setRemoteAddr("198.51.100.24"); return request; })
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests());

        Long failures = jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_event WHERE action_code='AUTH_LOGIN_FAILURE'
                """, Map.of(), Long.class);
        Long denied = jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_event WHERE action_code='AUTH_LOGIN_RATE_LIMITED'
                """, Map.of(), Long.class);
        Long leaked = jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_event WHERE detail_json LIKE '%missing-rate-limit-account%'
                """, Map.of(), Long.class);
        assertThat(failures).isGreaterThanOrEqualTo(3);
        assertThat(denied).isGreaterThanOrEqualTo(1);
        assertThat(leaked).isZero();
    }

    private String login(String username, String password) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(response).path("accessToken").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private String userId(String username) {
        return jdbc.queryForObject("SELECT id FROM sys_user WHERE username=:username",
                Map.of("username", username), String.class);
    }
}
