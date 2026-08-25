package com.propertyops.pms.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
class FinancialLedgerIntegrationTest {
    private static final String PROJECT = "30000000-0000-0000-0000-000000000001";
    private static final String ADMIN_USERNAME = "finance-integration-admin";
    private static final String ADMIN_PASSWORD = "finance-integration-admin-password";

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("pms_finance_test")
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
        registry.add("pms.security.jwt-secret", () -> "finance-integration-only-jwt-secret-with-more-than-32-characters");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired NamedParameterJdbcTemplate jdbc;

    @Test
    void completeFinancialLifecycleIsIdempotentConservedTraceableAndLockable() throws Exception {
        String token = login();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        mockMvc.perform(get("/api/v1/finance/bills")
                        .header("Authorization", "Bearer " + token)
                        .param("communityId", PROJECT)
                        .param("status", "UNPAID")
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].asset_id").isNotEmpty())
                .andExpect(jsonPath("$.items[0].customer_id").isNotEmpty());
        List<Map<String, Object>> bills = jdbc.queryForList("""
                SELECT id, customer_id, asset_id, outstanding_amount FROM bill
                WHERE community_id=:communityId AND status='UNPAID' AND customer_id IS NOT NULL
                  AND outstanding_amount>=30 ORDER BY bill_no LIMIT 6
                """, Map.of("communityId", PROJECT));
        assertThat(bills).hasSizeGreaterThanOrEqualTo(6);

        String shiftKey = "g6-shift-" + suffix;
        JsonNode shift = postOk(token, "/api/v1/cashier/shifts",
                body("communityId", PROJECT, "openingCash", money("100")), shiftKey);
        String shiftId = shift.path("id").asText();
        postRequest(token, "/api/v1/cashier/shifts",
                body("communityId", PROJECT, "openingCash", money("100")), shiftKey)
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(shiftId));
        postRequest(token, "/api/v1/cashier/shifts",
                body("communityId", PROJECT, "openingCash", money("101")), shiftKey)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FINANCIAL_CONFLICT"));

