package com.propertyops.pms.finance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.propertyops.pms.adapter.InvoiceAdapter;
import com.propertyops.pms.common.api.BusinessException;
import com.propertyops.pms.common.audit.AuditService;
import com.propertyops.pms.security.SecurityContextService;

@Service
public class FinancialGovernanceService {
    private final NamedParameterJdbcTemplate jdbc;
    private final SecurityContextService security;
    private final AuditService audit;
    private final InvoiceAdapter invoiceAdapter;
    private final ObjectMapper objectMapper;

    public FinancialGovernanceService(NamedParameterJdbcTemplate jdbc, SecurityContextService security,
                                      AuditService audit, InvoiceAdapter invoiceAdapter, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.security = security;
        this.audit = audit;
        this.invoiceAdapter = invoiceAdapter;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> bills(String communityId, String keyword, String status,
                                     LocalDate asOfDate, int page, int size) {
        readFinance(communityId);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 200);
        String pattern = "%" + text(keyword) + "%";
        String normalizedStatus = text(status);
        LocalDate effectiveDate = asOfDate == null ? LocalDate.now(ZoneOffset.UTC) : asOfDate;
        MapSqlParameterSource params = new MapSqlParameterSource("communityId", communityId)
                .addValue("keyword", pattern).addValue("status", normalizedStatus)
                .addValue("asOfDate", effectiveDate).addValue("size", safeSize)
                .addValue("offset", (safePage - 1) * safeSize);
        String where = """
                b.community_id=:communityId
                AND (:status='' OR b.status=:status)
                AND (b.bill_no LIKE :keyword OR a.code LIKE :keyword OR a.display_name LIKE :keyword
                     OR COALESCE(c.display_name, '') LIKE :keyword)
                AND b.charge_date<=:asOfDate
                """;
        String countSql = """
                SELECT COUNT(*) FROM bill b
                JOIN asset a ON a.id=b.asset_id LEFT JOIN customer c ON c.id=b.customer_id
                WHERE
                """ + where + "\n";
        Long total = jdbc.queryForObject(countSql, params, Long.class);
        String itemSql = """
                SELECT b.id, b.asset_id, b.customer_id, b.bill_no, b.bill_type, b.billing_period, b.charge_date, b.due_date,
                       b.original_amount, b.adjustment_amount, b.total_amount, b.paid_amount,
                       b.outstanding_amount, b.status, b.locked, b.lock_reason, b.version,
                       a.code asset_code, a.display_name asset_name, c.display_name customer_name,
                       DATEDIFF(:asOfDate, b.due_date) overdue_days
                FROM bill b JOIN asset a ON a.id=b.asset_id
                LEFT JOIN customer c ON c.id=b.customer_id
                WHERE
                """ + where + """
                ORDER BY b.due_date, b.bill_no LIMIT :size OFFSET :offset
                """;
        List<Map<String, Object>> items = jdbc.queryForList(itemSql, params);
        return Map.of("items", items, "page", safePage, "size", safeSize,
                "total", total == null ? 0 : total, "asOfDate", effectiveDate);
    }

    public Map<String, Object> bill(String communityId, String billId) {
        readFinance(communityId);
        Map<String, Object> bill = required("""
                SELECT b.*, a.code asset_code, a.display_name asset_name,
                       c.display_name customer_name, c.customer_no
                FROM bill b JOIN asset a ON a.id=b.asset_id
                LEFT JOIN customer c ON c.id=b.customer_id
                WHERE b.id=:id AND b.community_id=:communityId
                """, Map.of("id", billId, "communityId", communityId), "账单不存在");
        List<Map<String, Object>> items = jdbc.queryForList("""
                SELECT id, item_name_snapshot, quantity, unit_price, coefficient, amount,
                       calculation_snapshot, fee_definition_id, fee_standard_version_id,
                       fee_allocation_id, source_type, source_id
                FROM bill_item WHERE bill_id=:id ORDER BY created_at, id
                """, Map.of("id", billId));
        List<Map<String, Object>> adjustments = jdbc.queryForList("""
                SELECT ba.*, dp.policy_code, dp.display_name policy_name
                FROM bill_adjustment ba LEFT JOIN discount_policy dp ON dp.id=ba.discount_policy_id
                WHERE ba.bill_id=:id ORDER BY ba.created_at, ba.id
                """, Map.of("id", billId));
        List<Map<String, Object>> allocations = jdbc.queryForList("""
                SELECT pa.allocated_amount, pt.transaction_no, pt.transaction_type, pt.payment_channel,
                       pt.status, pt.occurred_at, pt.original_transaction_id
                FROM payment_allocation pa JOIN payment_transaction pt ON pt.id=pa.payment_transaction_id
                WHERE pa.bill_id=:id ORDER BY pt.occurred_at, pt.id
                """, Map.of("id", billId));
        return Map.of("bill", bill, "items", items, "adjustments", adjustments,
                "paymentAllocations", allocations);
    }

