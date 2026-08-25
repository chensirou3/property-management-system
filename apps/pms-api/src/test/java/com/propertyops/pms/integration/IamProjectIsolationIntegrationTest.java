package com.propertyops.pms.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
    private static final String EMPLOYEE_PASSWORD = "integration-employee-password";

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
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

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
                .andExpect(jsonPath("$.projectIds.length()").value(1));

        String employeeToken = login(EMPLOYEE_USERNAME, EMPLOYEE_PASSWORD);

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
}