        String firstBill = id(bills.get(0));
        String secondBill = id(bills.get(1));
        String paymentKey = "g6-payment-" + suffix;
        Map<String, Object> combinedPayment = body(
                "communityId", PROJECT, "paymentMethod", "CASH",
                "bills", List.of(body("billId", firstBill, "amount", money("10")),
                        body("billId", secondBill, "amount", money("20"))));
        JsonNode order = postOk(token, "/api/v1/payment-orders", combinedPayment, paymentKey);
        assertThat(order.path("cashierShiftId").asText()).isEqualTo(shiftId);
        String orderId = order.path("orderId").asText();
        JsonNode confirmed = postOk(token,
                "/api/v1/payment-orders/" + orderId + ":confirm-simulated?communityId=" + PROJECT,
                body(), null);
        String reversedTransactionId = confirmed.path("transactionId").asText();
        postRequest(token, "/api/v1/payment-orders/" + orderId + ":confirm-simulated?communityId=" + PROJECT,
                body(), null).andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true));
        assertThat(count("SELECT COUNT(*) FROM payment_transaction WHERE payment_order_id=:id AND transaction_type='PAYMENT'", orderId))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM receipt WHERE payment_order_id=:id", orderId)).isEqualTo(1);
        postRequest(token, "/api/v1/payment-orders",
                body("communityId", PROJECT, "paymentMethod", "CASH",
                        "bills", List.of(body("billId", firstBill, "amount", money("1")))), paymentKey)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FINANCIAL_CONFLICT"));

        postRequest(token, "/api/v1/payment-transactions/" + reversedTransactionId + ":reverse",
                body("communityId", PROJECT, "reason", "集成测试收款冲正"), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REVERSED"));
        assertBillBalance(firstBill);
        assertBillBalance(secondBill);

        String customerId = String.valueOf(bills.get(2).get("customer_id"));
        String thirdBill = id(bills.get(2));
        JsonNode prepayment = postOk(token, "/api/v1/prepayment-accounts",
                body("communityId", PROJECT, "customerId", customerId), null);
        String prepaymentId = prepayment.path("id").asText();
        String topUpKey = "g6-topup-" + suffix;
        postOk(token, "/api/v1/prepayment-accounts/" + prepaymentId + ":top-up",
                body("communityId", PROJECT, "amount", money("60"), "reason", "测试充值"), topUpKey);
        postRequest(token, "/api/v1/prepayment-accounts/" + prepaymentId + ":top-up",
                body("communityId", PROJECT, "amount", money("60"), "reason", "测试充值"), topUpKey)
                .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true));
        postRequest(token, "/api/v1/prepayment-accounts/" + prepaymentId + ":top-up",
                body("communityId", PROJECT, "amount", money("61"), "reason", "测试充值"), topUpKey)
                .andExpect(status().isConflict());
        JsonNode applied = postOk(token, "/api/v1/prepayment-accounts/" + prepaymentId + ":apply",
                body("communityId", PROJECT,
                        "bills", List.of(body("billId", thirdBill, "amount", money("15")))),
                "g6-prepay-apply-" + suffix);
        assertThat(balance("prepayment_account", prepaymentId)).isEqualByComparingTo("45.00");
        postRequest(token, "/api/v1/payment-transactions/" + applied.path("transactionId").asText() + ":reverse",
                body("communityId", PROJECT, "reason", "验证预收抵扣冲正恢复"), null)
                .andExpect(status().isOk());
        assertThat(balance("prepayment_account", prepaymentId)).isEqualByComparingTo("60.00");
        assertBillBalance(thirdBill);

        String depositKey = "g6-deposit-" + suffix;
        Map<String, Object> depositRequest = body(
                "communityId", PROJECT, "customerId", customerId, "assetId", bills.get(2).get("asset_id"),
                "depositType", "ACCESS_CARD", "amount", money("100"), "reason", "测试押金收取");
        JsonNode deposit = postOk(token, "/api/v1/deposits", depositRequest, depositKey);
        String depositId = deposit.path("accountId").asText();
        postRequest(token, "/api/v1/deposits", depositRequest, depositKey)
                .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true));
        Map<String, Object> changedDeposit = new LinkedHashMap<>(depositRequest);
        changedDeposit.put("amount", money("101"));
        postRequest(token, "/api/v1/deposits", changedDeposit, depositKey).andExpect(status().isConflict());
        postOk(token, "/api/v1/deposits/" + depositId + ":refund",
                body("communityId", PROJECT, "amount", money("40"), "reason", "测试退还"),
                "g6-deposit-refund-" + suffix);
        assertThat(balance("deposit_account", depositId)).isEqualByComparingTo("60.00");

        String adjustmentBill = id(bills.get(3));
        JsonNode adjustment = postOk(token, "/api/v1/finance/adjustments",
                body("communityId", PROJECT, "billId", adjustmentBill, "adjustmentType", "WAIVER",
                        "amount", money("5"), "reason", "集成测试减免审批"),
                "g6-adjustment-" + suffix);
        postRequest(token, "/api/v1/finance/adjustments/" + adjustment.path("id").asText() + ":approve",
                body("communityId", PROJECT, "expectedVersion", 0, "reason", "审批通过"), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPLIED"));
        assertBillBalance(adjustmentBill);

        String lockedBill = id(bills.get(4));
        JsonNode lockedOrder = postOk(token, "/api/v1/payment-orders",
                body("communityId", PROJECT, "paymentMethod", "CASH",
                        "bills", List.of(body("billId", lockedBill, "amount", money("5")))),
                "g6-lock-payment-" + suffix);
        JsonNode lockedPayment = postOk(token,
                "/api/v1/payment-orders/" + lockedOrder.path("orderId").asText()
                        + ":confirm-simulated?communityId=" + PROJECT, body(), null);
        String lockedTransactionId = lockedPayment.path("transactionId").asText();
        String lockedReceiptId = lockedPayment.path("receiptId").asText();

        JsonNode invoice = postOk(token, "/api/v1/invoices:simulate",
                body("communityId", PROJECT, "receiptId", lockedReceiptId, "title", "青岛物业测试抬头"), null);
        postRequest(token, "/api/v1/finance/invoices/" + invoice.path("id").asText() + ":operate",
                body("communityId", PROJECT, "operationType", "REPLACE", "title", "青岛物业换开抬头",
                        "reason", "抬头更正"), null).andExpect(status().isOk());
        postRequest(token, "/api/v1/finance/invoices/" + invoice.path("id").asText() + ":operate",
                body("communityId", PROJECT, "operationType", "RED", "title", "青岛物业测试抬头",
                        "reason", "红冲验证"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(-5.0));

        JsonNode replacementReceipt = postOk(token,
                "/api/v1/finance/receipts/" + lockedReceiptId + ":replace",
                body("communityId", PROJECT, "reason", "原收据打印模糊"), null);
        assertThat(replacementReceipt.path("original_receipt_id").asText()).isEqualTo(lockedReceiptId);
        assertThat(replacementReceipt.path("receipt_no").asText()).startsWith("SYN-RCT-M-");

        String concurrentBill = id(bills.get(5));
        JsonNode concurrentOrder = postOk(token, "/api/v1/payment-orders",
                body("communityId", PROJECT, "paymentMethod", "QR_SIMULATOR",
                        "bills", List.of(body("billId", concurrentBill, "amount", money("7")))),
                "g6-concurrent-" + suffix);
        String concurrentOrderId = concurrentOrder.path("orderId").asText();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<JsonNode> first = CompletableFuture.supplyAsync(
                    () -> confirmUnchecked(token, concurrentOrderId), executor);
            CompletableFuture<JsonNode> second = CompletableFuture.supplyAsync(
                    () -> confirmUnchecked(token, concurrentOrderId), executor);
            JsonNode firstResult = first.join();
            JsonNode secondResult = second.join();
            assertThat(List.of(firstResult.path("replayed").asBoolean(), secondResult.path("replayed").asBoolean()))
                    .containsExactlyInAnyOrder(false, true);
        } finally {
            executor.shutdownNow();
        }
        assertThat(count("SELECT COUNT(*) FROM payment_transaction WHERE payment_order_id=:id AND transaction_type='PAYMENT'",
                concurrentOrderId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM receipt WHERE payment_order_id=:id", concurrentOrderId)).isEqualTo(1);

        BigDecimal expectedCash = jdbc.queryForObject("""
                SELECT opening_cash + COALESCE((SELECT SUM(amount) FROM payment_transaction
                  WHERE cashier_shift_id=:id AND payment_channel='CASH' AND status='SUCCESS'),0)
                FROM cashier_shift WHERE id=:id
                """, Map.of("id", shiftId), BigDecimal.class);
        JsonNode closedShift = postOk(token, "/api/v1/cashier/shifts/" + shiftId + ":close",
                body("communityId", PROJECT, "actualCash", expectedCash, "expectedVersion", 0), null);
        assertThat(closedShift.path("variance_amount").decimalValue()).isEqualByComparingTo(BigDecimal.ZERO);
        postRequest(token, "/api/v1/cashier/shifts/" + shiftId + ":lock",
                body("communityId", PROJECT, "expectedVersion", 1, "reason", "交班复核完成"), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("LOCKED"));

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        JsonNode preview = getJson(token, "/api/v1/finance/settlements:preview?communityId=" + PROJECT
                + "&settlementDate=" + today);
        assertThat(preview.path("transactionCount").asInt()).isGreaterThanOrEqualTo(6);
        String settlementKey = "g6-settlement-" + suffix;
        JsonNode settlement = postOk(token, "/api/v1/finance/settlements",
                body("communityId", PROJECT, "settlementDate", today.toString()), settlementKey);
        String settlementId = settlement.path("id").asText();
        postRequest(token, "/api/v1/finance/settlements",
                body("communityId", PROJECT, "settlementDate", today.toString()), settlementKey)
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(settlementId));
        postRequest(token, "/api/v1/finance/settlements/" + settlementId + ":lock",
                body("communityId", PROJECT, "expectedVersion", 0, "reason", "财务日结复核完成"), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("LOCKED"));
        postRequest(token, "/api/v1/payment-transactions/" + lockedTransactionId + ":reverse",
                body("communityId", PROJECT, "reason", "锁定后冲正应失败"), null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FINANCIAL_CONFLICT"));

        JsonNode reconciliation = getJson(token, "/api/v1/finance/reconciliation?communityId=" + PROJECT);
        assertThat(reconciliation.path("healthy").asBoolean()).isTrue();
        for (String metric : List.of("billMismatches", "paymentMismatches", "prepaymentMismatches",
                "depositMismatches", "settlementMismatches", "receiptChecksumMismatches",
                "invoiceChecksumMismatches")) {
            assertThat(reconciliation.path(metric).asInt()).as(metric).isZero();
        }
    }

    private JsonNode confirmUnchecked(String token, String orderId) {
        try {
            return postOk(token, "/api/v1/payment-orders/" + orderId
                    + ":confirm-simulated?communityId=" + PROJECT, body(), null);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void assertBillBalance(String billId) {
        Map<String, Object> bill = jdbc.queryForMap("""
                SELECT original_amount, adjustment_amount, total_amount, paid_amount, outstanding_amount
                FROM bill WHERE id=:id
                """, Map.of("id", billId));
        BigDecimal original = decimal(bill.get("original_amount"));
        BigDecimal adjustment = decimal(bill.get("adjustment_amount"));
        BigDecimal total = decimal(bill.get("total_amount"));
        BigDecimal paid = decimal(bill.get("paid_amount"));
        BigDecimal outstanding = decimal(bill.get("outstanding_amount"));
        assertThat(original.add(adjustment)).isEqualByComparingTo(total);
        assertThat(paid.add(outstanding)).isEqualByComparingTo(total);
    }

    private BigDecimal balance(String table, String id) {
        if (!List.of("prepayment_account", "deposit_account").contains(table)) throw new IllegalArgumentException(table);
        return jdbc.queryForObject("SELECT balance FROM " + table + " WHERE id=:id", Map.of("id", id), BigDecimal.class);
    }

    private int count(String sql, String id) {
        Integer count = jdbc.queryForObject(sql, Map.of("id", id), Integer.class);
        return count == null ? 0 : count;
    }

    private String id(Map<String, Object> row) {
        return String.valueOf(row.get("id"));
    }

    private BigDecimal decimal(Object value) {
        return new BigDecimal(String.valueOf(value));
    }

    private BigDecimal money(String value) {
        return new BigDecimal(value).setScale(2);
    }

    private String login() throws Exception {
        return postOk(null, "/api/v1/auth/login",
                body("username", ADMIN_USERNAME, "password", ADMIN_PASSWORD), null)
                .path("accessToken").asText();
    }

    private JsonNode getJson(String token, String path) throws Exception {
        String response = mockMvc.perform(get(path).header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
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

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private Map<String, Object> body(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) result.put((String) values[index], values[index + 1]);
        return result;
    }
}