    public Map<String, Object> arrears(String communityId, LocalDate asOfDate, String keyword) {
        readFinance(communityId);
        LocalDate date = asOfDate == null ? LocalDate.now(ZoneOffset.UTC) : asOfDate;
        MapSqlParameterSource params = new MapSqlParameterSource("communityId", communityId)
                .addValue("asOfDate", date).addValue("keyword", "%" + text(keyword) + "%");
        List<Map<String, Object>> items = jdbc.queryForList("""
                SELECT b.id, b.bill_no, b.billing_period, b.due_date, b.total_amount,
                       b.paid_amount, b.outstanding_amount, b.status,
                       GREATEST(DATEDIFF(:asOfDate, b.due_date), 0) overdue_days,
                       a.code asset_code, a.display_name asset_name, c.display_name customer_name
                FROM bill b JOIN asset a ON a.id=b.asset_id
                LEFT JOIN customer c ON c.id=b.customer_id
                WHERE b.community_id=:communityId AND b.outstanding_amount>0 AND b.due_date<:asOfDate
                  AND (b.bill_no LIKE :keyword OR a.code LIKE :keyword OR a.display_name LIKE :keyword
                       OR COALESCE(c.display_name, '') LIKE :keyword)
                ORDER BY b.due_date, b.bill_no LIMIT 500
                """, params);
        BigDecimal total = items.stream().map(row -> decimal(row.get("outstanding_amount")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return Map.of("items", items, "asOfDate", date, "count", items.size(), "outstandingAmount", total);
    }

    public List<Map<String, Object>> transactions(String communityId, LocalDate from, LocalDate to,
                                                   String channel, String type) {
        readFinance(communityId);
        LocalDate start = from == null ? LocalDate.now(ZoneOffset.UTC).minusMonths(1) : from;
        LocalDate end = to == null ? LocalDate.now(ZoneOffset.UTC) : to;
        if (end.isBefore(start)) throw invalid("交易结束日期不能早于开始日期");
        return jdbc.queryForList("""
                SELECT pt.id, pt.transaction_no, po.order_no, pt.payment_channel,
                       pt.transaction_type, pt.status, pt.amount, pt.external_reference,
                       pt.original_transaction_id, pt.settlement_id, pt.occurred_at,
                       u.display_name cashier_name
                FROM payment_transaction pt JOIN payment_order po ON po.id=pt.payment_order_id
                JOIN sys_user u ON u.id=po.requested_by
                WHERE pt.community_id=:communityId
                  AND pt.occurred_at>=:startAt AND pt.occurred_at<:endAt
                  AND (:channel='' OR pt.payment_channel=:channel)
                  AND (:type='' OR pt.transaction_type=:type)
                ORDER BY pt.occurred_at DESC, pt.id DESC LIMIT 1000
                """, new MapSqlParameterSource("communityId", communityId)
                .addValue("startAt", start.atStartOfDay()).addValue("endAt", end.plusDays(1).atStartOfDay())
                .addValue("channel", text(channel)).addValue("type", text(type)));
    }

    public Map<String, Object> balances(String communityId, String customerId) {
        readFinance(communityId);
        MapSqlParameterSource params = new MapSqlParameterSource("communityId", communityId)
                .addValue("customerId", text(customerId));
        List<Map<String, Object>> prepayments = jdbc.queryForList("""
                SELECT pa.*, c.customer_no, c.display_name customer_name,
                       (SELECT COUNT(*) FROM prepayment_transaction pt WHERE pt.account_id=pa.id) transaction_count
                FROM prepayment_account pa JOIN customer c ON c.id=pa.customer_id
                WHERE pa.community_id=:communityId AND (:customerId='' OR pa.customer_id=:customerId)
                ORDER BY c.customer_no
                """, params);
        List<Map<String, Object>> deposits = jdbc.queryForList("""
                SELECT da.*, c.customer_no, c.display_name customer_name,
                       a.code asset_code, a.display_name asset_name,
                       (SELECT COUNT(*) FROM deposit_transaction dt WHERE dt.account_id=da.id) transaction_count
                FROM deposit_account da JOIN customer c ON c.id=da.customer_id
                LEFT JOIN asset a ON a.id=da.asset_id
                WHERE da.community_id=:communityId AND (:customerId='' OR da.customer_id=:customerId)
                ORDER BY c.customer_no, da.deposit_type
                """, params);
        return Map.of("prepayments", prepayments, "deposits", deposits);
    }

    public List<Map<String, Object>> discountPolicies(String communityId, LocalDate effectiveDate) {
        readFee(communityId);
        LocalDate date = effectiveDate == null ? LocalDate.now(ZoneOffset.UTC) : effectiveDate;
        return jdbc.queryForList("""
                SELECT * FROM discount_policy
                WHERE community_id=:communityId
                  AND effective_from<=:effectiveDate
                  AND (effective_to IS NULL OR effective_to>=:effectiveDate)
                ORDER BY status, policy_code
                """, Map.of("communityId", communityId, "effectiveDate", date));
    }

    @Transactional
    public Map<String, Object> createDiscountPolicy(FinancialModels.CreateDiscountPolicy request) {
        writeFee(request.communityId());
        validatePolicy(request.discountType(), request.discountValue(), request.maximumAmount(),
                request.effectiveFrom(), request.effectiveTo());
        String id = UUID.randomUUID().toString();
        LocalDateTime now = now();
        jdbc.update("""
                INSERT INTO discount_policy
                    (id, community_id, policy_code, display_name, discount_type, discount_value,
                     maximum_amount, effective_from, effective_to, approval_required, status,
                     version, created_at, updated_at)
                VALUES (:id, :communityId, :code, :name, :type, :value,
                        :maximum, :effectiveFrom, :effectiveTo, :approval, 'ACTIVE', 0, :now, :now)
                """, new MapSqlParameterSource("id", id).addValue("communityId", request.communityId())
                .addValue("code", request.policyCode().trim()).addValue("name", request.displayName().trim())
                .addValue("type", request.discountType().trim().toUpperCase()).addValue("value", request.discountValue())
                .addValue("maximum", request.maximumAmount()).addValue("effectiveFrom", request.effectiveFrom())
                .addValue("effectiveTo", request.effectiveTo()).addValue("approval", request.approvalRequired())
                .addValue("now", now));
        audit.success(request.communityId(), "discount-policy:create", "discount-policy", id,
                Map.of("code", request.policyCode()));
        return required("SELECT * FROM discount_policy WHERE id=:id", Map.of("id", id), "折扣策略创建失败");
    }

    @Transactional
    public Map<String, Object> updateDiscountPolicy(String id, String communityId,
                                                     FinancialModels.UpdateDiscountPolicy request) {
        writeFee(communityId);
        Map<String, Object> current = required("""
                SELECT * FROM discount_policy WHERE id=:id AND community_id=:communityId FOR UPDATE
                """, Map.of("id", id, "communityId", communityId), "折扣策略不存在");
        validatePolicy(String.valueOf(current.get("discount_type")), request.discountValue(), request.maximumAmount(),
                request.effectiveFrom(), request.effectiveTo());
        String status = enumValue(request.status(), SetValues.POLICY_STATUSES, "折扣策略状态");
        int changed = jdbc.update("""
                UPDATE discount_policy SET display_name=:name, discount_value=:value,
                    maximum_amount=:maximum, effective_from=:effectiveFrom, effective_to=:effectiveTo,
                    approval_required=:approval, status=:status, version=version+1, updated_at=:now
                WHERE id=:id AND community_id=:communityId AND version=:version
                """, new MapSqlParameterSource("name", request.displayName().trim())
                .addValue("value", request.discountValue()).addValue("maximum", request.maximumAmount())
                .addValue("effectiveFrom", request.effectiveFrom()).addValue("effectiveTo", request.effectiveTo())
                .addValue("approval", request.approvalRequired()).addValue("status", status)
                .addValue("now", now()).addValue("id", id).addValue("communityId", communityId)
                .addValue("version", request.expectedVersion()));
        if (changed != 1) throw conflict("折扣策略已被其他操作更新");
        audit.success(communityId, "discount-policy:update", "discount-policy", id, Map.of("status", status));
        return required("SELECT * FROM discount_policy WHERE id=:id", Map.of("id", id), "折扣策略不存在");
    }

    public List<Map<String, Object>> adjustments(String communityId, String status, String billId) {
        readFinance(communityId);
        return jdbc.queryForList("""
                SELECT ba.*, b.bill_no, a.code asset_code, a.display_name asset_name,
                       dp.policy_code, dp.display_name policy_name,
                       requester.display_name requested_by_name, approver.display_name approved_by_name
                FROM bill_adjustment ba JOIN bill b ON b.id=ba.bill_id JOIN asset a ON a.id=b.asset_id
                LEFT JOIN discount_policy dp ON dp.id=ba.discount_policy_id
                JOIN sys_user requester ON requester.id=ba.requested_by
                LEFT JOIN sys_user approver ON approver.id=ba.approved_by
                WHERE ba.community_id=:communityId AND (:status='' OR ba.status=:status)
                  AND (:billId='' OR ba.bill_id=:billId)
                ORDER BY ba.created_at DESC LIMIT 500
                """, Map.of("communityId", communityId, "status", text(status), "billId", text(billId)));
    }

    @Transactional
    public Map<String, Object> createAdjustment(FinancialModels.CreateAdjustment request, String idempotencyKey) {
        security.requirePermission("finance:adjust");
        security.requireProject(request.communityId());
        String key = requireKey(idempotencyKey);
        String type = enumValue(request.adjustmentType(), SetValues.ADJUSTMENT_TYPES, "调账类型");
        String requestJson = json(Map.of(
                "communityId", request.communityId(), "billId", request.billId(),
                "discountPolicyId", request.discountPolicyId() == null ? "" : request.discountPolicyId(),
                "adjustmentType", type, "amount", request.amount() == null ? BigDecimal.ZERO : request.amount(),
                "reason", request.reason()));
        String requestHash = sha256(requestJson);
        List<Map<String, Object>> replay = jdbc.queryForList("""
                SELECT * FROM bill_adjustment WHERE community_id=:communityId AND request_key=:key
                """, Map.of("communityId", request.communityId(), "key", key));
        if (!replay.isEmpty()) {
            requireSameRequest(replay.get(0).get("request_hash"), requestHash);
            return replay.get(0);
        }
        Map<String, Object> bill = lockBill(request.communityId(), request.billId());
        if (booleanValue(bill.get("locked"))) throw conflict("账单已锁定，不能调账");
        BigDecimal signedAmount = adjustmentAmount(type, request.amount(), request.discountPolicyId(), bill);
        BigDecimal totalAfter = decimal(bill.get("total_amount")).add(signedAmount);
        if (totalAfter.signum() < 0 || totalAfter.compareTo(decimal(bill.get("paid_amount"))) < 0) {
            throw conflict("调账后应收不能小于已收或小于 0");
        }
        String id = UUID.randomUUID().toString();
        LocalDateTime now = now();
        String before = json(Map.of("originalAmount", bill.get("original_amount"),
                "adjustmentAmount", bill.get("adjustment_amount"), "totalAmount", bill.get("total_amount"),
                "paidAmount", bill.get("paid_amount"), "outstandingAmount", bill.get("outstanding_amount"),
                "version", bill.get("version")));
        jdbc.update("""
                INSERT INTO bill_adjustment
                    (id, community_id, bill_id, discount_policy_id, adjustment_no, adjustment_type,
                     amount, status, reason, request_key, request_hash, request_json,
                     before_snapshot, requested_by, version, created_at, updated_at)
                VALUES (:id, :communityId, :billId, :policyId, :number, :type,
                        :amount, 'PENDING', :reason, :key, :requestHash, :requestJson,
                        :before, :userId, 0, :now, :now)
                """, new MapSqlParameterSource("id", id).addValue("communityId", request.communityId())
                .addValue("billId", request.billId()).addValue("policyId", request.discountPolicyId())
                .addValue("number", "ADJ-" + id).addValue("type", type).addValue("amount", signedAmount)
                .addValue("reason", request.reason()).addValue("key", key).addValue("requestHash", requestHash)
                .addValue("requestJson", requestJson).addValue("before", before)
                .addValue("userId", security.requirePrincipal().userId()).addValue("now", now));
        audit.success(request.communityId(), "bill-adjustment:request", "bill-adjustment", id,
                Map.of("billId", request.billId(), "type", type, "amount", signedAmount));
        event(request.communityId(), "BILL", request.billId(), "ADJUSTMENT_REQUESTED",
                "adjustment-create:" + key, Map.of("adjustmentId", id, "type", type, "amount", signedAmount));
        return required("SELECT * FROM bill_adjustment WHERE id=:id", Map.of("id", id), "调账申请创建失败");
    }

    @Transactional
    public Map<String, Object> approveAdjustment(String id, FinancialModels.AdjustmentDecision request) {
        security.requirePermission("finance:adjust");
        security.requireProject(request.communityId());
        Map<String, Object> adjustment = lockAdjustment(id, request.communityId());
        if ("APPLIED".equals(adjustment.get("status"))) return adjustment;
        if (!"PENDING".equals(adjustment.get("status"))) throw conflict("只有待审批调账可以通过");
        if (((Number) adjustment.get("version")).longValue() != request.expectedVersion()) {
            throw conflict("调账申请已被其他操作更新");
        }
        Map<String, Object> bill = lockBill(request.communityId(), String.valueOf(adjustment.get("bill_id")));
        if (booleanValue(bill.get("locked"))) throw conflict("账单已锁定，不能应用调账");
        BigDecimal amount = decimal(adjustment.get("amount"));
        BigDecimal totalAfter = decimal(bill.get("total_amount")).add(amount);
        BigDecimal paid = decimal(bill.get("paid_amount"));
        if (totalAfter.signum() < 0 || totalAfter.compareTo(paid) < 0) throw conflict("调账后应收不能小于已收或小于 0");
        BigDecimal adjustmentAfter = decimal(bill.get("adjustment_amount")).add(amount);
        BigDecimal outstandingAfter = totalAfter.subtract(paid);
        String billStatus = "VOID".equals(adjustment.get("adjustment_type")) ? "VOID"
                : outstandingAfter.signum() == 0 ? "PAID" : paid.signum() == 0 ? "UNPAID" : "PARTIAL";
        LocalDateTime now = now();
        int billChanged = jdbc.update("""
                UPDATE bill SET adjustment_amount=:adjustment, total_amount=:total,
                    outstanding_amount=:outstanding, status=:status, version=version+1, updated_at=:now
                WHERE id=:id AND version=:version
                """, Map.of("adjustment", adjustmentAfter, "total", totalAfter,
                "outstanding", outstandingAfter, "status", billStatus, "now", now,
                "id", bill.get("id"), "version", bill.get("version")));
        if (billChanged != 1) throw conflict("账单已被其他财务操作更新");
        String after = json(Map.of("originalAmount", bill.get("original_amount"),
                "adjustmentAmount", adjustmentAfter, "totalAmount", totalAfter,
                "paidAmount", paid, "outstandingAmount", outstandingAfter, "status", billStatus));
        int changed = jdbc.update("""
                UPDATE bill_adjustment SET status='APPLIED', after_snapshot=:after,
                    approved_by=:userId, approved_at=:now, applied_at=:now,
                    version=version+1, updated_at=:now
                WHERE id=:id AND status='PENDING' AND version=:version
                """, Map.of("after", after, "userId", security.requirePrincipal().userId(), "now", now,
                "id", id, "version", request.expectedVersion()));
        if (changed != 1) throw conflict("调账申请已被其他操作更新");
        audit.success(request.communityId(), "bill-adjustment:approve", "bill-adjustment", id,
                Map.of("billId", bill.get("id"), "amount", amount));
        event(request.communityId(), "BILL", String.valueOf(bill.get("id")), "ADJUSTMENT_APPLIED",
                "adjustment-approve:" + id, Map.of("adjustmentId", id, "amount", amount,
                        "totalAfter", totalAfter, "outstandingAfter", outstandingAfter));
        return required("SELECT * FROM bill_adjustment WHERE id=:id", Map.of("id", id), "调账申请不存在");
    }

    @Transactional
    public Map<String, Object> rejectAdjustment(String id, FinancialModels.AdjustmentDecision request) {
        security.requirePermission("finance:adjust");
        security.requireProject(request.communityId());
        Map<String, Object> current = lockAdjustment(id, request.communityId());
        if ("REJECTED".equals(current.get("status"))) return current;
        if (!"PENDING".equals(current.get("status"))) throw conflict("只有待审批调账可以驳回");
        int changed = jdbc.update("""
                UPDATE bill_adjustment SET status='REJECTED', approved_by=:userId,
                    rejected_at=:now, version=version+1, updated_at=:now
                WHERE id=:id AND version=:version AND status='PENDING'
                """, Map.of("userId", security.requirePrincipal().userId(), "now", now(),
                "id", id, "version", request.expectedVersion()));
        if (changed != 1) throw conflict("调账申请已被其他操作更新");
        audit.success(request.communityId(), "bill-adjustment:reject", "bill-adjustment", id,
                Map.of("reason", request.reason() == null ? "" : request.reason()));
        event(request.communityId(), "BILL", String.valueOf(current.get("bill_id")), "ADJUSTMENT_REJECTED",
                "adjustment-reject:" + id, Map.of("adjustmentId", id,
                        "reason", request.reason() == null ? "" : request.reason()));
        return required("SELECT * FROM bill_adjustment WHERE id=:id", Map.of("id", id), "调账申请不存在");
    }

    private Map<String, Object> lockBill(String communityId, String billId) {
        return required("""
                SELECT * FROM bill WHERE id=:id AND community_id=:communityId FOR UPDATE
                """, Map.of("id", billId, "communityId", communityId), "账单不存在");
    }

    private Map<String, Object> lockAdjustment(String id, String communityId) {
        return required("""
                SELECT * FROM bill_adjustment WHERE id=:id AND community_id=:communityId FOR UPDATE
                """, Map.of("id", id, "communityId", communityId), "调账申请不存在");
    }

    private BigDecimal adjustmentAmount(String type, BigDecimal requestedAmount,
                                        String policyId, Map<String, Object> bill) {
        BigDecimal outstanding = decimal(bill.get("outstanding_amount"));
        if ("VOID".equals(type)) {
            if (decimal(bill.get("paid_amount")).signum() != 0) throw conflict("已有收款的账单不能直接作废");
            return decimal(bill.get("total_amount")).negate();
        }
        if ("DISCOUNT".equals(type) && policyId != null && !policyId.isBlank()) {
            Map<String, Object> policy = required("""
                    SELECT * FROM discount_policy WHERE id=:id AND community_id=:communityId
                      AND status='ACTIVE' AND effective_from<=CURRENT_DATE
                      AND (effective_to IS NULL OR effective_to>=CURRENT_DATE)
                    """, Map.of("id", policyId, "communityId", bill.get("community_id")), "折扣策略不存在或未生效");
            BigDecimal amount = "PERCENT".equals(policy.get("discount_type"))
                    ? outstanding.multiply(decimal(policy.get("discount_value"))).setScale(2, RoundingMode.HALF_UP)
                    : decimal(policy.get("discount_value")).setScale(2, RoundingMode.HALF_UP);
            if (policy.get("maximum_amount") != null) amount = amount.min(decimal(policy.get("maximum_amount")));
            return amount.min(outstanding).negate();
        }
        if (requestedAmount == null || requestedAmount.signum() <= 0) throw invalid("调账金额必须大于 0");
        BigDecimal amount = requestedAmount.setScale(2, RoundingMode.HALF_UP);
        return "DEBIT".equals(type) ? amount : amount.negate();
    }

    private void validatePolicy(String type, BigDecimal value, BigDecimal maximum,
                                LocalDate from, LocalDate to) {
        String normalized = enumValue(type, SetValues.DISCOUNT_TYPES, "折扣类型");
        if (value == null || value.signum() <= 0) throw invalid("折扣值必须大于 0");
        if ("PERCENT".equals(normalized) && value.compareTo(BigDecimal.ONE) > 0) throw invalid("比例折扣不能超过 1");
        if (maximum != null && maximum.signum() < 0) throw invalid("折扣上限不能为负");
        if (to != null && to.isBefore(from)) throw invalid("折扣结束日不能早于开始日");
    }

    private void readFinance(String communityId) {
        security.requirePermission("finance:read");
        security.requireProject(communityId);
    }

    private void readFee(String communityId) {
        security.requirePermission("fee:read");
        security.requireProject(communityId);
    }

    private void writeFee(String communityId) {
        security.requirePermission("fee:discount-write");
        security.requireProject(communityId);
    }

    private Map<String, Object> required(String sql, Map<String, ?> params, String message) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, params);
        if (rows.isEmpty()) throw notFound(message);
        return rows.get(0);
    }

