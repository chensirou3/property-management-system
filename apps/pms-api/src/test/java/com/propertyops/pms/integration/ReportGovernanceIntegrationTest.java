package com.propertyops.pms.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
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
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
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
    private static final String ADMIN_PASSWORD = "ReportFixture-2026!Secure";
    private static final Map<String, List<String>> EXPECTED_REPORT_FILTERS = Map.ofEntries(
            Map.entry("TRANSACTION_SUMMARY", List.of("dateFrom", "dateTo", "paymentChannel", "cashierId")),
            Map.entry("TRANSACTION_DETAILS", List.of("dateFrom", "dateTo", "transactionNo", "paymentChannel", "status")),
            Map.entry("RECEIPT_BATCH_PRINT", List.of("dateFrom", "dateTo", "receiptStatus", "cashierId", "keyword")),
            Map.entry("PAYMENTS", List.of("dateFrom", "dateTo", "keyword", "status")),
            Map.entry("ARREARS", List.of("billingPeriodFrom", "billingPeriodTo", "feeDefinitionId", "keyword")),
            Map.entry("BILL_NOTIFICATIONS", List.of("billingPeriodFrom", "billingPeriodTo", "channel", "deliveryStatus")),
            Map.entry("BILLS", List.of("billingPeriodFrom", "billingPeriodTo", "keyword", "status")),
            Map.entry("COLLECTION_RATE", List.of("billingPeriodFrom", "billingPeriodTo", "feeDefinitionId")),
            Map.entry("ARREARS_CLEARANCE_RATE", List.of("dateFrom", "dateTo", "arrearsPeriodFrom", "arrearsPeriodTo")),
            Map.entry("COMPREHENSIVE_QUERY", List.of("subjectType", "dateFrom", "dateTo", "keyword")),
            Map.entry("COLLECTION_CLEARANCE_SUMMARY", List.of("dateFrom", "dateTo", "feeDefinitionId")),
            Map.entry("CHARGE_DETAILS", List.of("dateFrom", "dateTo", "feeDefinitionId", "paymentChannel")),
            Map.entry("DISCOUNT_DETAILS", List.of("dateFrom", "dateTo", "discountType", "keyword")),
            Map.entry("PREPAYMENTS", List.of("dateFrom", "dateTo", "keyword", "entryType")),
            Map.entry("OWNERSHIP_TRANSFERS", List.of("dateFrom", "dateTo", "keyword")),
            Map.entry("REMINDERS", List.of("dateFrom", "dateTo", "reminderType", "deliveryStatus", "keyword")),
            Map.entry("FEE_STATUS", List.of("billingPeriodFrom", "billingPeriodTo", "feeDefinitionId")),
            Map.entry("INVOICE_STATISTICS", List.of("dateFrom", "dateTo", "invoiceType", "invoiceStatus")),
            Map.entry("DEPOSITS", List.of("dateFrom", "dateTo", "keyword", "status")),
            Map.entry("DAILY_SETTLEMENT_DETAILS", List.of("settlementDate", "dateFrom", "dateTo", "cashierId", "paymentChannel")),
            Map.entry("ADJUSTMENTS", List.of("dateFrom", "dateTo", "adjustmentType", "status", "keyword")),
            Map.entry("BANK_TRUST", List.of()));

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
    void discountDetailsUsesOnlyAppliedAdjustmentsAndEffectiveDateBoundaries() throws Exception {
        String token = login();
        String marker = "DISC-BOUNDARY-" + UUID.randomUUID().toString().substring(0, 8);
        String billId = jdbc.queryForObject("""
                SELECT id FROM bill WHERE community_id=:communityId ORDER BY id LIMIT 1
                """, Map.of("communityId", PROJECT), String.class);
        String userId = jdbc.queryForObject("SELECT id FROM sys_user WHERE username=:username",
                Map.of("username", ADMIN_USERNAME), String.class);

        String appliedAtStart = marker + "-APPLIED-START";
        String appliedAtEnd = marker + "-APPLIED-END";
        String appliedAfterRange = marker + "-APPLIED-AFTER";
        String pending = marker + "-PENDING";
        String rejected = marker + "-REJECTED";
        LocalDateTime rangeStart = LocalDateTime.of(2040, 5, 10, 0, 0);
        LocalDateTime rangeEnd = LocalDateTime.of(2040, 5, 10, 23, 59, 59, 999_000_000);

        // Creation times deliberately contradict effective-time order and range membership.
        insertDiscountAdjustmentFixture(billId, userId, appliedAtStart, "APPLIED",
                LocalDateTime.of(2040, 12, 1, 0, 0), rangeStart);
        insertDiscountAdjustmentFixture(billId, userId, appliedAtEnd, "APPLIED",
                LocalDateTime.of(2040, 1, 1, 0, 0), rangeEnd);
        insertDiscountAdjustmentFixture(billId, userId, appliedAfterRange, "APPLIED",
                LocalDateTime.of(2040, 5, 10, 12, 0), LocalDateTime.of(2040, 5, 11, 0, 0));
        insertDiscountAdjustmentFixture(billId, userId, pending, "PENDING",
                LocalDateTime.of(2040, 5, 10, 13, 0), null);
        insertDiscountAdjustmentFixture(billId, userId, rejected, "REJECTED",
                LocalDateTime.of(2040, 5, 10, 14, 0), null);

        JsonNode report = getJson(token, "/api/v1/reports/DISCOUNT_DETAILS?communityId=" + PROJECT
                + "&dateFrom=2040-05-10&dateTo=2040-05-10&keyword=" + marker);
        assertThat(report.path("rows").findValuesAsText("discountNo"))
                .containsExactly(appliedAtEnd, appliedAtStart)
                .doesNotContain(appliedAfterRange, pending, rejected);
        assertThat(report.path("rows").get(0).path("approvedAt").asText())
                .startsWith("2040-05-10T23:59:59.999");
        assertThat(report.path("rows").get(1).path("approvedAt").asText())
                .startsWith("2040-05-10T00:00:00");
        assertThat(report.path("appliedFilters").path("dateFrom").asText()).isEqualTo("2040-05-10");
        assertThat(report.path("appliedFilters").path("dateTo").asText()).isEqualTo("2040-05-10");
    }

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
        String financePermissionId = jdbc.queryForObject("SELECT id FROM sys_permission WHERE code='finance:read'",
                Map.of(), String.class);
        JsonNode financeRole = postOk(token, "/api/v1/iam/roles", body("enterpriseId", null,
                "code", "G8_FINANCE_" + suffix, "name", "G8 财务筛选角色", "description", "仅用于筛选选项权限验收",
                "permissionIds", List.of(financePermissionId)), null);
        String financeUsername = "g8-finance-" + suffix;
        String financePassword = "FinanceScoped-Password9!";
        JsonNode financeUser = postOk(token, "/api/v1/iam/users", body("username", financeUsername, "password", financePassword,
                "displayName", "G8 财务筛选用户", "employeeId", null, "enabled", true,
                "roleIds", List.of(financeRole.path("id").asText()), "projectIds", List.of(PROJECT)), null);
        jdbc.update("UPDATE sys_user SET password_change_required=FALSE WHERE id=:id",
                Map.of("id", financeUser.path("id").asText()));
        String financeToken = login(financeUsername, financePassword);
        JsonNode externalUser = postOk(token, "/api/v1/iam/users", body("username", "g8-external-" + suffix,
                "password", "ExternalScoped-Password9!", "displayName", "G8 异项目收银员", "employeeId", null,
                "enabled", true, "roleIds", List.of(scopedRole.path("id").asText()),
                "projectIds", List.of("30000000-0000-0000-0000-000000000002")), null);
        JsonNode catalog = getJson(token, "/api/v1/reports/catalog?communityId=" + PROJECT);
        assertThat(catalog.size()).isEqualTo(22);
        assertThat(catalog.findValuesAsText("report_code")).containsExactlyInAnyOrderElementsOf(ReportQueryEngine.CODES);
        assertThat(EXPECTED_REPORT_FILTERS.keySet()).containsExactlyInAnyOrderElementsOf(ReportQueryEngine.CODES);
        for (JsonNode catalogItem : catalog) {
            String reportCode = catalogItem.path("report_code").asText();
            List<String> actualFilters = new ArrayList<>();
            catalogItem.path("allowed_filters").forEach(filter -> actualFilters.add(filter.asText()));
            assertThat(actualFilters)
                    .as("catalog allowed_filters contract for %s", reportCode)
                    .containsExactlyElementsOf(EXPECTED_REPORT_FILTERS.get(reportCode));
        }
        JsonNode openApi = getJson(token, "/v3/api-docs");
        JsonNode reportParameters = openApi.path("paths").path("/api/v1/reports/{code}").path("get").path("parameters");
        List<String> parameterNames = new ArrayList<>();
        reportParameters.forEach(parameter -> parameterNames.add(parameter.path("name").asText()));
        List<String> canonicalFilterParameters = List.of(
                "dateFrom", "dateTo", "paymentChannel", "cashierId", "transactionNo", "status",
                "receiptStatus", "keyword", "billingPeriodFrom", "billingPeriodTo", "feeDefinitionId",
                "channel", "deliveryStatus", "arrearsPeriodFrom", "arrearsPeriodTo", "subjectType",
                "discountType", "entryType", "reminderType", "invoiceType", "invoiceStatus",
                "settlementDate", "adjustmentType");
        assertThat(parameterNames).containsAll(canonicalFilterParameters).doesNotContain("all");
        for (String name : canonicalFilterParameters) {
            assertThat(parameterWith(reportParameters, name).path("required").asBoolean(false)).isFalse();
        }
        JsonNode optionOperation = openApi.path("paths").path("/api/v1/reports/filter-options").path("get");
        assertThat(optionOperation.path("parameters").findValuesAsText("name"))
                .contains("communityId", "reportCode");
        assertThat(optionOperation.path("responses").path("200").path("content")
                .path("application/json").path("schema").path("$ref").asText())
                .isEqualTo("#/components/schemas/ReportFilterOptions");
        JsonNode optionSchema = openApi.path("components").path("schemas").path("ReportFilterOptions");
        assertThat(optionSchema.path("properties").path("feeDefinitionId").path("type").asText()).isEqualTo("array");
        assertThat(optionSchema.path("properties").path("cashierId").path("type").asText()).isEqualTo("array");

        JsonNode beyondLastPage = getJson(token, "/api/v1/reports/COLLECTION_RATE?communityId=" + PROJECT
                + "&page=2147483647&size=500");
        assertThat(beyondLastPage.path("page").asInt()).isEqualTo(Integer.MAX_VALUE);
        assertThat(beyondLastPage.path("rows")).isEmpty();

        String billId = jdbc.queryForObject("""
                SELECT id FROM bill WHERE community_id=:communityId AND outstanding_amount>=2
                AND customer_id IS NOT NULL ORDER BY bill_no LIMIT 1
                """, Map.of("communityId", PROJECT), String.class);
        JsonNode shift = postOk(token, "/api/v1/cashier/shifts",
                body("communityId", PROJECT, "openingCash", BigDecimal.ZERO), "g8-shift-" + suffix);
        String externalCashierId = externalUser.path("id").asText();
        insertExternalCashierShift(externalCashierId, suffix);
        String externalFeeDefinitionId = insertExternalFeeDefinition(suffix);
        String inactiveFeeDefinitionId = insertInactiveFeeDefinition(suffix);
        String historicalCashierName = "G8 历史同名收银员";
        String disabledCashierUsername = "g8-disabled-cashier-" + suffix;
        JsonNode disabledCashier = postOk(token, "/api/v1/iam/users", body(
                "username", disabledCashierUsername, "password", "DisabledCashier-Password9!",
                "displayName", historicalCashierName, "employeeId", null, "enabled", true,
                "roleIds", List.of(financeRole.path("id").asText()), "projectIds", List.of(PROJECT)), null);
        String revokedCashierUsername = "g8-revoked-cashier-" + suffix;
        JsonNode revokedCashier = postOk(token, "/api/v1/iam/users", body(
                "username", revokedCashierUsername, "password", "RevokedCashier-Password9!",
                "displayName", historicalCashierName, "employeeId", null, "enabled", true,
                "roleIds", List.of(financeRole.path("id").asText()), "projectIds", List.of(PROJECT)), null);
        insertProjectCashierShift(disabledCashier.path("id").asText(), "DISABLED", suffix);
        insertProjectCashierShift(revokedCashier.path("id").asText(), "REVOKED", suffix);
        jdbc.update("UPDATE sys_user SET enabled=FALSE WHERE id=:id",
                Map.of("id", disabledCashier.path("id").asText()));
        jdbc.update("DELETE FROM sys_user_project_scope WHERE user_id=:id AND community_id=:communityId",
                Map.of("id", revokedCashier.path("id").asText(), "communityId", PROJECT));
        JsonNode feeOptions = getJson(token, "/api/v1/reports/filter-options?communityId=" + PROJECT
                + "&reportCode=COLLECTION_RATE");
        assertThat(feeOptions.path("feeDefinitionId")).isNotEmpty();
        assertThat(feeOptions.path("cashierId")).isEmpty();
        for (JsonNode option : feeOptions.path("feeDefinitionId")) {
            assertThat(option.path("label").asText()).isNotBlank();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fee_definition WHERE id=:id AND community_id=:communityId",
                    Map.of("id", option.path("value").asText(), "communityId", PROJECT), Integer.class)).isEqualTo(1);
        }
        JsonNode inactiveFeeOption = optionWith(feeOptions.path("feeDefinitionId"), inactiveFeeDefinitionId);
        assertThat(inactiveFeeOption.path("label").asText()).contains("已停用");
        getRequest(token, "/api/v1/reports/COLLECTION_RATE?communityId=" + PROJECT
                + "&feeDefinitionId=" + inactiveFeeDefinitionId).andExpect(status().isOk());
        assertThat(feeOptions.path("feeDefinitionId").findValuesAsText("value")).doesNotContain(externalFeeDefinitionId);
        JsonNode cashierOptions = getJson(token, "/api/v1/reports/filter-options?communityId=" + PROJECT
                + "&reportCode=TRANSACTION_SUMMARY");
        assertThat(cashierOptions.path("feeDefinitionId")).isEmpty();
        assertThat(cashierOptions.path("cashierId")).isNotEmpty();
        for (JsonNode option : cashierOptions.path("cashierId")) {
            assertThat(option.path("label").asText()).isNotBlank();
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM cashier_shift
                    WHERE cashier_user_id=:id AND community_id=:communityId
                    """, Map.of("id", option.path("value").asText(), "communityId", PROJECT), Integer.class)).isPositive();
        }
        JsonNode disabledCashierOption = optionWith(cashierOptions.path("cashierId"), disabledCashier.path("id").asText());
        JsonNode revokedCashierOption = optionWith(cashierOptions.path("cashierId"), revokedCashier.path("id").asText());
        assertThat(disabledCashierOption.path("label").asText())
                .startsWith(historicalCashierName)
                .doesNotContain(disabledCashierUsername, revokedCashierUsername);
        assertThat(revokedCashierOption.path("label").asText())
                .startsWith(historicalCashierName)
                .doesNotContain(disabledCashierUsername, revokedCashierUsername)
                .isNotEqualTo(disabledCashierOption.path("label").asText());
        assertThat(cashierOptions.toString()).doesNotContain(disabledCashierUsername, revokedCashierUsername);
        assertThat(jdbc.queryForObject("SELECT enabled FROM sys_user WHERE id=:id",
                Map.of("id", disabledCashier.path("id").asText()), Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_user_project_scope
                WHERE user_id=:id AND community_id=:communityId
                """, Map.of("id", revokedCashier.path("id").asText(), "communityId", PROJECT), Integer.class)).isZero();
        getRequest(token, "/api/v1/reports/TRANSACTION_SUMMARY?communityId=" + PROJECT
                + "&cashierId=" + disabledCashier.path("id").asText()).andExpect(status().isOk());
        getRequest(token, "/api/v1/reports/TRANSACTION_SUMMARY?communityId=" + PROJECT
                + "&cashierId=" + revokedCashier.path("id").asText()).andExpect(status().isOk());
        assertThat(cashierOptions.path("cashierId").findValuesAsText("value")).doesNotContain(externalCashierId);
        JsonNode order = postOk(token, "/api/v1/payment-orders",
                body("communityId", PROJECT, "paymentMethod", "CASH",
                        "bills", List.of(body("billId", billId, "amount", new BigDecimal("1.00")))),
                "g8-payment-" + suffix);
        postOk(token, "/api/v1/payment-orders/" + order.path("orderId").asText()
                + ":confirm-simulated?communityId=" + PROJECT, body(), null);
        String receiptId = jdbc.queryForObject("SELECT id FROM receipt WHERE payment_order_id=:id",
                Map.of("id", order.path("orderId").asText()), String.class);

        String transactionNo = jdbc.queryForObject("SELECT transaction_no FROM payment_transaction WHERE payment_order_id=:id AND transaction_type='PAYMENT'",
                Map.of("id", order.path("orderId").asText()), String.class);
        JsonNode filteredTransaction = getJson(token, "/api/v1/reports/TRANSACTION_DETAILS?communityId=" + PROJECT
                + "&transactionNo=" + transactionNo + "&paymentChannel=CASH&status=SUCCESS");
        assertThat(filteredTransaction.path("total").asInt()).isGreaterThan(0);
        assertThat(filteredTransaction.path("rows").get(0).path("transactionNo").asText()).isEqualTo(transactionNo);
        assertThat(filteredTransaction.path("appliedFilters").path("paymentChannel").asText()).isEqualTo("CASH");
        getRequest(token, "/api/v1/reports/COLLECTION_RATE?communityId=" + PROJECT + "&ignoredFilter=value")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REPORT_FILTER_NOT_ALLOWED"));
        getRequest(token, "/api/v1/reports/COLLECTION_RATE?communityId=" + PROJECT + "&ignoredFilter=")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REPORT_FILTER_NOT_ALLOWED"));
        getRequest(token, "/api/v1/reports/ARREARS?communityId=" + PROJECT + "&billingPeriodFrom=2026-13")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REPORT_PERIOD_INVALID"));
        getRequest(token, "/api/v1/reports/BILLS?communityId=" + PROJECT + "&status=UNKNOWN")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REPORT_FILTER_VALUE_INVALID"));

        String transactionId = jdbc.queryForObject("""
                SELECT id FROM payment_transaction WHERE payment_order_id=:id AND transaction_type='PAYMENT'
                """, Map.of("id", order.path("orderId").asText()), String.class);
        Map<String, Object> originalBillDates = jdbc.queryForMap("""
                SELECT billing_period,due_date,created_at FROM bill WHERE id=:id
                """, Map.of("id", billId));
        LocalDateTime originalOccurredAt = jdbc.queryForObject("SELECT occurred_at FROM payment_transaction WHERE id=:id",
                Map.of("id", transactionId), LocalDateTime.class);
        YearMonth billPeriod = YearMonth.parse(String.valueOf(originalBillDates.get("billing_period")));
        LocalDate periodStart = billPeriod.atDay(1);
        LocalDate periodEnd = billPeriod.atEndOfMonth();
        jdbc.update("UPDATE bill SET created_at=:createdAt WHERE id=:id",
                Map.of("createdAt", periodStart.atStartOfDay(), "id", billId));
        jdbc.update("UPDATE payment_transaction SET occurred_at=:occurredAt WHERE id=:id",
                Map.of("occurredAt", periodStart.plusDays(10).atTime(12, 0), "id", transactionId));
        JsonNode collectedInside = getJson(token, "/api/v1/reports/COLLECTION_CLEARANCE_SUMMARY?communityId=" + PROJECT
                + "&dateFrom=" + periodStart + "&dateTo=" + periodEnd);
        String summaryReversalId = UUID.randomUUID().toString();
        LocalDateTime summaryReversalAt = periodStart.plusDays(11).atTime(12, 0);
        jdbc.update("""
                INSERT INTO payment_transaction
                    (id,community_id,payment_order_id,transaction_no,adapter_code,payment_channel,
                     cashier_shift_id,transaction_type,original_transaction_id,status,amount,
                     external_reference,request_key,snapshot_json,occurred_at,created_at)
                SELECT :reversalId,community_id,payment_order_id,:transactionNo,'REVERSAL',payment_channel,
                       cashier_shift_id,'REVERSAL',id,'SUCCESS',-amount,
                       id,:requestKey,JSON_OBJECT('testFixture',TRUE),:occurredAt,:occurredAt
                FROM payment_transaction WHERE id=:originalTransactionId
                """, Map.of("reversalId", summaryReversalId, "transactionNo", "REV-SUMMARY-" + suffix,
                        "requestKey", "summary-reversal-" + suffix, "occurredAt", summaryReversalAt,
                        "originalTransactionId", transactionId));
        jdbc.update("""
                INSERT INTO payment_allocation (id,payment_transaction_id,bill_id,allocated_amount,created_at)
                SELECT :allocationId,:reversalId,bill_id,-allocated_amount,:createdAt
                FROM payment_allocation WHERE payment_transaction_id=:originalTransactionId
                """, Map.of("allocationId", UUID.randomUUID().toString(), "reversalId", summaryReversalId,
                        "createdAt", summaryReversalAt, "originalTransactionId", transactionId));
        JsonNode collectedAfterReversal = getJson(token,
                "/api/v1/reports/COLLECTION_CLEARANCE_SUMMARY?communityId=" + PROJECT
                        + "&dateFrom=" + periodStart + "&dateTo=" + periodEnd);
        assertThat(decimal(collectedInside.path("rows").get(0).path("currentCollected"))
                .subtract(decimal(collectedAfterReversal.path("rows").get(0).path("currentCollected"))))
                .isEqualByComparingTo("1.00");
        jdbc.update("DELETE FROM payment_allocation WHERE payment_transaction_id=:id",
                Map.of("id", summaryReversalId));
        jdbc.update("DELETE FROM payment_transaction WHERE id=:id", Map.of("id", summaryReversalId));
        jdbc.update("UPDATE payment_transaction SET occurred_at=:occurredAt WHERE id=:id",
                Map.of("occurredAt", periodEnd.plusDays(1).atStartOfDay(), "id", transactionId));
        JsonNode collectedOutside = getJson(token, "/api/v1/reports/COLLECTION_CLEARANCE_SUMMARY?communityId=" + PROJECT
                + "&dateFrom=" + periodStart + "&dateTo=" + periodEnd);
        assertThat(decimal(collectedInside.path("rows").get(0).path("currentCollected"))
                .subtract(decimal(collectedOutside.path("rows").get(0).path("currentCollected"))))
                .isEqualByComparingTo("1.00");

        YearMonth clearancePeriod = billPeriod.plusMonths(1);
        LocalDate clearanceStart = clearancePeriod.atDay(1);
        LocalDate clearanceEnd = clearancePeriod.atEndOfMonth();
        jdbc.update("UPDATE bill SET due_date=:dueDate,created_at=:createdAt WHERE id=:id",
                Map.of("dueDate", clearanceStart.minusDays(1), "createdAt", periodStart.atStartOfDay(), "id", billId));
        jdbc.update("UPDATE payment_transaction SET occurred_at=:occurredAt WHERE id=:id",
                Map.of("occurredAt", clearanceStart.plusDays(3).atTime(12, 0), "id", transactionId));
        JsonNode clearedInside = getJson(token, "/api/v1/reports/COLLECTION_CLEARANCE_SUMMARY?communityId=" + PROJECT
                + "&dateFrom=" + clearanceStart + "&dateTo=" + clearanceEnd);
        jdbc.update("UPDATE payment_transaction SET occurred_at=:occurredAt WHERE id=:id",
                Map.of("occurredAt", clearanceEnd.plusDays(1).atStartOfDay(), "id", transactionId));
        JsonNode clearedOutside = getJson(token, "/api/v1/reports/COLLECTION_CLEARANCE_SUMMARY?communityId=" + PROJECT
                + "&dateFrom=" + clearanceStart + "&dateTo=" + clearanceEnd);
        assertThat(decimal(clearedInside.path("rows").get(0).path("arrearsCleared"))
                .subtract(decimal(clearedOutside.path("rows").get(0).path("arrearsCleared"))))
                .isEqualByComparingTo("1.00");
        BigDecimal originalAllocation = jdbc.queryForObject("""
                SELECT allocated_amount FROM payment_allocation WHERE payment_transaction_id=:transactionId
                """, Map.of("transactionId", transactionId), BigDecimal.class);
        jdbc.update("UPDATE payment_transaction SET occurred_at=:occurredAt WHERE id=:id",
                Map.of("occurredAt", clearanceStart.plusDays(3).atTime(12, 0), "id", transactionId));
        jdbc.update("UPDATE payment_allocation SET allocated_amount=1000000000 WHERE payment_transaction_id=:transactionId",
                Map.of("transactionId", transactionId));
        JsonNode cappedClearance = getJson(token, "/api/v1/reports/ARREARS_CLEARANCE_RATE?communityId=" + PROJECT
                + "&dateFrom=" + clearanceStart + "&dateTo=" + clearanceEnd
                + "&arrearsPeriodFrom=" + billPeriod + "&arrearsPeriodTo=" + billPeriod);
        JsonNode cappedRow = cappedClearance.path("rows").get(0);
        assertThat(decimal(cappedRow.path("clearedAmount")))
                .isLessThanOrEqualTo(decimal(cappedRow.path("openingArrears")));
        assertThat(decimal(cappedRow.path("closingArrears"))).isNotNegative();
        assertThat(decimal(cappedRow.path("clearanceRate"))).isLessThanOrEqualTo(new BigDecimal("100.00"));
        jdbc.update("UPDATE payment_allocation SET allocated_amount=:amount WHERE payment_transaction_id=:transactionId",
                Map.of("amount", originalAllocation, "transactionId", transactionId));
        jdbc.update("UPDATE payment_transaction SET occurred_at=:occurredAt WHERE id=:id",
                Map.of("occurredAt", clearanceStart.minusDays(1).atTime(12, 0), "id", transactionId));
        String clearanceUrl = "/api/v1/reports/ARREARS_CLEARANCE_RATE?communityId=" + PROJECT
                + "&dateFrom=" + clearanceStart + "&dateTo=" + clearanceEnd
                + "&arrearsPeriodFrom=" + billPeriod + "&arrearsPeriodTo=" + billPeriod;
        JsonNode clearanceBeforeReversal = getJson(token, clearanceUrl).path("rows").get(0);
        String clearanceReversalId = UUID.randomUUID().toString();
        LocalDateTime clearanceReversalAt = clearanceStart.plusDays(3).atTime(12, 0);
        jdbc.update("""
                INSERT INTO payment_transaction
                    (id,community_id,payment_order_id,transaction_no,adapter_code,payment_channel,
                     cashier_shift_id,transaction_type,original_transaction_id,status,amount,
                     external_reference,request_key,snapshot_json,occurred_at,created_at)
                SELECT :reversalId,community_id,payment_order_id,:transactionNo,'REVERSAL',payment_channel,
                       cashier_shift_id,'REVERSAL',id,'SUCCESS',-amount,
                       id,:requestKey,JSON_OBJECT('testFixture',TRUE),:occurredAt,:occurredAt
                FROM payment_transaction WHERE id=:originalTransactionId
                """, Map.of("reversalId", clearanceReversalId, "transactionNo", "REV-CLEARANCE-" + suffix,
                        "requestKey", "clearance-reversal-" + suffix, "occurredAt", clearanceReversalAt,
                        "originalTransactionId", transactionId));
        jdbc.update("""
                INSERT INTO payment_allocation (id,payment_transaction_id,bill_id,allocated_amount,created_at)
                SELECT :allocationId,:reversalId,bill_id,-allocated_amount,:createdAt
                FROM payment_allocation WHERE payment_transaction_id=:originalTransactionId
                """, Map.of("allocationId", UUID.randomUUID().toString(), "reversalId", clearanceReversalId,
                        "createdAt", clearanceReversalAt, "originalTransactionId", transactionId));
        JsonNode clearanceAfterReversal = getJson(token, clearanceUrl).path("rows").get(0);
        assertThat(decimal(clearanceAfterReversal.path("openingArrears")))
                .isEqualByComparingTo(decimal(clearanceBeforeReversal.path("openingArrears")));
        assertThat(decimal(clearanceAfterReversal.path("clearedAmount")))
                .isEqualByComparingTo(decimal(clearanceBeforeReversal.path("clearedAmount")));
        assertThat(decimal(clearanceAfterReversal.path("closingArrears"))
                .subtract(decimal(clearanceBeforeReversal.path("closingArrears"))))
                .isEqualByComparingTo(originalAllocation.abs());
        assertThat(decimal(clearanceAfterReversal.path("clearanceRate")))
                .isEqualByComparingTo(decimal(clearanceBeforeReversal.path("clearanceRate")));
        jdbc.update("DELETE FROM payment_allocation WHERE payment_transaction_id=:id",
                Map.of("id", clearanceReversalId));
        jdbc.update("DELETE FROM payment_transaction WHERE id=:id", Map.of("id", clearanceReversalId));
        jdbc.update("UPDATE bill SET due_date=:dueDate,created_at=:createdAt WHERE id=:id",
                Map.of("dueDate", originalBillDates.get("due_date"), "createdAt", originalBillDates.get("created_at"), "id", billId));
        jdbc.update("UPDATE payment_transaction SET occurred_at=:occurredAt WHERE id=:id",
                Map.of("occurredAt", originalOccurredAt, "id", transactionId));

        String feeDefinitionId = jdbc.queryForObject("""
                SELECT bi.fee_definition_id FROM bill_item bi JOIN bill b ON b.id=bi.bill_id
                WHERE b.community_id=:communityId GROUP BY bi.fee_definition_id ORDER BY SUM(bi.amount) LIMIT 1
                """, Map.of("communityId", PROJECT), String.class);
        JsonNode unfilteredRate = getJson(token, "/api/v1/reports/COLLECTION_RATE?communityId=" + PROJECT);
        JsonNode filteredRate = getJson(token, "/api/v1/reports/COLLECTION_RATE?communityId=" + PROJECT
                + "&feeDefinitionId=" + feeDefinitionId);
        assertThat(decimal(filteredRate.path("rows").get(0).path("receivableAmount")))
                .isPositive().isLessThanOrEqualTo(decimal(unfilteredRate.path("rows").get(0).path("receivableAmount")));
        assertThat(filteredRate.path("appliedFilters").path("feeDefinitionId").asText()).isEqualTo(feeDefinitionId);
        String unusedFeeDefinitionId = jdbc.queryForObject("""
                SELECT fd.id FROM fee_definition fd LEFT JOIN bill_item bi ON bi.fee_definition_id=fd.id
                WHERE fd.community_id=:communityId AND bi.id IS NULL ORDER BY fd.id LIMIT 1
                """, Map.of("communityId", PROJECT), String.class);
        JsonNode emptyFeeRate = getJson(token, "/api/v1/reports/COLLECTION_RATE?communityId=" + PROJECT
                + "&feeDefinitionId=" + unusedFeeDefinitionId);
        assertThat(decimal(emptyFeeRate.path("rows").get(0).path("receivableAmount"))).isZero();
        getRequest(token, "/api/v1/reports/COLLECTION_RATE?communityId=" + PROJECT
                + "&feeDefinitionId=" + externalFeeDefinitionId)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REPORT_FILTER_SCOPE_INVALID"));
        getRequest(token, "/api/v1/reports/TRANSACTION_SUMMARY?communityId=" + PROJECT
                + "&cashierId=" + externalCashierId)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REPORT_FILTER_SCOPE_INVALID"));

        String paidFeeDefinitionId = jdbc.queryForObject("""
                SELECT fee_definition_id FROM bill_item WHERE bill_id=:billId ORDER BY id LIMIT 1
                """, Map.of("billId", billId), String.class);
        String secondFeeDefinitionId = unusedFeeDefinitionId;
        BigDecimal extraItemAmount = jdbc.queryForObject("""
                SELECT amount FROM bill_item WHERE bill_id=:billId ORDER BY id LIMIT 1
                """, Map.of("billId", billId), BigDecimal.class);
        String extraItemId = UUID.randomUUID().toString();
        LocalDateTime fixtureNow = LocalDateTime.now();
        jdbc.update("""
                INSERT INTO bill_item
                    (id,bill_id,fee_definition_id,fee_standard_version_id,fee_allocation_id,item_name_snapshot,
                     quantity,unit_price,coefficient,amount,calculation_snapshot,created_at,source_type,source_id)
                VALUES (:id,:billId,:feeDefinitionId,NULL,NULL,'报表费用比例验收项',
                        1,:amount,1,:amount,'{}',:now,'REPORT_INTEGRATION_TEST',:sourceId)
                """, Map.of("id", extraItemId, "billId", billId, "feeDefinitionId", secondFeeDefinitionId,
                        "amount", extraItemAmount, "now", fixtureNow, "sourceId", extraItemId));
        BigDecimal adjustmentAmount = new BigDecimal("0.37");
        jdbc.update("""
                UPDATE bill b JOIN (
                    SELECT bill_id,SUM(amount) item_total FROM bill_item WHERE bill_id=:id GROUP BY bill_id
                ) items ON items.bill_id=b.id
                SET b.original_amount=items.item_total,
                    b.adjustment_amount=0-:adjustmentAmount,
                    b.total_amount=items.item_total-:adjustmentAmount,
                    b.outstanding_amount=items.item_total-:adjustmentAmount-b.paid_amount,
                    b.updated_at=:now
                WHERE b.id=:id
                """, Map.of("adjustmentAmount", adjustmentAmount, "now", fixtureNow, "id", billId));
        Map<String, Object> adjustedBillAmounts = jdbc.queryForMap("""
                SELECT b.total_amount,SUM(bi.amount) item_total
                FROM bill b JOIN bill_item bi ON bi.bill_id=b.id
                WHERE b.id=:billId GROUP BY b.id,b.total_amount
                """, Map.of("billId", billId));
        assertThat(decimal(adjustedBillAmounts.get("total_amount")))
                .isNotEqualByComparingTo(decimal(adjustedBillAmounts.get("item_total")));
        assertThat(decimal(adjustedBillAmounts.get("item_total"))
                .subtract(decimal(adjustedBillAmounts.get("total_amount"))))
                .isEqualByComparingTo(adjustmentAmount);
        BigDecimal expectedSecondFeeReceivable = jdbc.queryForObject("""
                SELECT b.total_amount*SUM(CASE WHEN bi.fee_definition_id=:feeDefinitionId THEN bi.amount ELSE 0 END)
                       /SUM(bi.amount)
                FROM bill b JOIN bill_item bi ON bi.bill_id=b.id
                WHERE b.id=:billId GROUP BY b.id,b.total_amount
                """, Map.of("feeDefinitionId", secondFeeDefinitionId, "billId", billId), BigDecimal.class);
        JsonNode adjustedRate = getJson(token, "/api/v1/reports/COLLECTION_RATE?communityId=" + PROJECT
                + "&feeDefinitionId=" + secondFeeDefinitionId);
        JsonNode adjustedSummary = getJson(token, "/api/v1/reports/COLLECTION_CLEARANCE_SUMMARY?communityId=" + PROJECT
                + "&feeDefinitionId=" + secondFeeDefinitionId);
        JsonNode adjustedFeeStatus = getJson(token, "/api/v1/reports/FEE_STATUS?communityId=" + PROJECT
                + "&feeDefinitionId=" + secondFeeDefinitionId);
        assertThat(decimal(adjustedRate.path("rows").get(0).path("receivableAmount")))
                .isEqualByComparingTo(expectedSecondFeeReceivable);
        assertThat(decimal(adjustedSummary.path("rows").get(0).path("currentReceivable")))
                .isEqualByComparingTo(expectedSecondFeeReceivable);
        BigDecimal feeStatusReceivable = BigDecimal.ZERO;
        for (JsonNode row : adjustedFeeStatus.path("rows")) {
            feeStatusReceivable = feeStatusReceivable.add(decimal(row.path("receivableAmount")));
        }
        assertThat(feeStatusReceivable).isEqualByComparingTo(expectedSecondFeeReceivable);
        String oldReceiptNo = jdbc.queryForObject("SELECT receipt_no FROM receipt WHERE id=:id",
                Map.of("id", receiptId), String.class);
        JsonNode chargeBeforeReplacement = getJson(token, "/api/v1/reports/CHARGE_DETAILS?communityId=" + PROJECT
                + "&feeDefinitionId=" + paidFeeDefinitionId);
        JsonNode oldChargeRow = rowWith(chargeBeforeReplacement, "receiptNo", oldReceiptNo);
        BigDecimal expectedFeeShare = jdbc.queryForObject("""
                SELECT ROUND(pa.allocated_amount
                    *SUM(CASE WHEN bi.fee_definition_id=:feeDefinitionId THEN bi.amount ELSE 0 END)/SUM(bi.amount),2)
                FROM payment_allocation pa JOIN bill_item bi ON bi.bill_id=pa.bill_id
                WHERE pa.payment_transaction_id=:transactionId GROUP BY pa.id,pa.allocated_amount
                """, Map.of("feeDefinitionId", paidFeeDefinitionId, "transactionId", transactionId), BigDecimal.class);
        assertThat(decimal(oldChargeRow.path("amount"))).isEqualByComparingTo(expectedFeeShare);
        String reversalId = UUID.randomUUID().toString();
        LocalDateTime reversalCreatedAt = LocalDateTime.now();
        jdbc.update("""
                INSERT INTO payment_transaction
                    (id,community_id,payment_order_id,transaction_no,adapter_code,payment_channel,
                     cashier_shift_id,transaction_type,original_transaction_id,status,amount,
                     external_reference,request_key,snapshot_json,occurred_at,created_at)
                SELECT :reversalId,community_id,payment_order_id,:transactionNo,'REVERSAL',payment_channel,
                       cashier_shift_id,'REVERSAL',id,'SUCCESS',-amount,
                       id,:requestKey,JSON_OBJECT('testFixture',TRUE),occurred_at,:createdAt
                FROM payment_transaction WHERE id=:originalTransactionId
                """, Map.of("reversalId", reversalId, "transactionNo", "REV-REPORT-" + suffix,
                        "requestKey", "report-reversal-" + suffix, "createdAt", reversalCreatedAt,
                        "originalTransactionId", transactionId));
        jdbc.update("""
                INSERT INTO payment_allocation (id,payment_transaction_id,bill_id,allocated_amount,created_at)
                SELECT :allocationId,:reversalId,bill_id,-allocated_amount,:createdAt
                FROM payment_allocation WHERE payment_transaction_id=:originalTransactionId
                """, Map.of("allocationId", UUID.randomUUID().toString(), "reversalId", reversalId,
                        "createdAt", reversalCreatedAt, "originalTransactionId", transactionId));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM payment_transaction
                WHERE id=:id AND transaction_type='REVERSAL' AND status='SUCCESS'
                """, Map.of("id", reversalId), Integer.class)).isEqualTo(1);
        JsonNode chargeWithSuccessfulReversal = getJson(token,
                "/api/v1/reports/CHARGE_DETAILS?communityId=" + PROJECT
                        + "&feeDefinitionId=" + paidFeeDefinitionId);
        assertThat(chargeWithSuccessfulReversal.path("total").asInt())
                .isEqualTo(chargeBeforeReplacement.path("total").asInt() + 1);
        List<BigDecimal> signedReceiptAmounts = new ArrayList<>();
        for (JsonNode row : chargeWithSuccessfulReversal.path("rows")) {
            if (oldReceiptNo.equals(row.path("receiptNo").asText())) {
                signedReceiptAmounts.add(decimal(row.path("amount")));
            }
        }
        assertThat(signedReceiptAmounts).contains(expectedFeeShare, expectedFeeShare.negate());
        assertThat(signedReceiptAmounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isZero();
        jdbc.update("DELETE FROM payment_allocation WHERE payment_transaction_id=:id", Map.of("id", reversalId));
        jdbc.update("DELETE FROM payment_transaction WHERE id=:id", Map.of("id", reversalId));
        JsonNode replacement = postOk(token, "/api/v1/finance/receipts/" + receiptId + ":replace",
                body("communityId", PROJECT, "reason", "报表换票去重验收"), null);
        receiptId = replacement.path("id").asText();
        JsonNode chargeAfterReplacement = getJson(token, "/api/v1/reports/CHARGE_DETAILS?communityId=" + PROJECT
                + "&feeDefinitionId=" + paidFeeDefinitionId);
        JsonNode replacementChargeRow = rowWith(chargeAfterReplacement, "receiptNo", replacement.path("receipt_no").asText());
        assertThat(chargeAfterReplacement.path("total").asInt()).isEqualTo(chargeBeforeReplacement.path("total").asInt());
        assertThat(decimal(replacementChargeRow.path("amount"))).isEqualByComparingTo(expectedFeeShare);
        assertThat(chargeAfterReplacement.path("rows").findValuesAsText("receiptNo")).doesNotContain(oldReceiptNo);

        for (String code : ReportQueryEngine.CODES) {
            JsonNode report = getJson(token, "/api/v1/reports/" + code + "?communityId=" + PROJECT + "&page=1&size=50");
            assertThat(report.path("reportCode").asText()).isEqualTo(code);
            assertThat(report.path("formulaNote").asText()).isNotBlank();
            assertThat(report.path("fixedSample").isObject()).isTrue();
            assertThat(report.path("queryChecksum").asText()).hasSize(64);
            assertThat(report.path("summary").path("rowCount").asInt()).isEqualTo(report.path("total").asInt());
            assertThat(report.path("durationMs").asLong()).isLessThan(2_000);
            if (List.of("COLLECTION_RATE", "COLLECTION_CLEARANCE_SUMMARY", "CHARGE_DETAILS", "FEE_STATUS").contains(code)) {
                assertThat(report.path("formula").path("feeShare").asText())
                        .isEqualTo("selectedItemAmount/allItemAmount");
            }
            if ("COLLECTION_CLEARANCE_SUMMARY".equals(code)) {
                assertThat(report.path("formula").path("currentCollected").asText())
                        .isEqualTo("signedPeriodAllocation*feeShare");
                assertThat(report.path("formula").path("arrearsCleared").asText())
                        .isEqualTo("min(max(signedPeriodAllocation,0),openingArrears)*feeShare");
            }
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
        assertThat(decimal(dashboard.path("finance").path("receivable")))
                .isEqualByComparingTo(decimal(rate.path("rows").get(0).path("receivableAmount")));

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
            String storedFilters = jdbc.queryForObject("SELECT filters_json FROM report_export_job WHERE id=:id",
                    Map.of("id", created.path("id").asText()), String.class);
            assertThat(storedFilters).contains("billingPeriodFrom", "billingPeriodTo")
                    .doesNotContain("periodFrom", "periodTo");
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
        JsonNode reportReaderOptions = getJson(scopedToken, "/api/v1/reports/filter-options?communityId=" + PROJECT
                + "&reportCode=COLLECTION_RATE");
        assertThat(reportReaderOptions.path("feeDefinitionId")).isNotEmpty();
        assertThat(reportReaderOptions.path("cashierId")).isEmpty();
        getRequest(scopedToken, "/api/v1/reports/filter-options?communityId=" + PROJECT
                + "&reportCode=TRANSACTION_SUMMARY")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        JsonNode financeReaderOptions = getJson(financeToken, "/api/v1/reports/filter-options?communityId=" + PROJECT
                + "&reportCode=TRANSACTION_SUMMARY");
        assertThat(financeReaderOptions.path("feeDefinitionId")).isEmpty();
        assertThat(financeReaderOptions.path("cashierId")).isNotEmpty();
        getRequest(financeToken, "/api/v1/reports/filter-options?communityId=" + PROJECT
                + "&reportCode=COLLECTION_RATE")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
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
        getRequest(scopedToken, "/api/v1/reports/filter-options?communityId=30000000-0000-0000-0000-000000000002"
                + "&reportCode=COLLECTION_RATE")
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

    private String insertExternalFeeDefinition(String suffix) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO fee_definition
                    (id,community_id,source_id,code,name,fee_type,fee_class,unit_code,decimal_scale,
                     rounding_mode,currency_code,late_fee_enabled,temporary_allowed,enabled,
                     accounting_subject_code,prepayment_subject_code,tax_category_code,tax_rate,
                     version,created_at,updated_at)
                SELECT :id,'30000000-0000-0000-0000-000000000002',CONCAT('EXT-',:suffix),
                       CONCAT('EXT-',:suffix),'异项目费用定义',fee_type,fee_class,unit_code,decimal_scale,
                       rounding_mode,currency_code,late_fee_enabled,temporary_allowed,enabled,
                       accounting_subject_code,prepayment_subject_code,tax_category_code,tax_rate,
                       0,:now,:now
                FROM fee_definition WHERE community_id=:sourceCommunity ORDER BY id LIMIT 1
                """, body("id", id, "suffix", suffix, "now", LocalDateTime.now(), "sourceCommunity", PROJECT));
        return id;
    }

    private void insertDiscountAdjustmentFixture(String billId, String userId, String adjustmentNo,
                                                 String status, LocalDateTime createdAt,
                                                 LocalDateTime effectiveAt) {
        LocalDateTime rejectedAt = "REJECTED".equals(status) ? createdAt.plusMinutes(1) : null;
        jdbc.update("""
                INSERT INTO bill_adjustment
                    (id,community_id,bill_id,discount_policy_id,adjustment_no,adjustment_type,
                     amount,status,reason,request_key,request_hash,request_json,before_snapshot,
                     after_snapshot,requested_by,approved_by,approved_at,applied_at,rejected_at,
                     version,created_at,updated_at)
                VALUES (:id,:communityId,:billId,NULL,:adjustmentNo,'DISCOUNT',
                        -1.00,:status,'报表有效时间边界验收',:requestKey,:requestHash,
                        JSON_OBJECT('fixture',TRUE),JSON_OBJECT('fixture',TRUE),
                        CASE WHEN :status='APPLIED' THEN JSON_OBJECT('fixture',TRUE) ELSE NULL END,
                        :userId,CASE WHEN :status IN ('APPLIED','REJECTED') THEN :userId ELSE NULL END,
                        :effectiveAt,:effectiveAt,:rejectedAt,0,:createdAt,:updatedAt)
                """, new MapSqlParameterSource("id", UUID.randomUUID().toString())
                .addValue("communityId", PROJECT).addValue("billId", billId)
                .addValue("adjustmentNo", adjustmentNo).addValue("status", status)
                .addValue("requestKey", "report-fixture-" + adjustmentNo)
                .addValue("requestHash", "d".repeat(64)).addValue("userId", userId)
                .addValue("effectiveAt", effectiveAt).addValue("rejectedAt", rejectedAt)
                .addValue("createdAt", createdAt)
                .addValue("updatedAt", effectiveAt == null ? rejectedAt == null ? createdAt : rejectedAt : effectiveAt));
    }

    private String insertInactiveFeeDefinition(String suffix) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO fee_definition
                    (id,community_id,source_id,code,name,fee_type,fee_class,unit_code,decimal_scale,
                     rounding_mode,currency_code,late_fee_enabled,temporary_allowed,enabled,
                     accounting_subject_code,prepayment_subject_code,tax_category_code,tax_rate,
                     version,created_at,updated_at)
                SELECT :id,community_id,CONCAT('HIST-OFF-',:suffix),CONCAT('HIST-OFF-',:suffix),
                       '历史停用费用定义',fee_type,fee_class,unit_code,decimal_scale,
                       rounding_mode,currency_code,late_fee_enabled,temporary_allowed,FALSE,
                       accounting_subject_code,prepayment_subject_code,tax_category_code,tax_rate,
                       0,:now,:now
                FROM fee_definition WHERE community_id=:communityId ORDER BY id LIMIT 1
                """, body("id", id, "suffix", suffix, "now", LocalDateTime.now(), "communityId", PROJECT));
        return id;
    }

    private void insertExternalCashierShift(String cashierId, String suffix) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                INSERT INTO cashier_shift
                    (id,community_id,shift_no,cashier_user_id,status,opening_cash,expected_cash,
                     actual_cash,variance_amount,request_key,request_hash,request_json,
                     opened_at,closed_at,version,created_at,updated_at)
                VALUES (:id,'30000000-0000-0000-0000-000000000002',:shiftNo,:cashierId,'CLOSED',0,0,
                        0,0,:requestKey,:requestHash,'{}',:now,:now,0,:now,:now)
                """, body("id", UUID.randomUUID().toString(), "shiftNo", "EXT-SHIFT-" + suffix,
                        "cashierId", cashierId, "requestKey", "ext-shift-" + suffix,
                        "requestHash", "a".repeat(64), "now", now));
    }

    private void insertProjectCashierShift(String cashierId, String marker, String suffix) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                INSERT INTO cashier_shift
                    (id,community_id,shift_no,cashier_user_id,status,opening_cash,expected_cash,
                     actual_cash,variance_amount,request_key,request_hash,request_json,
                     opened_at,closed_at,version,created_at,updated_at)
                VALUES (:id,:communityId,:shiftNo,:cashierId,'CLOSED',0,0,
                        0,0,:requestKey,:requestHash,'{}',:now,:now,0,:now,:now)
                """, body("id", UUID.randomUUID().toString(), "communityId", PROJECT,
                        "shiftNo", "HIST-" + marker + "-" + suffix, "cashierId", cashierId,
                        "requestKey", "hist-" + marker.toLowerCase() + "-" + suffix,
                        "requestHash", "b".repeat(64), "now", now));
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

    private JsonNode rowWith(JsonNode report, String field, String value) {
        for (JsonNode row : report.path("rows")) if (value.equals(row.path(field).asText())) return row;
        throw new AssertionError("Report row not found: " + field + "=" + value);
    }

    private JsonNode optionWith(JsonNode options, String value) {
        for (JsonNode option : options) if (value.equals(option.path("value").asText())) return option;
        throw new AssertionError("Report filter option not found: " + value);
    }

    private JsonNode parameterWith(JsonNode parameters, String name) {
        for (JsonNode parameter : parameters) if (name.equals(parameter.path("name").asText())) return parameter;
        throw new AssertionError("OpenAPI parameter not found: " + name);
    }

    private BigDecimal decimal(JsonNode node) { return new BigDecimal(node.asText("0")); }

    private BigDecimal decimal(Object value) {
        return value instanceof BigDecimal amount ? amount : new BigDecimal(String.valueOf(value));
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
