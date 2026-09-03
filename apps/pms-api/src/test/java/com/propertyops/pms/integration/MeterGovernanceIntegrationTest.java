package com.propertyops.pms.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class MeterGovernanceIntegrationTest {
    private static final String PROJECT = "30000000-0000-0000-0000-000000000001";
    private static final String METER_ONE = "61000000-0000-0000-0000-000000000001";
    private static final String METER_TWO = "61000000-0000-0000-0000-000000000002";
    private static final String SHARE_RULE = "62000000-0000-0000-0000-000000000001";
    private static final String METER_STANDARD = "71000000-0000-0000-0000-000000000019";
    private static final String ADMIN_USERNAME = "meter-integration-admin";
    private static final String ADMIN_PASSWORD = "MeterFixture-2026!Secure";

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("pms_meter_test")
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
        registry.add("pms.security.jwt-secret", () -> "meter-integration-only-jwt-secret-with-more-than-32-characters");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired NamedParameterJdbcTemplate jdbc;

    @Test
    void governsReadingAnomaliesShareIotChargesAndReplacementAsOneTraceableFlow() throws Exception {
        String token = login();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("""
                UPDATE meter SET multiplier=2.000000, loss_rate=0.050000, range_value=100.0000
                WHERE id=:id
                """, Map.of("id", METER_ONE));

        Map<String, Object> augustRequest = body("communityId", PROJECT, "batchNo", "G7-AUG-" + suffix,
                "readingPeriod", "2026-08", "sourceType", "MANUAL");
        String augustKey = "g7-august-" + suffix;
        JsonNode august = postOk(token, "/api/v1/meter-reading-batches", augustRequest, augustKey);
        String augustBatchId = august.path("id").asText();
        postRequest(token, "/api/v1/meter-reading-batches", augustRequest, augustKey)
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(augustBatchId))
                .andExpect(jsonPath("$.replayed").value(true));
        postRequest(token, "/api/v1/meter-reading-batches", body("communityId", PROJECT,
                        "batchNo", "G7-AUG-DIFFERENT-" + suffix, "readingPeriod", "2026-08", "sourceType", "MANUAL"),
                        augustKey)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_REQUEST_CONFLICT"));

        Map<String, Object> crossPeriod = readingBody(augustBatchId, METER_ONE, null,
                new BigDecimal("10"), "2026-07-31T12:00:00");
        postRequest(token, "/api/v1/meter-readings:input", crossPeriod, null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("METER_READING_CROSS_PERIOD"));

        Map<String, Object> normalInput = readingBody(augustBatchId, METER_ONE, null,
                new BigDecimal("10"), "2026-08-15T12:00:00");
        postRequest(token, "/api/v1/meter-readings:input", normalInput, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.anomalies").value(0));
        postRequest(token, "/api/v1/meter-readings:input", normalInput, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(1));
        Map<String, Object> normalRow = jdbc.queryForMap("""
                SELECT adjusted_usage, billable_usage, validation_status, calculation_snapshot,
                       calculation_checksum FROM meter_reading WHERE batch_id=:batchId AND meter_id=:meterId
                """, Map.of("batchId", augustBatchId, "meterId", METER_ONE));
        assertThat((BigDecimal) normalRow.get("adjusted_usage")).isEqualByComparingTo("21.0000");
        assertThat((BigDecimal) normalRow.get("billable_usage")).isEqualByComparingTo("21.0000");
        assertThat(normalRow.get("validation_status")).isEqualTo("NORMAL");
        assertThat(normalRow.get("calculation_checksum")).isEqualTo(
                sha256(String.valueOf(normalRow.get("calculation_snapshot"))));
        postRequest(token, "/api/v1/meter-reading-batches/" + augustBatchId + ":approve?communityId=" + PROJECT,
                null, null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));

        JsonNode september = postOk(token, "/api/v1/meter-reading-batches", body(
                "communityId", PROJECT, "batchNo", "G7-SEP-" + suffix,
                "readingPeriod", "2026-09", "sourceType", "MIXED"), "g7-september-" + suffix);
        String septemberBatchId = september.path("id").asText();
        Map<String, Object> anomalyInput = readingBody(septemberBatchId, METER_ONE, new BigDecimal("9"),
                new BigDecimal("20"), "2026-09-15T12:00:00");
        postRequest(token, "/api/v1/meter-readings:input", anomalyInput, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.anomalies").value(1));
        Map<String, Object> anomaly = jdbc.queryForMap("""
                SELECT id, anomaly_code, validation_status, version FROM meter_reading
                WHERE batch_id=:batchId AND meter_id=:meterId
                """, Map.of("batchId", septemberBatchId, "meterId", METER_ONE));
        assertThat(anomaly.get("anomaly_code")).isEqualTo("PREVIOUS_MISMATCH");
        postRequest(token, "/api/v1/meter-reading-batches/" + septemberBatchId + ":approve?communityId=" + PROJECT,
                null, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("METER_ANOMALY_REVIEW_REQUIRED"));

        JsonNode preview = postOk(token, "/api/v1/meter-share-rules:preview", body(
                "communityId", PROJECT, "ruleId", SHARE_RULE, "batchId", septemberBatchId,
                "totalUsage", new BigDecimal("3")), null);
        assertThat(preview.path("ruleVersionNo").asInt()).isEqualTo(1);
        assertThat(preview.path("assumptionRule").asBoolean()).isTrue();
        assertThat(preview.path("items").size()).isEqualTo(1);
        Map<String, Object> shareRequest = body("communityId", PROJECT, "ruleId", SHARE_RULE,
                "batchId", septemberBatchId, "totalUsage", new BigDecimal("3"));
        postRequest(token, "/api/v1/meter-share-rules:apply", shareRequest, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.inserted").value(1));
        postRequest(token, "/api/v1/meter-share-rules:apply", shareRequest, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(1));

        String readingId = String.valueOf(anomaly.get("id"));
        postRequest(token, "/api/v1/meter-readings/" + readingId + ":review", body(
                "communityId", PROJECT, "reason", "现场表单与照片复核一致，允许采用手工上期数",
                "expectedVersion", 1), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.validation_status").value("REVIEWED"));
        postRequest(token, "/api/v1/meter-reading-batches/" + septemberBatchId + ":approve?communityId=" + PROJECT,
                null, null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));

        postRequest(token, "/api/v1/meter-reading-batches/" + septemberBatchId + ":generate-charges",
                body("communityId", PROJECT, "feeStandardId", METER_STANDARD), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.generated").value(1))
                .andExpect(jsonPath("$.reconciliationMatched").value(true));
        postRequest(token, "/api/v1/meter-reading-batches/" + septemberBatchId + ":generate-charges",
                body("communityId", PROJECT, "feeStandardId", METER_STANDARD), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(1));
        List<Map<String, Object>> metrics = jdbc.queryForList("""
                SELECT metric_name, difference_value, status FROM meter_charge_reconciliation
                WHERE batch_id=:batchId ORDER BY metric_name
                """, Map.of("batchId", septemberBatchId));
        assertThat(metrics).hasSize(3).allSatisfy(metric -> {
            assertThat(metric.get("status")).isEqualTo("MATCHED");
            assertThat((BigDecimal) metric.get("difference_value")).isEqualByComparingTo(BigDecimal.ZERO);
        });
        String chargeSnapshot = jdbc.queryForObject("""
                SELECT bi.calculation_snapshot FROM bill_item bi
                WHERE bi.source_type='METER_READING' AND bi.source_id=:readingId
                """, Map.of("readingId", readingId), String.class);
        JsonNode chargeJson = objectMapper.readTree(chargeSnapshot);
        assertThat(chargeJson.path("originalReadingChecksum").asText()).isNotBlank();
        assertThat(new BigDecimal(chargeJson.path("originalReadingSnapshot").path("lossRate").asText()))
                .isEqualByComparingTo("0.050000");
        assertThat(chargeJson.path("shareRuleVersionId").isMissingNode()).isTrue();
        assertThat(chargeJson.path("originalReadingSnapshot").path("shareRuleVersionNo").asInt()).isEqualTo(1);

        String currentUtcPeriod = YearMonth.now(ZoneOffset.UTC).toString();
        JsonNode iotBatch = postOk(token, "/api/v1/meter-reading-batches", body(
                "communityId", PROJECT, "batchNo", "G7-IOT-" + suffix,
                "readingPeriod", currentUtcPeriod, "sourceType", "IOT_SIMULATOR"), "g7-iot-" + suffix);
        String iotBatchId = iotBatch.path("id").asText();
        postRequest(token, "/api/v1/meter-readings:import-simulated?communityId=" + PROJECT
                + "&batchId=" + iotBatchId, List.of(METER_TWO), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.created").value(1));
        postRequest(token, "/api/v1/meter-readings:import-simulated?communityId=" + PROJECT
                + "&batchId=" + iotBatchId, List.of(METER_TWO), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(1));
        Map<String, Object> iotEvidence = jdbc.queryForMap("""
                SELECT simulated, payload_checksum, status, meter_reading_id FROM iot_reading_inbox
                WHERE batch_id=:batchId
                """, Map.of("batchId", iotBatchId));
        assertThat(iotEvidence.get("simulated")).isEqualTo(true);
        assertThat(iotEvidence.get("status")).isEqualTo("REPLAYED");
        assertThat(iotEvidence.get("payload_checksum")).isNotNull();
        assertThat(iotEvidence.get("meter_reading_id")).isNotNull();

        Map<String, Object> replacement = body("communityId", PROJECT, "newMeterNo", "G7-NEW-" + suffix,
                "oldFinalReading", new BigDecimal("20"), "newInitialReading", new BigDecimal("0.5"),
                "reason", "集成测试换表连续性");
        String replacementKey = "g7-replace-" + suffix;
        JsonNode replaced = postOk(token, "/api/v1/meters/" + METER_ONE + ":replace", replacement, replacementKey);
        assertThat(replaced.path("evidenceNo").asText()).startsWith("MR-");
        assertThat(replaced.path("snapshotChecksum").asText()).hasSize(64);
        postRequest(token, "/api/v1/meters/" + METER_ONE + ":replace", replacement, replacementKey)
                .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true));
        Map<String, Object> conflictingReplacement = new LinkedHashMap<>(replacement);
        conflictingReplacement.put("newInitialReading", new BigDecimal("1.0"));
        postRequest(token, "/api/v1/meters/" + METER_ONE + ":replace", conflictingReplacement, replacementKey)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_REQUEST_CONFLICT"));

        mockMvc.perform(get("/api/v1/meter-reading-batches/{id}", septemberBatchId)
                        .param("communityId", PROJECT).header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.batch.status").value("APPROVED"))
                .andExpect(jsonPath("$.reconciliation.length()").value(3));
    }

    private Map<String, Object> readingBody(String batchId, String meterId, BigDecimal previous,
                                            BigDecimal current, String readingAt) {
        return body("communityId", PROJECT, "batchId", batchId, "readings", List.of(body(
                "meterId", meterId, "previousReading", previous, "currentReading", current,
                "correction", null, "allocatedShare", BigDecimal.ZERO, "readingAt", readingAt)));
    }

    private String login() throws Exception {
        return postOk(null, "/api/v1/auth/login", body("username", ADMIN_USERNAME, "password", ADMIN_PASSWORD), null)
                .path("accessToken").asText();
    }

    private ResultActions postRequest(String token, String path, Object payload, String key) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON);
        if (payload != null) request.content(objectMapper.writeValueAsString(payload));
        if (token != null) request.header("Authorization", bearer(token));
        if (key != null) request.header("Idempotency-Key", key);
        return mockMvc.perform(request);
    }

    private JsonNode postOk(String token, String path, Object payload, String key) throws Exception {
        String content = postRequest(token, path, payload, key).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(content);
    }

    private Map<String, Object> body(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) result.put(String.valueOf(values[index]), values[index + 1]);
        return result;
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private String sha256(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
