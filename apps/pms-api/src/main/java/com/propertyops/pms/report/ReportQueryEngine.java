package com.propertyops.pms.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.propertyops.pms.common.api.BusinessException;
import com.propertyops.pms.security.SecurityContextService;

@Service
public class ReportQueryEngine {
    public static final Set<String> CODES = Set.of(
            "TRANSACTION_SUMMARY", "TRANSACTION_DETAILS", "RECEIPT_BATCH_PRINT", "PAYMENTS",
            "ARREARS", "BILL_NOTIFICATIONS", "BILLS", "COLLECTION_RATE",
            "ARREARS_CLEARANCE_RATE", "COMPREHENSIVE_QUERY", "COLLECTION_CLEARANCE_SUMMARY",
            "CHARGE_DETAILS", "DISCOUNT_DETAILS", "PREPAYMENTS", "OWNERSHIP_TRANSFERS",
            "REMINDERS", "FEE_STATUS", "INVOICE_STATISTICS", "DEPOSITS",
            "DAILY_SETTLEMENT_DETAILS", "ADJUSTMENTS", "BANK_TRUST");

    private final NamedParameterJdbcTemplate jdbc;
    private final SecurityContextService security;
    private final ObjectMapper objectMapper;

    public ReportQueryEngine(NamedParameterJdbcTemplate jdbc, SecurityContextService security,
                             ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.security = security;
        this.objectMapper = objectMapper;
    }

    public List<Map<String, Object>> catalog(String communityId) {
        security.requireAnyPermission("finance:read", "report:read", "notification:read", "property:read", "invoice:read", "bank:read");
        security.requireProject(communityId);
        return jdbc.queryForList("""
                SELECT report_code, page_path, title, row_grain, formula_note, formula_json,
                       columns_json, fixed_sample_json, status, version, updated_at
                FROM report_definition WHERE status='ACTIVE' ORDER BY id
                """, Map.of());
    }

    public Map<String, Object> execute(String communityId, String reportCode, Map<String, String> filters,
                                       List<String> selectedColumns, int page, int size) {
        String code = normalizeCode(reportCode);
        security.requirePermission(readPermission(code));
        security.requireProject(communityId);
        return executeTrusted(communityId, code, filters, selectedColumns, page, Math.min(size, 500));
    }

