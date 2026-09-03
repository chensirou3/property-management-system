package com.propertyops.pms.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

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
class SingleProjectSetupIntegrationTest {
    private static final String COMPANY = "青岛测试物业服务有限公司";
    private static final String PROJECT = "海风家园";
    private static final String USERNAME = "setup_admin";
    private static final String PASSWORD = "OneTime-Setup-2026!";

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("pms_setup_test")
            .withUsername("pms_test")
            .withPassword("pms_test_password");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("pms.bootstrap.admin-username", () -> "");
        registry.add("pms.bootstrap.admin-password", () -> "");
        registry.add("pms.security.jwt-secret", () -> "setup-test-jwt-secret-with-more-than-32-characters");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired NamedParameterJdbcTemplate jdbc;

    @Test
    void initializesExactlyOnceAndExposesOnlyTheConfiguredProject() throws Exception {
        mockMvc.perform(get("/api/v1/setup/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.initialized").value(false))
                .andExpect(jsonPath("$.deploymentMode").value("SINGLE_PROJECT"));

        String request = objectMapper.writeValueAsString(Map.of(
                "companyName", COMPANY,
                "projectName", PROJECT,
                "adminDisplayName", "首次管理员",
                "adminUsername", USERNAME,
                "adminPassword", PASSWORD));
        mockMvc.perform(post("/api/v1/setup/initialize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.initialized").value(true))
                .andExpect(jsonPath("$.deploymentMode").value("SINGLE_PROJECT"))
                .andExpect(jsonPath("$.companyName").value(COMPANY))
                .andExpect(jsonPath("$.projectName").value(PROJECT))
                .andExpect(jsonPath("$.adminUsername").value(USERNAME));

        mockMvc.perform(post("/api/v1/setup/initialize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SETUP_ALREADY_COMPLETED"));

        String loginResponse = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", USERNAME, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.roles[0]").value("PLATFORM_ADMIN"))
                .andExpect(jsonPath("$.user.projectIds.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(loginResponse).path("accessToken").asText();

        mockMvc.perform(get("/api/v1/data/communities")
                        .header("Authorization", "Bearer " + token)
                        .param("page", "1").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].name").value(PROJECT));

        mockMvc.perform(get("/api/v1/iam/projects")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value(PROJECT));

        mockMvc.perform(post("/api/v1/data/communities")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SINGLE_PROJECT_LIMIT"));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM community WHERE status='ACTIVE'", Map.of(), Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM system_setup WHERE initialized=TRUE AND deployment_mode='SINGLE_PROJECT'",
                Map.of(), Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE action_code='system:initialize'",
                Map.of(), Long.class)).isEqualTo(1L);
    }
}
