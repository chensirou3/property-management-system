package com.propertyops.pms.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.propertyops.pms.report.ReportQueryEngine;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ReportGovernanceIntegrationTest {
    private static final String PROJECT = "30000000-0000-0000-0000-000000000001";
    private static final String ADMIN_USERNAME = "report-integration-admin";
    private static final String ADMIN_PASSWORD = "report-integration-admin-password";

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("pms_report_test").withUsername("pms_test").withPassword("pms_test_password");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("pms.bootstrap.admin-username", () -> ADMIN_USERNAME);
        registry.add("pms.bootstrap.admin-password", () -> ADMIN_PASSWORD);
        registry.add("pms.security.jwt-secret", () -> "report-integration-only-jwt-secret-with-more-than-32-characters");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired NamedParameterJdbcTemplate jdbc;

    @Test
    void governsAllReportsArtifactsReceiptPrintingNotificationsAndPerformance() throws Exception {
        String token = login();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String reportPermissionId = jdbc.queryForObject("SELECT id FROM sys_permission WHERE code='report:read'",
                Map.of(), String.class);
        JsonNode scopedRole = postOk(token, "/api/v1/iam/roles", body("enterpriseId", null,
                "code", "G8_REPORT_" + suffix, "name", "G8 报表隔离角色", "description", "仅用于集成验收",
                "permissionIds", List.of(reportPermissionId)), null);
        String scopedUsername = "g8-scoped-" + suffix;
        String scopedPassword = "ReportScoped-Password9!";
        JsonNode scopedUser = postOk(token, "/api/v1/iam/users", body("username", scopedUsername, "password", scopedPassword,
                "displayName", "G8 隔离验收用户", "employeeId", null, "enabled", true,
                "roleIds", List.of(scopedRole.path("id").asText()), "projectIds", List.of(PROJECT)), null);
        jdbc.update("UPDATE sys_user SET password_change_required=FALSE WHERE id=:id",
                Map.of("id", scopedUser.path("id").asText()));
        String scopedToken = login(scopedUsername, scopedPassword);
        JsonNode catalog = getJson(token, "/api/v1/reports/catalog?communityId=" + PROJECT);
        assertThat(catalog.size()).isEqualTo(22);
        assertThat(catalog.findValuesAsText("report_code")).containsExactlyInAnyOrderElementsOf(ReportQueryEngine.CODES);

        String billId = jdbc.queryForObject("""
                SELECT id FROM bill WHERE community_id=:communityId AND outstanding_amount>=2
                AND customer_id IS NOT NULL ORDER BY bill_no LIMIT 1
                """, Map.of("communityId", PROJECT), String.class);
        JsonNode shift = postOk(token, "/api/v1/cashier/shifts",
                body("communityId", PROJECT, "openingCash", BigDecimal.ZERO), "g8-shift-" + suffix);
        JsonNode order = postOk(token, "/api/v1/payment-orders",
                body("communityId", PROJECT, "paymentMethod", "CASH",
                        "bills", List.of(body("billId", billId, "amount", new BigDecimal("1.00")))),
                "g8-payment-" + suffix);
        postOk(token, "/api/v1/payment-orders/" + order.path("orderId").asText()
                + ":confirm-simulated?communityId=" + PROJECT, body(), null);
        String receiptId = jdbc.queryForObject("SELECT id FROM receipt WHERE payment_order_id=:id",
                Map.of("id", order.path("orderId").asText()), String.class);

        for (String code : ReportQueryEngine.CODES) {
            JsonNode report = getJson(token, "/api/v1/reports/" + code + "?communityId=" + PROJECT
                    + "&from=2020-01-01&to=2030-12-31&periodFrom=2020-01&periodTo=2030-12&page=1&size=50");
            assertThat(report.path("reportCode").asText()).isEqualTo(code);
            assertThat(report.path("formulaNote").asText()).isNotBlank();
            assertThat(report.path("fixedSample").isObject()).isTrue();
            assertThat(report.path("queryChecksum").asText()).hasSize(64);
            assertThat(report.path("summary").path("rowCount").asInt()).isEqualTo(report.path("total").asInt());
            assertThat(report.path("durationMs").asLong()).isLessThan(2_000);
            if ("RECEIPT_BATCH_PRINT".equals(code)) {
                assertThat(report.path("rows").get(0).path("receiptId").asText()).isNotBlank();
            }
            if (List.of("BILL_NOTIFICATIONS", "REMINDERS", "INVOICE_STATISTICS", "BANK_TRUST").contains(code)) {
                assertThat(report.path("productionConnected").asBoolean()).isFalse();
                assertThat(report.path("integrationMode").asText()).endsWith("SIMULATOR");
            }
        }
        getRequest(token, "/api/v1/reports/ARREARS")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[0].field").value("communityId"));
        getRequest(token, "/api/v1/reports/COMPREHENSIVE_QUERY?communityId=" + PROJECT + "&columns=businessNo,forbidden")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("REPORT_COLUMN_NOT_ALLOWED"));
        JsonNode arrears = getJson(token, "/api/v1/reports/ARREARS?communityId=" + PROJECT + "&size=10");
        if (arrears.path("rows").size() > 0) assertThat(arrears.path("rows").get(0).path("customerName").asText()).contains("*");

        JsonNode dashboard = getJson(token, "/api/v1/dashboard?communityId=" + PROJECT);
        JsonNode rate = getJson(token, "/api/v1/reports/COLLECTION_RATE?communityId=" + PROJECT);
        assertThat(dashboard.path("finance").path("metricSource").asText()).isEqualTo("COLLECTION_RATE");
        assertThat(dashboard.path("finance").path("receivable").decimalValue())
                .isEqualByComparingTo(rate.path("rows").get(0).path("receivableAmount").decimalValue());

        List<String> formats = List.of("CSV", "XLSX", "PDF", "PRINT");
        for (String format : formats) {
            String key = "g8-export-" + format.toLowerCase() + "-" + suffix;
            Map<String, Object> request = body("communityId", PROJECT, "reportCode", "ARREARS", "format", format,
                    "filters", Map.of("periodFrom", "2020-01", "periodTo", "2030-12"), "selectedColumns", List.of());
            JsonNode created = postOk(token, "/api/v1/report-jobs", request, key);
            postRequest(token, "/api/v1/report-jobs", request, key).andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(created.path("id").asText()));
            await("report_export_job", created.path("id").asText());
            Map<String, Object> artifact = jdbc.queryForMap("SELECT status,artifact_blob,artifact_checksum FROM report_export_job WHERE id=:id",
                    Map.of("id", created.path("id").asText()));
            assertThat(artifact.get("status")).isEqualTo("SUCCEEDED");
            assertThat(String.valueOf(artifact.get("artifact_checksum"))).hasSize(64);
            byte[] bytes = (byte[]) artifact.get("artifact_blob");
            if ("CSV".equals(format)) assertThat(bytes).startsWith(new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf});
            if ("XLSX".equals(format)) assertThat(bytes).startsWith(new byte[] {'P', 'K'});
            if ("PDF".equals(format)) assertThat(bytes).startsWith("%PDF".getBytes(StandardCharsets.US_ASCII));
            if ("PRINT".equals(format)) assertThat(new String(bytes, StandardCharsets.UTF_8)).startsWith("<!doctype html>");
        }
        postRequest(token, "/api/v1/report-jobs", body("communityId", PROJECT, "reportCode", "BILLS", "format", "CSV",
                "filters", Map.of(), "selectedColumns", List.of()), "g8-export-csv-" + suffix)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REPORT_CONFLICT"));

        JsonNode reportOnlyJob = postOk(token, "/api/v1/report-jobs",
                body("communityId", PROJECT, "reportCode", "COLLECTION_RATE", "format", "CSV",
                        "filters", Map.of(), "selectedColumns", List.of()), "g8-report-only-" + suffix);
        await("report_export_job", reportOnlyJob.path("id").asText());
        getRequest(scopedToken, "/api/v1/reports/COLLECTION_RATE?communityId=" + PROJECT)
                .andExpect(status().isOk());
        getRequest(scopedToken, "/api/v1/reports/ARREARS?communityId=" + PROJECT)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        JsonNode scopedJobs = getJson(scopedToken, "/api/v1/report-jobs?communityId=" + PROJECT);
        assertThat(scopedJobs.findValuesAsText("report_code")).contains("COLLECTION_RATE")
                .doesNotContain("ARREARS");
        getRequest(scopedToken, "/api/v1/notification-batches?communityId=" + PROJECT)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));

        int beforePrint = jdbc.queryForObject("SELECT print_count FROM receipt WHERE id=:id", Map.of("id", receiptId), Integer.class);
        Map<String, Object> printRequest = body("communityId", PROJECT, "receiptIds", List.of(receiptId), "format", "PDF");
        JsonNode printJob = postOk(token, "/api/v1/receipt-print-jobs", printRequest, "g8-print-" + suffix);
        await("receipt_print_job", printJob.path("id").asText());
        assertThat(jdbc.queryForObject("SELECT print_count FROM receipt WHERE id=:id", Map.of("id", receiptId), Integer.class))
                .isEqualTo(beforePrint + 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM receipt_print_item WHERE job_id=:id AND LENGTH(snapshot_checksum)=64",
                Map.of("id", printJob.path("id").asText()), Integer.class)).isEqualTo(1);
        postRequest(token, "/api/v1/receipt-print-jobs", printRequest, "g8-print-" + suffix)
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(printJob.path("id").asText()));

        String notifyBillId = jdbc.queryForObject("""
                SELECT id FROM bill WHERE community_id=:communityId AND billing_period='2026-07' AND outstanding_amount>0 LIMIT 1
                """, Map.of("communityId", PROJECT), String.class);
        Map<String, Object> notification = body("communityId", PROJECT, "billingPeriod", "2026-07",
                "channel", "SMS_SIMULATOR", "billIds", List.of(notifyBillId),
                "contentTemplate", "账单 {billNo} 资产 {asset} 待缴 {amount}");
        JsonNode batch = postOk(token, "/api/v1/notification-batches", notification, "g8-notify-" + suffix);
        assertThat(batch.path("simulated").asBoolean()).isTrue();
        assertThat(batch.path("messages").get(0).path("content_checksum").asText()).hasSize(64);
        assertThat(batch.path("messages").get(0).path("masked_recipient").asText()).doesNotContain("1380000");
        postRequest(token, "/api/v1/notification-batches", notification, "g8-notify-" + suffix)
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(batch.path("id").asText()));
        getRequest(scopedToken, "/api/v1/reports/COLLECTION_RATE?communityId=30000000-0000-0000-0000-000000000002")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PROJECT_ACCESS_DENIED"));

        insertPerformanceAssets(9_400);
        long started = System.nanoTime();
        JsonNode assets = getJson(token, "/api/v1/data/assets?communityId=" + PROJECT + "&page=50&size=200&sort=code");
        long durationMs = (System.nanoTime() - started) / 1_000_000L;
        assertThat(assets.path("total").asLong()).isGreaterThanOrEqualTo(10_000);
        assertThat(durationMs).isLessThan(2_000);
        postRequest(token, "/api/v1/cashier/shifts/" + shift.path("id").asText() + ":close",
                body("communityId", PROJECT, "actualCash", new BigDecimal("1.00"), "expectedVersion", 0), null)
                .andExpect(status().isOk());
    }

    private void insertPerformanceAssets(int count) {
        List<Map<String, Object>> batch = new ArrayList<>(count);
        LocalDateTime now = LocalDateTime.now();
        for (int index = 0; index < count; index++) batch.add(Map.of(
                "id", UUID.randomUUID().toString(), "communityId", PROJECT, "code", String.format("PERF-%05d", index),
                "name", "性能基线资产" + index, "now", now));
        jdbc.batchUpdate("""
                INSERT INTO asset
                    (id,community_id,asset_type,code,display_name,building_area,usable_area,occupancy_status,
                     operation_status,enabled,version,created_at,updated_at)
                VALUES (:id,:communityId,'PUBLIC_AREA',:code,:name,1,1,'VACANT','ACTIVE',TRUE,0,:now,:now)
                """, batch.toArray(Map[]::new));
    }

    private void await(String table, String id) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            String status = jdbc.queryForObject("SELECT status FROM " + table + " WHERE id=:id", Map.of("id", id), String.class);
            if (List.of("SUCCEEDED", "FAILED").contains(status)) {
                assertThat(status).isEqualTo("SUCCEEDED"); return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for " + table);
    }

    private String login() throws Exception {
        return login(ADMIN_USERNAME, ADMIN_PASSWORD);
    }

    private String login(String username, String password) throws Exception {
        return postOk(null, "/api/v1/auth/login", body("username", username, "password", password), null)
                .path("accessToken").asText();
    }

    private JsonNode getJson(String token, String path) throws Exception {
        return objectMapper.readTree(getRequest(token, path).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private org.springframework.test.web.servlet.ResultActions getRequest(String token, String path) throws Exception {
        return mockMvc.perform(get(path).header("Authorization", "Bearer " + token));
    }

    private org.springframework.test.web.servlet.ResultActions postRequest(String token, String path, Object value, String key) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(value));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (key != null) request.header("Idempotency-Key", key);
        return mockMvc.perform(request);
    }

    private JsonNode postOk(String token, String path, Object value, String key) throws Exception {
        return objectMapper.readTree(postRequest(token, path, value, key).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private Map<String, Object> body(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) result.put((String) values[index], values[index + 1]);
        return result;
    }
}