    Map<String, Object> executeTrusted(String communityId, String reportCode, Map<String, String> filters,
                                       List<String> selectedColumns, int page, int size) {
        long started = System.nanoTime();
        String code = normalizeCode(reportCode);
        Map<String, Object> definition = definition(code);
        List<String> allowedColumns = parseList(definition.get("columns_json"));
        List<String> projected = selectedColumns == null || selectedColumns.isEmpty()
                ? allowedColumns : validateColumns(selectedColumns, allowedColumns);
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, 10_000));
        MapSqlParameterSource params = parameters(communityId, filters == null ? Map.of() : filters);
        List<Map<String, Object>> allRows = query(code, params);
        List<Map<String, Object>> projectedRows = allRows.stream().map(row -> project(row, projected)).toList();
        int start = Math.min((safePage - 1) * safeSize, projectedRows.size());
        int end = Math.min(start + safeSize, projectedRows.size());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("reportCode", code);
        response.put("title", definition.get("title"));
        response.put("rowGrain", definition.get("row_grain"));
        response.put("formulaNote", definition.get("formula_note"));
        response.put("formula", parseMap(definition.get("formula_json")));
        response.put("fixedSample", parseMap(definition.get("fixed_sample_json")));
        response.put("columns", projected);
        response.put("summary", summarize(projectedRows));
        response.put("rows", projectedRows.subList(start, end));
        response.put("page", safePage);
        response.put("size", safeSize);
        response.put("total", projectedRows.size());
        response.put("queryChecksum", sha256(json(Map.of(
                "communityId", communityId, "reportCode", code,
                "filters", filters == null ? Map.of() : new java.util.TreeMap<>(filters),
                "columns", projected))));
        response.put("durationMs", (System.nanoTime() - started) / 1_000_000L);
        response.put("drillDown", drillDown(code));
        response.put("syntheticEnvironment", true);
        response.put("integrationMode", integrationMode(code));
        response.put("productionConnected", !Set.of(
                "BILL_NOTIFICATIONS", "REMINDERS", "INVOICE_STATISTICS", "BANK_TRUST").contains(code));
        return response;
    }

    public Map<String, Object> dashboardFinance(String communityId) {
        Map<String, Object> result = executeTrusted(communityId, "COLLECTION_RATE", Map.of(), List.of(), 1, 10);
        @SuppressWarnings("unchecked") List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("rows");
        if (rows.isEmpty()) return Map.of("receivable", BigDecimal.ZERO, "received", BigDecimal.ZERO,
                "outstanding", BigDecimal.ZERO, "bill_count", 0, "collection_rate", BigDecimal.ZERO,
                "metricSource", "COLLECTION_RATE");
        Map<String, Object> row = rows.get(0);
        Map<String, Object> finance = new LinkedHashMap<>();
        finance.put("receivable", row.get("receivableAmount"));
        finance.put("received", row.get("collectedAmount"));
        finance.put("outstanding", row.get("outstandingAmount"));
        finance.put("bill_count", jdbc.queryForObject("SELECT COUNT(*) FROM bill WHERE community_id=:communityId",
                Map.of("communityId", communityId), Long.class));
        finance.put("collection_rate", row.get("collectionRate"));
        finance.put("metricSource", "COLLECTION_RATE");
        return finance;
    }

    private List<Map<String, Object>> query(String code, MapSqlParameterSource p) {
        return switch (code) {
            case "TRANSACTION_SUMMARY" -> sql("""
                    SELECT pt.payment_channel groupLabel, COUNT(*) transactionCount,
                           COALESCE(SUM(CASE WHEN pt.transaction_type='PAYMENT' THEN pt.amount ELSE 0 END),0) receivableAmount,
                           COALESCE(SUM(CASE WHEN pt.transaction_type='PAYMENT' THEN pt.amount ELSE 0 END),0) collectedAmount,
                           ABS(COALESCE(SUM(CASE WHEN pt.transaction_type IN ('REVERSAL','REFUND') THEN pt.amount ELSE 0 END),0)) refundAmount,
                           COALESCE(SUM(pt.amount),0) netAmount
                    FROM payment_transaction pt
                    WHERE pt.community_id=:communityId AND pt.status='SUCCESS' AND pt.occurred_at>=:fromAt AND pt.occurred_at<:toAt
                      AND (:status='' OR pt.payment_channel=:status)
                    GROUP BY pt.payment_channel ORDER BY pt.payment_channel
                    """, p);
            case "TRANSACTION_DETAILS" -> sql("""
                    SELECT pt.transaction_no transactionNo, pt.occurred_at paidAt,
                           %s customerName, COALESCE(MIN(a.display_name),'—') assetName,
                           pt.payment_channel paymentChannel, pt.amount amount, pt.status status
                    FROM payment_transaction pt
                    LEFT JOIN payment_allocation pa ON pa.payment_transaction_id=pt.id
                    LEFT JOIN bill b ON b.id=pa.bill_id LEFT JOIN customer c ON c.id=b.customer_id LEFT JOIN asset a ON a.id=b.asset_id
                    WHERE pt.community_id=:communityId AND pt.occurred_at>=:fromAt AND pt.occurred_at<:toAt
                      AND (:status='' OR pt.status=:status OR pt.payment_channel=:status)
                      AND (:keyword='' OR pt.transaction_no LIKE :likeKeyword OR a.code LIKE :likeKeyword)
                    GROUP BY pt.id, pt.transaction_no, pt.occurred_at, pt.payment_channel, pt.amount, pt.status
                    ORDER BY pt.occurred_at DESC LIMIT 10000
                    """.formatted(masked("MIN(c.display_name)")), p);
            case "RECEIPT_BATCH_PRINT" -> sql("""
                    SELECT r.receipt_no receiptNo, r.id receiptId, r.issued_at paidAt,
                           COALESCE(MIN(a.display_name),'—') assetName, %s customerName,
                           COALESCE(po.confirmed_amount,po.requested_amount) amount, r.print_count printCount, r.status status
                    FROM receipt r JOIN payment_order po ON po.id=r.payment_order_id
                    LEFT JOIN payment_transaction pt ON pt.payment_order_id=po.id AND pt.transaction_type='PAYMENT'
                    LEFT JOIN payment_allocation pa ON pa.payment_transaction_id=pt.id
                    LEFT JOIN bill b ON b.id=pa.bill_id LEFT JOIN customer c ON c.id=b.customer_id LEFT JOIN asset a ON a.id=b.asset_id
                    WHERE r.community_id=:communityId AND r.issued_at>=:fromAt AND r.issued_at<:toAt
                      AND (:status='' OR r.status=:status) AND (:keyword='' OR r.receipt_no LIKE :likeKeyword OR a.code LIKE :likeKeyword)
                    GROUP BY r.id,r.receipt_no,r.issued_at,po.confirmed_amount,po.requested_amount,r.print_count,r.status
                    ORDER BY r.issued_at DESC LIMIT 10000
                    """.formatted(masked("MIN(c.display_name)")), p);
            case "PAYMENTS" -> sql("""
                    SELECT pt.transaction_no transactionNo, po.order_no paymentOrderNo, pt.occurred_at paidAt,
                           %s payerName, pt.amount amount, pt.payment_channel channel, pt.status status
                    FROM payment_transaction pt JOIN payment_order po ON po.id=pt.payment_order_id
                    LEFT JOIN payment_allocation pa ON pa.payment_transaction_id=pt.id LEFT JOIN bill b ON b.id=pa.bill_id
                    LEFT JOIN customer c ON c.id=b.customer_id LEFT JOIN asset a ON a.id=b.asset_id
                    WHERE pt.community_id=:communityId AND pt.occurred_at>=:fromAt AND pt.occurred_at<:toAt
                      AND (:status='' OR pt.status=:status) AND (:keyword='' OR pt.transaction_no LIKE :likeKeyword OR po.order_no LIKE :likeKeyword OR a.code LIKE :likeKeyword)
                    GROUP BY pt.id,pt.transaction_no,po.order_no,pt.occurred_at,pt.amount,pt.payment_channel,pt.status
                    ORDER BY pt.occurred_at DESC LIMIT 10000
                    """.formatted(masked("MIN(c.display_name)")), p);
            case "ARREARS" -> sql("""
                    SELECT b.id billId, a.display_name assetName, %s customerName,
                           COALESCE(GROUP_CONCAT(DISTINCT bi.item_name_snapshot ORDER BY bi.item_name_snapshot SEPARATOR '、'),'—') feeName,
                           b.billing_period billingPeriod, b.total_amount receivableAmount, b.outstanding_amount arrearsAmount,
                           GREATEST(DATEDIFF(:asOfDate,b.due_date),0) arrearsDays
                    FROM bill b JOIN asset a ON a.id=b.asset_id LEFT JOIN customer c ON c.id=b.customer_id LEFT JOIN bill_item bi ON bi.bill_id=b.id
                    WHERE b.community_id=:communityId AND b.outstanding_amount>0 AND b.status NOT IN ('VOID','VOIDED')
                      AND b.billing_period>=:periodFrom AND b.billing_period<=:periodTo
                      AND (:keyword='' OR b.bill_no LIKE :likeKeyword OR a.code LIKE :likeKeyword OR c.display_name LIKE :likeKeyword)
                    GROUP BY b.id,a.display_name,c.display_name,b.billing_period,b.total_amount,b.outstanding_amount,b.due_date
                    ORDER BY b.due_date,b.bill_no LIMIT 10000
                    """.formatted(masked("c.display_name")), p);
            case "BILL_NOTIFICATIONS" -> sql("""
                    SELECT %s customerName, a.display_name assetName, b.billing_period billingPeriod, b.total_amount billAmount,
                           nb.channel channel, nm.status deliveryStatus, nm.sent_at sentAt
                    FROM notification_message nm JOIN notification_batch nb ON nb.id=nm.batch_id JOIN bill b ON b.id=nm.bill_id
                    JOIN asset a ON a.id=b.asset_id LEFT JOIN customer c ON c.id=b.customer_id
                    WHERE nb.community_id=:communityId AND b.billing_period>=:periodFrom AND b.billing_period<=:periodTo
                      AND (:status='' OR nm.status=:status OR nb.channel=:status)
                    ORDER BY nm.created_at DESC LIMIT 10000
                    """.formatted(masked("c.display_name")), p);
            case "BILLS" -> sql("""
                    SELECT b.id billId,b.bill_no billNo,a.display_name assetName,%s customerName,
                           COALESCE(GROUP_CONCAT(DISTINCT bi.item_name_snapshot ORDER BY bi.item_name_snapshot SEPARATOR '、'),'—') feeName,
                           b.total_amount amount,b.outstanding_amount outstandingAmount,b.status status,b.version version
                    FROM bill b JOIN asset a ON a.id=b.asset_id LEFT JOIN customer c ON c.id=b.customer_id LEFT JOIN bill_item bi ON bi.bill_id=b.id
                    WHERE b.community_id=:communityId AND b.billing_period>=:periodFrom AND b.billing_period<=:periodTo
                      AND (:status='' OR b.status=:status) AND (:keyword='' OR b.bill_no LIKE :likeKeyword OR a.code LIKE :likeKeyword OR c.display_name LIKE :likeKeyword)
                    GROUP BY b.id,b.bill_no,a.display_name,c.display_name,b.total_amount,b.outstanding_amount,b.status,b.version
                    ORDER BY b.created_at DESC LIMIT 10000
                    """.formatted(masked("c.display_name")), p);
            case "COLLECTION_RATE" -> sql("""
                    SELECT cm.name scopeName,COALESCE(SUM(b.total_amount),0) receivableAmount,
                           COALESCE(SUM(b.paid_amount),0) collectedAmount,COALESCE(SUM(b.outstanding_amount),0) outstandingAmount,
                           CASE WHEN COALESCE(SUM(b.total_amount),0)=0 THEN 0 ELSE ROUND(SUM(b.paid_amount)/SUM(b.total_amount)*100,2) END collectionRate
                    FROM community cm LEFT JOIN bill b ON b.community_id=cm.id AND b.billing_period>=:periodFrom AND b.billing_period<=:periodTo
                    WHERE cm.id=:communityId GROUP BY cm.id,cm.name
                    """, p);
            case "ARREARS_CLEARANCE_RATE" -> sql("""
                    SELECT cm.name scopeName,
                           COALESCE(SUM(CASE WHEN b.due_date<:fromDate THEN b.total_amount ELSE 0 END),0) openingArrears,
                           COALESCE(SUM(CASE WHEN b.due_date<:fromDate THEN b.paid_amount ELSE 0 END),0) clearedAmount,
                           COALESCE(SUM(CASE WHEN b.due_date<:fromDate THEN b.outstanding_amount ELSE 0 END),0) closingArrears,
                           CASE WHEN COALESCE(SUM(CASE WHEN b.due_date<:fromDate THEN b.total_amount ELSE 0 END),0)=0 THEN 0
                                ELSE ROUND(SUM(CASE WHEN b.due_date<:fromDate THEN b.paid_amount ELSE 0 END)/SUM(CASE WHEN b.due_date<:fromDate THEN b.total_amount ELSE 0 END)*100,2) END clearanceRate
                    FROM community cm LEFT JOIN bill b ON b.community_id=cm.id WHERE cm.id=:communityId GROUP BY cm.id,cm.name
                    """, p);
            case "COMPREHENSIVE_QUERY" -> comprehensive(p);
            case "COLLECTION_CLEARANCE_SUMMARY" -> sql("""
                    SELECT cm.name scopeName,COALESCE(SUM(CASE WHEN b.billing_period>=:periodFrom THEN b.total_amount ELSE 0 END),0) currentReceivable,
                           COALESCE(SUM(CASE WHEN b.billing_period>=:periodFrom THEN b.paid_amount ELSE 0 END),0) currentCollected,
                           COALESCE(SUM(CASE WHEN b.billing_period<:periodFrom THEN b.total_amount ELSE 0 END),0) historicalArrears,
                           COALESCE(SUM(CASE WHEN b.billing_period<:periodFrom THEN b.paid_amount ELSE 0 END),0) arrearsCleared,
                           CASE WHEN COALESCE(SUM(CASE WHEN b.billing_period>=:periodFrom THEN b.total_amount ELSE 0 END),0)=0 THEN 0 ELSE ROUND(SUM(CASE WHEN b.billing_period>=:periodFrom THEN b.paid_amount ELSE 0 END)/SUM(CASE WHEN b.billing_period>=:periodFrom THEN b.total_amount ELSE 0 END)*100,2) END collectionRate,
                           CASE WHEN COALESCE(SUM(CASE WHEN b.billing_period<:periodFrom THEN b.total_amount ELSE 0 END),0)=0 THEN 0 ELSE ROUND(SUM(CASE WHEN b.billing_period<:periodFrom THEN b.paid_amount ELSE 0 END)/SUM(CASE WHEN b.billing_period<:periodFrom THEN b.total_amount ELSE 0 END)*100,2) END clearanceRate
                    FROM community cm LEFT JOIN bill b ON b.community_id=cm.id AND b.billing_period<=:periodTo WHERE cm.id=:communityId GROUP BY cm.id,cm.name
                    """, p);
            case "CHARGE_DETAILS" -> sql("""
                    SELECT pt.occurred_at paidAt,MIN(r.receipt_no) receiptNo,a.display_name assetName,%s customerName,
                           COALESCE(GROUP_CONCAT(DISTINCT bi.item_name_snapshot ORDER BY bi.item_name_snapshot SEPARATOR '、'),'—') feeName,
                           b.billing_period billingPeriod,pa.allocated_amount amount,COALESCE(u.display_name,'—') cashierName
                    FROM payment_allocation pa JOIN payment_transaction pt ON pt.id=pa.payment_transaction_id JOIN bill b ON b.id=pa.bill_id
                    JOIN asset a ON a.id=b.asset_id LEFT JOIN customer c ON c.id=b.customer_id LEFT JOIN bill_item bi ON bi.bill_id=b.id
                    LEFT JOIN receipt r ON r.payment_order_id=pt.payment_order_id LEFT JOIN cashier_shift cs ON cs.id=pt.cashier_shift_id LEFT JOIN sys_user u ON u.id=cs.cashier_user_id
                    WHERE pt.community_id=:communityId AND pt.status='SUCCESS' AND pt.occurred_at>=:fromAt AND pt.occurred_at<:toAt
                    GROUP BY pa.id,pt.occurred_at,a.display_name,c.display_name,b.billing_period,pa.allocated_amount,u.display_name
                    ORDER BY pt.occurred_at DESC LIMIT 10000
                    """.formatted(masked("c.display_name")), p);
            case "DISCOUNT_DETAILS" -> sql("""
                    SELECT ba.adjustment_no discountNo,a.display_name assetName,%s customerName,
                           COALESCE(MIN(bi.item_name_snapshot),'—') feeName,b.original_amount originalAmount,
                           ABS(ba.amount) discountAmount,b.total_amount finalAmount,ba.approved_at approvedAt
                    FROM bill_adjustment ba JOIN bill b ON b.id=ba.bill_id JOIN asset a ON a.id=b.asset_id
                    LEFT JOIN customer c ON c.id=b.customer_id LEFT JOIN bill_item bi ON bi.bill_id=b.id
                    WHERE ba.community_id=:communityId AND ba.adjustment_type IN ('DISCOUNT','WAIVER','CREDIT')
                      AND ba.created_at>=:fromAt AND ba.created_at<:toAt AND (:status='' OR ba.status=:status)
                    GROUP BY ba.id,ba.adjustment_no,a.display_name,c.display_name,b.original_amount,ba.amount,b.total_amount,ba.approved_at
                    ORDER BY ba.created_at DESC LIMIT 10000
                    """.formatted(masked("c.display_name")), p);
            case "PREPAYMENTS" -> sql("""
                    SELECT pt.occurred_at occurredAt,%s customerName,COALESCE(MIN(a.display_name),'—') assetName,
                           pt.transaction_type entryType,CASE WHEN pt.amount>0 THEN pt.amount ELSE 0 END creditAmount,
                           CASE WHEN pt.amount<0 THEN ABS(pt.amount) ELSE 0 END debitAmount,pt.balance_after balance,
                           COALESCE(pt.reference_id,'—') referenceNo
                    FROM prepayment_transaction pt JOIN prepayment_account pa ON pa.id=pt.account_id JOIN customer c ON c.id=pa.customer_id
                    LEFT JOIN customer_asset_relation car ON car.customer_id=c.id AND car.status='ACTIVE' LEFT JOIN asset a ON a.id=car.asset_id
                    WHERE pa.community_id=:communityId AND pt.occurred_at>=:fromAt AND pt.occurred_at<:toAt
                      AND (:status='' OR pt.transaction_type=:status) AND (:keyword='' OR c.display_name LIKE :likeKeyword OR a.code LIKE :likeKeyword)
                    GROUP BY pt.id,pt.occurred_at,c.display_name,pt.transaction_type,pt.amount,pt.balance_after,pt.reference_id
                    ORDER BY pt.occurred_at DESC LIMIT 10000
                    """.formatted(masked("c.display_name")), p);
            case "OWNERSHIP_TRANSFERS" -> sql("""
                    SELECT pre.id transferNo,a.display_name assetName,%s previousCustomerName,%s newCustomerName,
                           pre.effective_date effectiveDate,COALESCE(u.display_name,'—') operatorName,'COMPLETED' status
                    FROM property_relation_event pre JOIN asset a ON a.id=pre.asset_id
                    LEFT JOIN customer_asset_relation oldr ON oldr.id=pre.previous_relation_id LEFT JOIN customer oldc ON oldc.id=oldr.customer_id
                    LEFT JOIN customer_asset_relation newr ON newr.id=pre.new_relation_id LEFT JOIN customer newc ON newc.id=newr.customer_id
                    LEFT JOIN sys_user u ON u.id=pre.actor_user_id
                    WHERE pre.community_id=:communityId AND pre.event_type='OWNERSHIP_TRANSFERRED' AND pre.effective_date>=:fromDate AND pre.effective_date<=:toDate
                    ORDER BY pre.effective_date DESC LIMIT 10000
                    """.formatted(masked("oldc.display_name"), masked("newc.display_name")), p);
            case "REMINDERS" -> sql("""
                    SELECT nm.id reminderNo,'ARREARS' reminderType,%s customerName,a.display_name assetName,
                           nb.channel channel,nm.status deliveryStatus,nm.sent_at sentAt
                    FROM notification_message nm JOIN notification_batch nb ON nb.id=nm.batch_id JOIN bill b ON b.id=nm.bill_id
                    JOIN asset a ON a.id=b.asset_id LEFT JOIN customer c ON c.id=b.customer_id
                    WHERE nb.community_id=:communityId AND nm.created_at>=:fromAt AND nm.created_at<:toAt
                      AND (:status='' OR nm.status=:status OR nb.channel=:status)
                      AND (:keyword='' OR a.code LIKE :likeKeyword OR c.display_name LIKE :likeKeyword)
                    ORDER BY nm.created_at DESC LIMIT 10000
                    """.formatted(masked("c.display_name")), p);
            case "FEE_STATUS" -> sql("""
                    SELECT bi.item_name_snapshot feeName,COUNT(DISTINCT b.asset_id) receivableCount,SUM(bi.amount) receivableAmount,
                           COUNT(DISTINCT CASE WHEN b.paid_amount>0 THEN b.asset_id END) collectedCount,
                           SUM(CASE WHEN b.total_amount=0 THEN 0 ELSE bi.amount*b.paid_amount/b.total_amount END) collectedAmount,
                           SUM(CASE WHEN b.total_amount=0 THEN 0 ELSE bi.amount*b.outstanding_amount/b.total_amount END) outstandingAmount
                    FROM bill_item bi JOIN bill b ON b.id=bi.bill_id
                    WHERE b.community_id=:communityId AND b.billing_period>=:periodFrom AND b.billing_period<=:periodTo
                    GROUP BY bi.item_name_snapshot ORDER BY bi.item_name_snapshot LIMIT 10000
                    """, p);
            case "INVOICE_STATISTICS" -> sql("""
                    SELECT ir.adapter_code groupLabel,
                           SUM(CASE WHEN ir.operation_type IN ('ISSUE','REPLACE') THEN 1 ELSE 0 END) issuedCount,
                           COALESCE(SUM(CASE WHEN ir.operation_type IN ('ISSUE','REPLACE') THEN ir.amount ELSE 0 END),0) issuedAmount,
                           0 voidedCount,0 voidedAmount,SUM(CASE WHEN ir.operation_type='RED' THEN 1 ELSE 0 END) redCount,
                           ABS(COALESCE(SUM(CASE WHEN ir.operation_type='RED' THEN ir.amount ELSE 0 END),0)) redAmount
                    FROM invoice_request ir WHERE ir.community_id=:communityId AND ir.created_at>=:fromAt AND ir.created_at<:toAt
                    GROUP BY ir.adapter_code LIMIT 10000
                    """, p);
            case "DEPOSITS" -> sql("""
                    SELECT da.id depositNo,%s customerName,COALESCE(a.display_name,'—') assetName,da.deposit_type depositType,
                           COALESCE(SUM(CASE WHEN dt.amount>0 THEN dt.amount ELSE 0 END),0) receivedAmount,
                           ABS(COALESCE(SUM(CASE WHEN dt.amount<0 THEN dt.amount ELSE 0 END),0)) refundedAmount,
                           da.balance balance,da.status status
                    FROM deposit_account da JOIN customer c ON c.id=da.customer_id LEFT JOIN asset a ON a.id=da.asset_id
                    LEFT JOIN deposit_transaction dt ON dt.account_id=da.id AND dt.occurred_at>=:fromAt AND dt.occurred_at<:toAt
                    WHERE da.community_id=:communityId AND (:status='' OR da.status=:status)
                      AND (:keyword='' OR c.display_name LIKE :likeKeyword OR a.code LIKE :likeKeyword OR da.id LIKE :likeKeyword)
                    GROUP BY da.id,c.display_name,a.display_name,da.deposit_type,da.balance,da.status ORDER BY da.created_at DESC LIMIT 10000
                    """.formatted(masked("c.display_name")), p);
            case "DAILY_SETTLEMENT_DETAILS" -> sql("""
                    SELECT ds.id settlementNo,COALESCE(u.display_name,'—') cashierName,COALESCE(pt.payment_channel,'ALL') paymentChannel,
                           COUNT(pt.id) transactionCount,COALESCE(SUM(CASE WHEN pt.transaction_type='PAYMENT' THEN pt.amount ELSE 0 END),0) collectedAmount,
                           ABS(COALESCE(SUM(CASE WHEN pt.transaction_type IN ('REVERSAL','REFUND') THEN pt.amount ELSE 0 END),0)) refundedAmount,
                           COALESCE(SUM(pt.amount),0) netAmount
                    FROM daily_settlement ds LEFT JOIN payment_transaction pt ON pt.settlement_id=ds.id
                    LEFT JOIN cashier_shift cs ON cs.id=pt.cashier_shift_id LEFT JOIN sys_user u ON u.id=cs.cashier_user_id
                    WHERE ds.community_id=:communityId AND ds.settlement_date>=:fromDate AND ds.settlement_date<=:toDate
                    GROUP BY ds.id,u.display_name,pt.payment_channel,ds.settlement_date ORDER BY ds.settlement_date DESC LIMIT 10000
                    """, p);
            case "ADJUSTMENTS" -> sql("""
                    SELECT ba.adjustment_no adjustmentNo,b.bill_no billNo,ba.adjustment_type adjustmentType,
                           b.original_amount beforeAmount,ba.amount changeAmount,b.total_amount afterAmount,ba.status status,ba.created_at createdAt
                    FROM bill_adjustment ba JOIN bill b ON b.id=ba.bill_id LEFT JOIN customer c ON c.id=b.customer_id
                    WHERE ba.community_id=:communityId AND ba.created_at>=:fromAt AND ba.created_at<:toAt
                      AND (:status='' OR ba.status=:status OR ba.adjustment_type=:status)
                      AND (:keyword='' OR b.bill_no LIKE :likeKeyword OR c.display_name LIKE :likeKeyword)
                    ORDER BY ba.created_at DESC LIMIT 10000
                    """, p);
            case "BANK_TRUST" -> List.of(bankTrustRow());
            default -> throw invalid("REPORT_NOT_FOUND", "未知报表定义");
        };
    }

    private List<Map<String, Object>> comprehensive(MapSqlParameterSource p) {
        String subject = String.valueOf(p.getValue("subjectType"));
        List<Map<String, Object>> rows = new ArrayList<>();
        if (subject.isBlank() || "ALL".equals(subject) || "BILL".equals(subject)) rows.addAll(sql("""
                SELECT 'BILL' businessType,b.bill_no businessNo,b.created_at occurredAt,a.display_name assetName,
                       %s customerName,b.total_amount amount,b.status status
                FROM bill b JOIN asset a ON a.id=b.asset_id LEFT JOIN customer c ON c.id=b.customer_id
                WHERE b.community_id=:communityId AND b.created_at>=:fromAt AND b.created_at<:toAt
                  AND (:keyword='' OR b.bill_no LIKE :likeKeyword OR a.code LIKE :likeKeyword OR c.display_name LIKE :likeKeyword)
                ORDER BY b.created_at DESC LIMIT 10000
                """.formatted(masked("c.display_name")), p));
        if (subject.isBlank() || "ALL".equals(subject) || "PAYMENT".equals(subject)) rows.addAll(sql("""
                SELECT 'PAYMENT' businessType,pt.transaction_no businessNo,pt.occurred_at occurredAt,'—' assetName,
                       '*' customerName,pt.amount amount,pt.status status
                FROM payment_transaction pt WHERE pt.community_id=:communityId AND pt.occurred_at>=:fromAt AND pt.occurred_at<:toAt
                  AND (:keyword='' OR pt.transaction_no LIKE :likeKeyword) ORDER BY pt.occurred_at DESC LIMIT 10000
                """, p));
        rows.sort((left, right) -> String.valueOf(right.get("occurredAt")).compareTo(String.valueOf(left.get("occurredAt"))));
        return rows.size() > 10000 ? rows.subList(0, 10000) : rows;
    }

    private List<Map<String, Object>> sql(String text, MapSqlParameterSource parameters) {
        return jdbc.queryForList(text, parameters);
    }

    private MapSqlParameterSource parameters(String communityId, Map<String, String> filters) {
        LocalDate from = date(filters.get("from"), LocalDate.of(2000, 1, 1));
        LocalDate to = date(filters.get("to"), LocalDate.of(2100, 1, 1));
        if (to.isBefore(from)) throw invalid("REPORT_DATE_RANGE_INVALID", "结束日期不能早于开始日期");
        String periodFrom = period(filters.get("periodFrom"), "2000-01");
        String periodTo = period(filters.get("periodTo"), "2100-12");
        String keyword = text(filters.get("keyword"));
        return new MapSqlParameterSource("communityId", communityId)
                .addValue("fromDate", from).addValue("toDate", to)
                .addValue("fromAt", from.atStartOfDay()).addValue("toAt", to.plusDays(1).atStartOfDay())
                .addValue("asOfDate", date(filters.get("asOfDate"), to.equals(LocalDate.of(2100, 1, 1)) ? LocalDate.now() : to))
                .addValue("periodFrom", periodFrom).addValue("periodTo", periodTo)
                .addValue("keyword", keyword).addValue("likeKeyword", "%" + keyword + "%")
                .addValue("status", text(filters.get("status")))
                .addValue("subjectType", text(filters.get("subjectType")).toUpperCase(Locale.ROOT));
    }

    private Map<String, Object> definition(String code) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM report_definition WHERE report_code=:code AND status='ACTIVE'",
                Map.of("code", code));
        if (rows.isEmpty()) throw invalid("REPORT_NOT_FOUND", "未知或已停用的报表定义");
        return rows.get(0);
    }

    private String normalizeCode(String value) {
        String code = text(value).toUpperCase(Locale.ROOT);
        if (!CODES.contains(code)) throw invalid("REPORT_NOT_FOUND", "未知报表定义");
        return code;
    }

    private List<String> validateColumns(List<String> requested, List<String> allowed) {
        LinkedHashSet<String> unique = new LinkedHashSet<>(requested);
        if (unique.isEmpty() || !allowed.containsAll(unique)) {
            throw invalid("REPORT_COLUMN_NOT_ALLOWED", "所选列不在报表白名单中");
        }
        return List.copyOf(unique);
    }

    private Map<String, Object> project(Map<String, Object> source, List<String> columns) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String column : columns) result.put(column, source.get(column));
        return result;
    }

    private Map<String, Object> summarize(List<Map<String, Object>> rows) {
        Map<String, BigDecimal> sums = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) for (var entry : row.entrySet()) {
            if (entry.getValue() instanceof Number number && !entry.getKey().toLowerCase(Locale.ROOT).contains("rate")
                    && !entry.getKey().toLowerCase(Locale.ROOT).contains("count")) {
                sums.merge(entry.getKey(), new BigDecimal(number.toString()), BigDecimal::add);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rowCount", rows.size());
        result.put("numericTotals", sums);
        return result;
    }

    private Map<String, Object> drillDown(String code) {
        return switch (code) {
            case "TRANSACTION_SUMMARY", "DAILY_SETTLEMENT_DETAILS" -> Map.of("reportCode", "TRANSACTION_DETAILS", "key", "transactionNo");
            case "COLLECTION_RATE", "ARREARS_CLEARANCE_RATE", "COLLECTION_CLEARANCE_SUMMARY", "FEE_STATUS" -> Map.of("reportCode", "ARREARS", "key", "billId");
            default -> Map.of("reportCode", code, "key", "businessNo");
        };
    }

    private Map<String, Object> bankTrustRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("trustNo", "SIMULATOR-NOT-SUBMITTED"); row.put("bankChannel", "BANK_TRUST_SIMULATOR");
        row.put("submittedCount", 0); row.put("submittedAmount", BigDecimal.ZERO);
        row.put("successCount", 0); row.put("successAmount", BigDecimal.ZERO);
        row.put("reconcileStatus", "SIMULATOR_ONLY");
        return row;
    }

    private String masked(String expression) {
        return "CASE WHEN " + expression + " IS NULL THEN '*' WHEN CHAR_LENGTH(" + expression
                + ")<=1 THEN '*' ELSE CONCAT(LEFT(" + expression + ",1),'*') END";
    }

    private List<String> parseList(Object value) {
        try { return objectMapper.readValue(String.valueOf(value), new TypeReference<>() {}); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Invalid report columns", exception); }
    }

    private Map<String, Object> parseMap(Object value) {
        try { return objectMapper.readValue(String.valueOf(value), new TypeReference<>() {}); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Invalid report metadata", exception); }
    }

    private LocalDate date(String value, LocalDate fallback) {
        try { return value == null || value.isBlank() ? fallback : LocalDate.parse(value); }
        catch (RuntimeException exception) { throw invalid("REPORT_DATE_INVALID", "日期格式必须为 YYYY-MM-DD"); }
    }

    private String period(String value, String fallback) {
        String result = value == null || value.isBlank() ? fallback : value;
        if (!result.matches("\\d{4}-\\d{2}")) throw invalid("REPORT_PERIOD_INVALID", "账期格式必须为 YYYY-MM");
        return result;
    }

    private String text(String value) { return value == null ? "" : value.trim(); }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
    }

    private String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private BusinessException invalid(String code, String message) {
        return new BusinessException(code, message, HttpStatus.UNPROCESSABLE_ENTITY);
    }

    private String readPermission(String code) {
        if (Set.of("COLLECTION_RATE", "ARREARS_CLEARANCE_RATE", "COMPREHENSIVE_QUERY", "COLLECTION_CLEARANCE_SUMMARY",
                "CHARGE_DETAILS", "DISCOUNT_DETAILS", "PREPAYMENTS", "FEE_STATUS").contains(code)) return "report:read";
        if (Set.of("BILL_NOTIFICATIONS", "REMINDERS").contains(code)) return "notification:read";
        if ("OWNERSHIP_TRANSFERS".equals(code)) return "property:read";
        if ("INVOICE_STATISTICS".equals(code)) return "invoice:read";
        if ("BANK_TRUST".equals(code)) return "bank:read";
        return "finance:read";
    }

    private String integrationMode(String code) {
        return switch (code) {
            case "BILL_NOTIFICATIONS", "REMINDERS" -> "NOTIFICATION_SIMULATOR";
            case "INVOICE_STATISTICS" -> "INVOICE_SIMULATOR";
            case "BANK_TRUST" -> "BANK_TRUST_SIMULATOR";
            default -> "INTERNAL_LEDGER";
        };
    }
}
