package com.propertyops.pms.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

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
class IntegrationGovernanceIntegrationTest {
    private static final String PROJECT = "30000000-0000-0000-0000-000000000001";
    private static final String USERNAME = "integration-governance-admin";
    private static final String PASSWORD = "GovernanceFixture-2026!";
    private static final String CALLBACK_SECRET = "integration-callback-test-secret-with-more-than-32-characters";

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("pms_integration_test").withUsername("pms_test").withPassword("pms_test_password");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("pms.bootstrap.admin-username", () -> USERNAME);
        registry.add("pms.bootstrap.admin-password", () -> PASSWORD);
        registry.add("pms.security.jwt-secret", () -> "integration-governance-jwt-secret-with-more-than-32-characters");
        registry.add("pms.integrations.callback-signing-secret", () -> CALLBACK_SECRET);
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired NamedParameterJdbcTemplate jdbc;

    @Test
    void signsReplaysRetriesDeadLettersAndObservesAllFailClosedAdapters() throws Exception {
        String token = login();
        JsonNode workbench = getJson(token, "/api/v1/integrations/workbench?communityId=" + PROJECT);
        assertThat(workbench.path("adapters").size()).isEqualTo(5);
        assertThat(workbench.path("adapters").findValuesAsText("mode"))
                .containsExactlyInAnyOrder("SIMULATOR", "SIMULATOR", "SIMULATOR", "SIMULATOR", "DISABLED");
        assertThat(workbench.path("adapters").findValues("productionReady"))
                .allMatch(node -> !node.asBoolean());
        assertThat(workbench.path("security").path("secretsReadable").asBoolean()).isFalse();

        for (String code : new String[]{"PAYMENT_SIMULATOR", "INVOICE_SIMULATOR", "BANK_TRUST_SIMULATOR", "IOT_SIMULATOR", "JAVA110_DISABLED"}) {
            JsonNode tested = postOk(token, "/api/v1/integrations/adapters/" + code + ":test?communityId=" + PROJECT, body());
            assertThat(tested.path("adapterCode").asText()).isEqualTo(code);
            assertThat(tested.path("productionReady").asBoolean()).isFalse();
            assertThat(tested.path("outcome").asText()).isIn("SUCCEEDED", "DISABLED");
        }

        String callbackId = "g9-callback-" + UUID.randomUUID();
        String timestamp = Instant.now().toString();
        String payload = objectMapper.writeValueAsString(body("communityId", PROJECT,
                "eventType", "PAYMENT_CONFIRMED_SIMULATED", "reference", "SIMULATED-ONLY"));
        postCallback("PAYMENT_SIMULATOR", callbackId, timestamp, "00", payload)
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("CALLBACK_SIGNATURE_REJECTED"));
        String signature = sign("PAYMENT_SIMULATOR", callbackId, timestamp, payload);
        JsonNode accepted = callbackJson("PAYMENT_SIMULATOR", callbackId, timestamp, signature, payload);
        assertThat(accepted.path("replayed").asBoolean()).isFalse();
        assertThat(accepted.path("payloadChecksum").asText()).hasSize(64);
        JsonNode replayed = callbackJson("PAYMENT_SIMULATOR", callbackId, timestamp, signature, payload);
        assertThat(replayed.path("replayed").asBoolean()).isTrue();

        String conflicting = objectMapper.writeValueAsString(body("communityId", PROJECT, "eventType", "CHANGED"));
        postCallback("PAYMENT_SIMULATOR", callbackId, timestamp,
                sign("PAYMENT_SIMULATOR", callbackId, timestamp, conflicting), conflicting)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CALLBACK_REPLAY_CONFLICT"));

        JsonNode event = postOk(token, "/api/v1/integrations/outbox-events:simulate",
                body("communityId", PROJECT, "eventType", "G9_RETRY_TEST", "payload", body("source", "backend-test")));
        String eventId = event.path("id").asText();
        for (int attempt = 1; attempt <= 3; attempt++) {
            JsonNode failed = postOk(token, "/api/v1/integrations/outbox-events/" + eventId + ":simulate-delivery",
                    body("adapterCode", "PAYMENT_SIMULATOR", "outcome", "RETRYABLE_FAILURE"));
            assertThat(failed.path("retryCount").asInt()).isEqualTo(attempt);
        }
        assertThat(getOutboxStatus(eventId)).isEqualTo("DEAD_LETTER");
        String deadLetterId = jdbc.queryForObject("SELECT id FROM integration_dead_letter WHERE reference_id=:id",
                Map.of("id", eventId), String.class);
        postOk(token, "/api/v1/integrations/dead-letters/" + deadLetterId + ":replay", body());
        JsonNode delivered = postOk(token, "/api/v1/integrations/outbox-events/" + eventId + ":simulate-delivery",
                body("adapterCode", "PAYMENT_SIMULATOR", "outcome", "SUCCEEDED"));
        assertThat(delivered.path("status").asText()).isEqualTo("PUBLISHED");

