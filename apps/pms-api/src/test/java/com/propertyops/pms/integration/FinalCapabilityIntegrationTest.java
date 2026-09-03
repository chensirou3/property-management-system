package com.propertyops.pms.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
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
class FinalCapabilityIntegrationTest {
    private static final String PROJECT = "30000000-0000-0000-0000-000000000001";
    private static final String USERNAME = "g10-capability-admin";
    private static final String PASSWORD = "CapabilityFixture-2026!";

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("pms_g10_capability_test").withUsername("pms_test").withPassword("pms_test_password");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("pms.bootstrap.admin-username", () -> USERNAME);
        registry.add("pms.bootstrap.admin-password", () -> PASSWORD);
        registry.add("pms.security.jwt-secret", () -> "g10-capability-jwt-secret-with-more-than-32-characters");
        registry.add("pms.integrations.callback-signing-secret", () -> "g10-capability-callback-secret-with-more-than-32-characters");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired NamedParameterJdbcTemplate jdbc;

    @Test
    void configuresPublishesAndTransitionsTheFinalTwoPagesWithoutProductionData() throws Exception {
        String token = login();
        JsonNode initial = getJson(token, "/api/v1/dashboard/configurations?communityId=" + PROJECT + "&roleCode=ALL");
        assertThat(initial.path("items").size()).isEqualTo(4);
        assertThat(initial.path("publishedCount").asInt()).isEqualTo(4);

        JsonNode created = postOk(token, "/api/v1/dashboard/configurations", body(
                "communityId", PROJECT, "roleCode", "ALL", "widgetCode", "ARREARS_SUMMARY",
                "widgetName", "欠费概览", "metricCode", "ARREARS_SUMMARY", "positionCode", "MAIN",
                "visible", true, "refreshIntervalSeconds", 300, "displayOrder", 5));
        assertThat(created.path("status").asText()).isEqualTo("DRAFT");
        JsonNode updated = putOk(token, "/api/v1/dashboard/configurations/" + created.path("id").asText()
                + "?communityId=" + PROJECT, body("widgetName", "欠费概览（合成）", "metricCode", "ARREARS_SUMMARY",
                "positionCode", "MAIN", "visible", true, "refreshIntervalSeconds", 600, "expectedVersion", 0));
        assertThat(updated.path("version").asInt()).isEqualTo(1);
        putRequest(token, "/api/v1/dashboard/configurations/" + created.path("id").asText()
                + "?communityId=" + PROJECT, body("widgetName", "冲突写入", "metricCode", "ARREARS_SUMMARY",
                "positionCode", "MAIN", "visible", true, "refreshIntervalSeconds", 600, "expectedVersion", 0))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OPTIMISTIC_LOCK_CONFLICT"));

        JsonNode draft = getJson(token, "/api/v1/dashboard/configurations?communityId=" + PROJECT + "&roleCode=ALL");
        List<Map<String, Object>> versions = versionReferences(draft.path("items"));
        JsonNode reordered = postOk(token, "/api/v1/dashboard/configurations:reorder",
                body("communityId", PROJECT, "roleCode", "ALL", "widgets", versions));
        JsonNode published = postOk(token, "/api/v1/dashboard/configurations:publish",
                body("communityId", PROJECT, "roleCode", "ALL", "widgets", versionReferences(reordered.path("items"))));
        assertThat(published.path("publishedCount").asInt()).isEqualTo(5);
        assertThat(published.path("items").findValuesAsText("status")).containsOnly("PUBLISHED");

        postRequest(token, "/api/v1/visitors", body("communityId", PROJECT,
                "visitorNameMasked", "真实姓名", "visitorMobileMasked", "138****0004",
                "hostNameMasked", "合成住户丁**", "assetName", "4号楼-1单元-0401",
                "scheduledAt", LocalDateTime.now(ZoneOffset.UTC).plusDays(1)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SENSITIVE_VALUE_NOT_MASKED"));

        JsonNode visitor = postOk(token, "/api/v1/visitors", body("communityId", PROJECT,
                "visitorNameMasked", "合成访客丁**", "visitorMobileMasked", "136****0004",
                "hostNameMasked", "合成住户丁**", "assetName", "4号楼-1单元-0401",
                "scheduledAt", LocalDateTime.now(ZoneOffset.UTC).plusDays(1)));
        assertThat(visitor.path("visit_status").asText()).isEqualTo("REGISTERED");
        assertThat(visitor.path("simulated").asBoolean()).isTrue();
        assertThat(visitor.path("productionConnected").asBoolean()).isFalse();

        String visitorId = visitor.path("id").asText();
        JsonNode checkedIn = postOk(token, "/api/v1/visitors/" + visitorId + ":check-in",
                body("communityId", PROJECT, "expectedVersion", 0));
        assertThat(checkedIn.path("visit_status").asText()).isEqualTo("CHECKED_IN");
        assertThat(checkedIn.path("adapter_evidence").asText()).startsWith("simulated:");
        JsonNode replayed = postOk(token, "/api/v1/visitors/" + visitorId + ":check-in",
                body("communityId", PROJECT, "expectedVersion", 0));
        assertThat(replayed.path("replayed").asBoolean()).isTrue();
        JsonNode checkedOut = postOk(token, "/api/v1/visitors/" + visitorId + ":check-out",
                body("communityId", PROJECT, "expectedVersion", 1));
        assertThat(checkedOut.path("visit_status").asText()).isEqualTo("CHECKED_OUT");

        JsonNode records = getJson(token, "/api/v1/visitors?communityId=" + PROJECT + "&keyword=" + visitorId.substring(0, 8));
        assertThat(records.path("productionConnected").asBoolean()).isFalse();
        assertThat(records.path("sensitiveFieldsStoredMasked").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM visitor_record WHERE production_connected=TRUE", Map.of(), Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE action_code LIKE 'visitor:%' OR action_code LIKE 'dashboard-widget:%'", Map.of(), Integer.class)).isGreaterThanOrEqualTo(7);
    }

    private List<Map<String, Object>> versionReferences(JsonNode items) {
        List<Map<String, Object>> result = new ArrayList<>();
        items.forEach(item -> result.add(body("id", item.path("id").asText(), "expectedVersion", item.path("version").asLong())));
        return result;
    }

    private String login() throws Exception {
        return postOk(null, "/api/v1/auth/login", body("username", USERNAME, "password", PASSWORD)).path("accessToken").asText();
    }

    private JsonNode getJson(String token, String path) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode postOk(String token, String path, Object body) throws Exception {
        return objectMapper.readTree(postRequest(token, path, body).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode putOk(String token, String path, Object body) throws Exception {
        return objectMapper.readTree(putRequest(token, path, body).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private org.springframework.test.web.servlet.ResultActions postRequest(String token, String path, Object body) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return mockMvc.perform(request);
    }

    private org.springframework.test.web.servlet.ResultActions putRequest(String token, String path, Object body) throws Exception {
        return mockMvc.perform(put(path).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private Map<String, Object> body(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) result.put(String.valueOf(values[index]), values[index + 1]);
        return result;
    }
}
