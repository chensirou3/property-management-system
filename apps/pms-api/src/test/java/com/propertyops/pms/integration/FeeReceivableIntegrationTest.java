package com.propertyops.pms.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
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
class FeeReceivableIntegrationTest {
    private static final String PROJECT = "30000000-0000-0000-0000-000000000001";
    private static final String ADMIN_USERNAME = "fee-integration-admin";
    private static final String ADMIN_PASSWORD = "fee-integration-admin-password";

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("pms_fee_test")
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
        registry.add("pms.security.jwt-secret", () -> "fee-integration-only-jwt-secret-with-more-than-32-characters");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired NamedParameterJdbcTemplate jdbc;

    @Test
    void governedConfigurationAndAsynchronousReceivablesPreserveHistoryAndIdempotency() throws Exception {
        String token = login();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> definitionRequest = body(
                "communityId", PROJECT, "code", "G5-" + suffix, "name", "G5 精确舍入费用",
                "feeType", "PROPERTY", "feeClass", "PERIODIC", "unitCode", "M2_MONTH",
                "decimalScale", 2, "lateFeeEnabled", false, "temporaryAllowed", true,
                "accountingSubjectCode", "6001.01", "prepaymentSubjectCode", "2203.01",
                "taxCategoryCode", "TAX-PROPERTY", "taxRate", new BigDecimal("0.06"),
                "roundingMode", "HALF_EVEN", "currencyCode", "CNY");
        JsonNode definition = postOk(token, "/api/v1/fees/definitions", definitionRequest, null);
        String definitionId = definition.path("id").asText();

        JsonNode standard = postOk(token, "/api/v1/fees/standards", body(
                "communityId", PROJECT, "feeDefinitionId", definitionId, "code", "G5-STD-" + suffix,
                "name", "G5 面积计费标准", "assetType", "ROOM", "billingCycle", "MONTHLY",
                "calculationBasis", "BUILDING_AREA", "prorationRule", "FULL_PERIOD",
                "unitPrice", new BigDecimal("0.015"), "formulaCode", "AREA_PRICE",
                "formulaExpression", "buildingArea * unitPrice * coefficient",
                "effectiveFrom", "2026-08-01", "effectiveTo", "2026-08-31"), null);
        String standardId = standard.path("id").asText();
        String versionOneId = jdbc.queryForObject("""
                SELECT id FROM fee_standard_version WHERE fee_standard_id=:id AND version_no=1
                """, Map.of("id", standardId), String.class);

        postRequest(token, "/api/v1/fees/standards/" + standardId + "/versions", body(
                "communityId", PROJECT, "unitPrice", new BigDecimal("0.025"), "formulaCode", "AREA_PRICE",
                "formulaExpression", "overlap", "effectiveFrom", "2026-08-15", "effectiveTo", "2026-09-15"), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FEE_STANDARD_VERSION_OVERLAP"));

        JsonNode versionTwo = postOk(token, "/api/v1/fees/standards/" + standardId + "/versions", body(
                "communityId", PROJECT, "unitPrice", new BigDecimal("0.025"), "formulaCode", "AREA_PRICE",
                "formulaExpression", "buildingArea * unitPrice * coefficient",
                "effectiveFrom", "2026-09-01", "effectiveTo", null), null);
        assertThat(versionTwo.path("versionNo").asInt()).isEqualTo(2);

        Map<String, Object> asset = jdbc.queryForMap("""
                SELECT id, building_area FROM asset
                WHERE community_id=:communityId AND asset_type='ROOM' AND enabled=TRUE
                ORDER BY code LIMIT 1
                """, Map.of("communityId", PROJECT));
        String assetId = String.valueOf(asset.get("id"));
        BigDecimal area = (BigDecimal) asset.get("building_area");
        Map<String, Object> allocationRequest = body(
                "communityId", PROJECT, "feeStandardId", standardId, "targetType", "ASSET",
                "targetIds", List.of(assetId), "coefficient", new BigDecimal("1.333333"),
                "effectiveFrom", "2026-08-01", "effectiveTo", null, "sourceType", "MANUAL");
        postRequest(token, "/api/v1/fees/allocations:preview", allocationRequest, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.changed").value(1));
        JsonNode assigned = postOk(token, "/api/v1/fees/allocations:assign", allocationRequest, null);
        String allocationId = assigned.path("items").get(0).path("id").asText();
        assertThat(assigned.path("changed").asInt()).isEqualTo(1);
        postRequest(token, "/api/v1/fees/allocations:assign", allocationRequest, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(0))
                .andExpect(jsonPath("$.skipped").value(1));
        Map<String, Object> overlapAllocation = new LinkedHashMap<>(allocationRequest);
        overlapAllocation.put("coefficient", new BigDecimal("1.1"));
        overlapAllocation.put("effectiveFrom", "2026-08-15");
        postRequest(token, "/api/v1/fees/allocations:assign", overlapAllocation, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FEE_ALLOCATION_OVERLAP"));

        JsonNode augustPreview = postOk(token, "/api/v1/receivables:preview",
                body("communityId", PROJECT, "billingPeriod", "2026-08", "assetIds", List.of(assetId)), null);
        JsonNode augustLine = lineForStandard(augustPreview, standardId);
        BigDecimal expectedAugust = area.multiply(new BigDecimal("0.015")).multiply(new BigDecimal("1.333333"))
                .setScale(2, RoundingMode.HALF_EVEN);
        assertThat(number(augustLine.path("amount"))).isEqualByComparingTo(expectedAugust);
        assertThat(augustLine.path("snapshot").path("feeStandardVersionNo").asInt()).isEqualTo(1);
        assertThat(augustLine.path("snapshot").path("roundingMode").asText()).isEqualTo("HALF_EVEN");

        JsonNode septemberPreview = postOk(token, "/api/v1/receivables:preview",
                body("communityId", PROJECT, "billingPeriod", "2026-09", "assetIds", List.of(assetId)), null);
        JsonNode septemberLine = lineForStandard(septemberPreview, standardId);
        BigDecimal expectedSeptember = area.multiply(new BigDecimal("0.025")).multiply(new BigDecimal("1.333333"))
                .setScale(2, RoundingMode.HALF_EVEN);
        assertThat(number(septemberLine.path("amount"))).isEqualByComparingTo(expectedSeptember);
        assertThat(septemberLine.path("snapshot").path("feeStandardVersionNo").asInt()).isEqualTo(2);

        Map<String, Object> periodicRequest = body(
                "communityId", PROJECT, "billingPeriod", "2026-08", "assetIds", List.of(assetId));
        String periodicKey = "g5-periodic-" + suffix;
        JsonNode receipt = postOk(token, "/api/v1/receivable-jobs", periodicRequest, periodicKey);
        String jobId = receipt.path("jobId").asText();
        JsonNode completed = awaitJob(token, jobId);
        assertThat(completed.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(completed.path("reconciliation").size()).isEqualTo(3);
        completed.path("reconciliation").forEach(metric -> {
            assertThat(metric.path("status").asText()).isEqualTo("MATCHED");
            assertThat(number(metric.path("differenceValue"))).isEqualByComparingTo(BigDecimal.ZERO);
        });

        postRequest(token, "/api/v1/receivable-jobs", periodicRequest, periodicKey)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(jobId))
                .andExpect(jsonPath("$.replayed").value(true));
        postRequest(token, "/api/v1/receivable-jobs", body(
                        "communityId", PROJECT, "billingPeriod", "2026-09", "assetIds", List.of(assetId)), periodicKey)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_REQUEST_CONFLICT"));

        String storedSnapshot = jdbc.queryForObject("""
                SELECT calculation_snapshot FROM bill_item
                WHERE fee_allocation_id=:allocationId AND fee_standard_version_id=:versionId
                """, Map.of("allocationId", allocationId, "versionId", versionOneId), String.class);
        assertThat(objectMapper.readTree(storedSnapshot).path("roundingMode").asText()).isEqualTo("HALF_EVEN");
        assertThat(number(objectMapper.readTree(storedSnapshot).path("unitPrice")))
                .isEqualByComparingTo(new BigDecimal("0.015"));

        Map<String, Object> updatedDefinition = new LinkedHashMap<>(definitionRequest);
        updatedDefinition.remove("communityId");
        updatedDefinition.remove("code");
        updatedDefinition.put("name", "G5 精确舍入费用（已调整）");
        updatedDefinition.put("roundingMode", "DOWN");
        updatedDefinition.put("enabled", true);
        updatedDefinition.put("expectedVersion", 0);
        putRequest(token, "/api/v1/fees/definitions/" + definitionId + "?communityId=" + PROJECT, updatedDefinition)
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        String snapshotAfterChange = jdbc.queryForObject("""
                SELECT calculation_snapshot FROM bill_item
                WHERE fee_allocation_id=:allocationId AND fee_standard_version_id=:versionId
                """, Map.of("allocationId", allocationId, "versionId", versionOneId), String.class);
        assertThat(snapshotAfterChange).isEqualTo(storedSnapshot);

        postRequest(token, "/api/v1/fees/allocations:cancel", body(
                "communityId", PROJECT, "allocationIds", List.of(allocationId),
                "effectiveTo", "2026-09-30", "reason", "集成测试截止"), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.changed").value(1));
        JsonNode octoberPreview = postOk(token, "/api/v1/receivables:preview",
                body("communityId", PROJECT, "billingPeriod", "2026-10", "assetIds", List.of(assetId)), null);
        assertThat(hasLineForStandard(octoberPreview, standardId)).isFalse();

        Map<String, Object> temporaryRequest = body(
                "communityId", PROJECT, "assetId", assetId, "chargeDate", "2026-10-15",
                "dueDate", "2026-10-31", "lines", List.of(body(
                        "feeDefinitionId", definitionId, "itemName", "门禁卡补办费",
                        "quantity", new BigDecimal("3"), "unitPrice", new BigDecimal("12.345"),
                        "coefficient", BigDecimal.ONE)));
        postRequest(token, "/api/v1/temporary-receivables:preview", temporaryRequest, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.lineCount").value(1));
        JsonNode temporaryReceipt = postOk(token, "/api/v1/temporary-receivable-jobs",
                temporaryRequest, "g5-temporary-" + suffix);
        JsonNode temporaryCompleted = awaitJob(token, temporaryReceipt.path("jobId").asText());
        assertThat(temporaryCompleted.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(temporaryCompleted.path("jobType").asText()).isEqualTo("TEMPORARY");
        assertThat(temporaryCompleted.path("reconciliation").size()).isEqualTo(3);
    }

    private JsonNode awaitJob(String token, String jobId) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        JsonNode result;
        do {
            String response = mockMvc.perform(get("/api/v1/receivable-jobs/{id}", jobId)
                            .param("communityId", PROJECT).header("Authorization", bearer(token)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            result = objectMapper.readTree(response);
            if (List.of("COMPLETED", "PARTIAL", "FAILED").contains(result.path("status").asText())) return result;
            Thread.sleep(50);
        } while (Instant.now().isBefore(deadline));
        throw new AssertionError("应收任务未在期限内完成：" + result);
    }

    private JsonNode lineForStandard(JsonNode preview, String standardId) {
        for (JsonNode item : preview.path("items")) {
            if (standardId.equals(item.path("snapshot").path("feeStandardId").asText())) return item;
        }
        throw new AssertionError("预览中未找到费用标准 " + standardId + "：" + preview);
    }

    private boolean hasLineForStandard(JsonNode preview, String standardId) {
        for (JsonNode item : preview.path("items")) {
            if (standardId.equals(item.path("snapshot").path("feeStandardId").asText())) return true;
        }
        return false;
    }

    private BigDecimal number(JsonNode value) {
        return new BigDecimal(value.asText());
    }

    private String login() throws Exception {
        JsonNode response = postOk(null, "/api/v1/auth/login",
                body("username", ADMIN_USERNAME, "password", ADMIN_PASSWORD), null);
        return response.path("accessToken").asText();
    }

    private ResultActions postRequest(String token, String path, Object body, String idempotencyKey) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
        if (token != null) request.header("Authorization", bearer(token));
        if (idempotencyKey != null) request.header("Idempotency-Key", idempotencyKey);
        return mockMvc.perform(request);
    }

    private JsonNode postOk(String token, String path, Object body, String idempotencyKey) throws Exception {
        String response = postRequest(token, path, body, idempotencyKey).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private ResultActions putRequest(String token, String path, Object body) throws Exception {
        return mockMvc.perform(put(path).header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private Map<String, Object> body(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) result.put((String) values[index], values[index + 1]);
        return result;
    }
}