        String businessEventId = UUID.randomUUID().toString();
        String businessPayload = objectMapper.writeValueAsString(body("communityId", PROJECT, "simulated", false));
        jdbc.update("""
                INSERT INTO outbox_event
                    (id,aggregate_type,aggregate_id,event_type,payload_json,request_id,payload_checksum,
                     status,available_at,retry_count,created_at,updated_at)
                VALUES (:id,'BUSINESS_LEDGER',:id,'BUSINESS_EVENT',:payload,NULL,SHA2(:payload,256),
                        'PENDING',CURRENT_TIMESTAMP(3),0,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))
                """, Map.of("id", businessEventId, "payload", businessPayload));
        mockMvc.perform(post("/api/v1/integrations/outbox-events/" + businessEventId + ":simulate-delivery")
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body(
                                "adapterCode", "PAYMENT_SIMULATOR", "outcome", "SUCCEEDED"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("OUTBOX_EVENT_NOT_FOUND"));

        postOk(token, "/api/v1/integrations/outbox-events:simulate",
                body("communityId", PROJECT, "eventType", "G9_DRAIN_TEST", "payload", body("source", "backend-test")));
        JsonNode drained = postOk(token, "/api/v1/integrations/outbox-events:drain-simulated?communityId=" + PROJECT, body());
        assertThat(drained.path("processed").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(drained.path("simulated").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE id=:id AND status='PENDING'",
                Map.of("id", businessEventId), Integer.class)).isEqualTo(1);

        getRequest("/api/v1/adapters/status", token).andExpect(status().isOk())
                .andExpect(jsonPath("$.bank.adapter").value("BANK_TRUST_SIMULATOR"));
        getRequest("/actuator/prometheus", token).andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("pms_integration_outbox_events"));
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        assertCount("SELECT COUNT(*) FROM integration_callback_inbox WHERE replay_count=1 AND status='PROCESSED'", 1);
        assertCount("SELECT COUNT(*) FROM integration_dead_letter WHERE status='RESOLVED'", 1);
        assertCount("SELECT COUNT(*) FROM integration_delivery_attempt WHERE CHAR_LENGTH(detail_checksum)<>64", 0);
        assertCount("SELECT COUNT(*) FROM outbox_event WHERE payload_checksum<>SHA2(payload_json,256)", 0);
        assertCount("SELECT COUNT(*) FROM integration_adapter_policy WHERE production_ready=TRUE", 0);
    }

    private String getOutboxStatus(String id) {
        return jdbc.queryForObject("SELECT status FROM outbox_event WHERE id=:id", Map.of("id", id), String.class);
    }

    private void assertCount(String sql, int expected) {
        assertThat(jdbc.queryForObject(sql, Map.of(), Integer.class)).isEqualTo(expected);
    }

    private String login() throws Exception {
        return postOk(null, "/api/v1/auth/login", body("username", USERNAME, "password", PASSWORD))
                .path("accessToken").asText();
    }

    private JsonNode getJson(String token, String path) throws Exception {
        return objectMapper.readTree(getRequest(path, token).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private org.springframework.test.web.servlet.ResultActions getRequest(String path, String token) throws Exception {
        return mockMvc.perform(get(path).header("Authorization", "Bearer " + token));
    }

    private JsonNode postOk(String token, String path, Object body) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return objectMapper.readTree(mockMvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode callbackJson(String adapter, String callbackId, String timestamp,
                                  String signature, String payload) throws Exception {
        return objectMapper.readTree(postCallback(adapter, callbackId, timestamp, signature, payload)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private org.springframework.test.web.servlet.ResultActions postCallback(String adapter, String callbackId,
                                                                             String timestamp, String signature,
                                                                             String payload) throws Exception {
        return mockMvc.perform(post("/api/v1/integrations/callbacks/" + adapter)
                .header("X-PMS-Callback-Id", callbackId).header("X-PMS-Timestamp", timestamp)
                .header("X-PMS-Signature", signature).contentType(MediaType.APPLICATION_JSON).content(payload));
    }

    private String sign(String adapter, String callbackId, String timestamp, String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(CALLBACK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal((timestamp + "\n" + adapter + "\n" + callbackId + "\n" + payload)
                .getBytes(StandardCharsets.UTF_8)));
    }

    private Map<String, Object> body(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) result.put(String.valueOf(values[index]), values[index + 1]);
        return result;
    }
}