    private String enumValue(String value, List<String> allowed, String label) {
        String normalized = text(value).toUpperCase();
        if (!allowed.contains(normalized)) throw invalid(label + "不受支持");
        return normalized;
    }

    private String requireKey(String key) {
        if (key == null || key.isBlank()) throw invalid("财务写操作必须提供 Idempotency-Key");
        String normalized = key.trim();
        if (normalized.length() > 80) throw invalid("财务 Idempotency-Key 最长为 80 个字符");
        return normalized;
    }

    private void requireSameRequest(Object storedHash, String requestHash) {
        if (storedHash == null || !MessageDigest.isEqual(
                String.valueOf(storedHash).getBytes(StandardCharsets.UTF_8),
                requestHash.getBytes(StandardCharsets.UTF_8))) {
            throw conflict("同一 Idempotency-Key 不能用于不同财务请求");
        }
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }

    private BigDecimal decimal(Object value) {
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(String.valueOf(value));
    }

    private boolean booleanValue(Object value) {
        return value instanceof Boolean bool ? bool : value instanceof Number number && number.intValue() != 0;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize financial evidence", exception);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void event(String communityId, String aggregateType, String aggregateId,
                       String eventType, String requestKey, Object detail) {
        jdbc.update("""
                INSERT INTO financial_event
                    (id, community_id, aggregate_type, aggregate_id, event_type,
                     request_key, detail_json, actor_user_id, created_at)
                VALUES (:id, :communityId, :aggregateType, :aggregateId, :eventType,
                        :requestKey, :detail, :userId, :now)
                """, new MapSqlParameterSource("id", UUID.randomUUID().toString())
                .addValue("communityId", communityId).addValue("aggregateType", aggregateType)
                .addValue("aggregateId", aggregateId).addValue("eventType", eventType)
                .addValue("requestKey", requestKey).addValue("detail", json(detail))
                .addValue("userId", security.requirePrincipal().userId()).addValue("now", now()));
    }

    private BusinessException invalid(String message) {
        return new BusinessException("INVALID_FINANCIAL_OPERATION", message, HttpStatus.BAD_REQUEST);
    }

    private BusinessException conflict(String message) {
        return new BusinessException("FINANCIAL_CONFLICT", message, HttpStatus.CONFLICT);
    }

    private BusinessException notFound(String message) {
        return new BusinessException("FINANCIAL_RECORD_NOT_FOUND", message, HttpStatus.NOT_FOUND);
    }

    private static final class SetValues {
        private static final List<String> POLICY_STATUSES = List.of("ACTIVE", "INACTIVE");
        private static final List<String> DISCOUNT_TYPES = List.of("PERCENT", "FIXED");
        private static final List<String> ADJUSTMENT_TYPES = List.of("DISCOUNT", "WAIVER", "CREDIT", "DEBIT", "VOID");
        private SetValues() {}
    }
}
