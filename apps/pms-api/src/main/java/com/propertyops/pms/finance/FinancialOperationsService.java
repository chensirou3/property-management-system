package com.propertyops.pms.finance;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
public class FinancialOperationsService {
    private final NamedParameterJdbcTemplate jdbc;
    private final SecurityContextService security;
    private final AuditService audit;
    private final InvoiceAdapter invoiceAdapter;
    private final ObjectMapper objectMapper;

    public FinancialOperationsService(NamedParameterJdbcTemplate jdbc, SecurityContextService security,
                                      AuditService audit, InvoiceAdapter invoiceAdapter, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.security = security;
        this.audit = audit;
        this.invoiceAdapter = invoiceAdapter;
        this.objectMapper = objectMapper;
    }

    public List<Map<String, Object>> shifts(String communityId, String status) {
        readFinance(communityId);
        return jdbc.queryForList("""
                SELECT cs.*, u.username cashier_username, u.display_name cashier_name,
                       locker.display_name locked_by_name,
                       (SELECT COUNT(*) FROM payment_transaction pt WHERE pt.cashier_shift_id=cs.id) transaction_count,
                       (SELECT COALESCE(SUM(pt.amount),0) FROM payment_transaction pt
                        WHERE pt.cashier_shift_id=cs.id AND pt.payment_channel='CASH' AND pt.status='SUCCESS') cash_movement
                FROM cashier_shift cs JOIN sys_user u ON u.id=cs.cashier_user_id
                LEFT JOIN sys_user locker ON locker.id=cs.locked_by
                WHERE cs.community_id=:communityId AND (:status='' OR cs.status=:status)
                ORDER BY cs.opened_at DESC LIMIT 200
                """, Map.of("communityId", communityId, "status", text(status)));
    }

    public Map<String, Object> currentShift(String communityId) {
        readFinance(communityId);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT cs.*,
                       (SELECT COALESCE(SUM(pt.amount),0) FROM payment_transaction pt
                        WHERE pt.cashier_shift_id=cs.id AND pt.payment_channel='CASH' AND pt.status='SUCCESS') cash_movement,
                       (SELECT COUNT(*) FROM payment_transaction pt WHERE pt.cashier_shift_id=cs.id) transaction_count
                FROM cashier_shift cs
                WHERE cs.community_id=:communityId AND cs.cashier_user_id=:userId AND cs.status='OPEN'
                """, Map.of("communityId", communityId, "userId", security.requirePrincipal().userId()));
        return rows.isEmpty() ? Map.of("open", false) : Map.of("open", true, "shift", rows.get(0));
    }

    @Transactional
    public Map<String, Object> openShift(FinancialModels.OpenShift request, String idempotencyKey) {
        security.requirePermission("cashier:write");
        security.requireProject(request.communityId());
        String key = requireKey(idempotencyKey);
        BigDecimal openingCash = money(request.openingCash());
        String requestJson = json(Map.of("communityId", request.communityId(), "openingCash", openingCash));
        String requestHash = sha256(requestJson);
        List<Map<String, Object>> replay = jdbc.queryForList("""
                SELECT * FROM cashier_shift WHERE community_id=:communityId AND request_key=:key
                """, Map.of("communityId", request.communityId(), "key", key));
        if (!replay.isEmpty()) {
            requireSameRequest(replay.get(0).get("request_hash"), requestHash);
            return replay.get(0);
        }
        String userId = security.requirePrincipal().userId();
        List<Map<String, Object>> open = jdbc.queryForList("""
                SELECT id FROM cashier_shift
                WHERE community_id=:communityId AND cashier_user_id=:userId AND status='OPEN' FOR UPDATE
                """, Map.of("communityId", request.communityId(), "userId", userId));
        if (!open.isEmpty()) throw conflict("当前用户已有未关闭班次");
        String id = UUID.randomUUID().toString();
        LocalDateTime now = now();
        jdbc.update("""
                INSERT INTO cashier_shift
                    (id, community_id, shift_no, cashier_user_id, status, opening_cash,
                     expected_cash, request_key, request_hash, request_json,
                     opened_at, version, created_at, updated_at)
                VALUES (:id, :communityId, :number, :userId, 'OPEN', :openingCash,
                        :openingCash, :key, :requestHash, :requestJson,
                        :now, 0, :now, :now)
                """, new MapSqlParameterSource("id", id).addValue("communityId", request.communityId())
                .addValue("number", "SHIFT-" + id).addValue("userId", userId)
                .addValue("openingCash", openingCash).addValue("key", key)
                .addValue("requestHash", requestHash).addValue("requestJson", requestJson).addValue("now", now));
        audit.success(request.communityId(), "cashier-shift:open", "cashier-shift", id,
                Map.of("openingCash", openingCash));
        event(request.communityId(), "SHIFT", id, "OPENED", "shift-open:" + key,
                Map.of("openingCash", openingCash));
        return required("SELECT * FROM cashier_shift WHERE id=:id", Map.of("id", id), "班次创建失败");
    }

    @Transactional
    public Map<String, Object> closeShift(String id, FinancialModels.CloseShift request) {
        security.requirePermission("cashier:write");
        security.requireProject(request.communityId());
        Map<String, Object> shift = lockShift(id, request.communityId());
        if ("CLOSED".equals(shift.get("status")) || "LOCKED".equals(shift.get("status"))) return shift;
        if (!"OPEN".equals(shift.get("status"))) throw conflict("只有开启中的班次可以交班");
        if (!String.valueOf(shift.get("cashier_user_id")).equals(security.requirePrincipal().userId())) {
            throw new BusinessException("SHIFT_OWNER_REQUIRED", "只能关闭自己的班次", HttpStatus.FORBIDDEN);
        }
        requireVersion(shift, request.expectedVersion(), "班次");
        BigDecimal movement = jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount),0) FROM payment_transaction
                WHERE cashier_shift_id=:id AND payment_channel='CASH' AND status='SUCCESS'
                """, Map.of("id", id), BigDecimal.class);
        BigDecimal expected = money(decimal(shift.get("opening_cash")).add(decimal(movement)));
        BigDecimal actual = money(request.actualCash());
        BigDecimal variance = actual.subtract(expected);
        LocalDateTime now = now();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("openingCash", shift.get("opening_cash"));
        snapshot.put("cashMovement", movement);
        snapshot.put("expectedCash", expected);
        snapshot.put("actualCash", actual);
        snapshot.put("varianceAmount", variance);
        String snapshotJson = json(snapshot);
        int changed = jdbc.update("""
                UPDATE cashier_shift SET status='CLOSED', expected_cash=:expected,
                    actual_cash=:actual, variance_amount=:variance, snapshot_json=:snapshot,
                    snapshot_checksum=:checksum, closed_at=:now, version=version+1, updated_at=:now
                WHERE id=:id AND status='OPEN' AND version=:version
                """, new MapSqlParameterSource("expected", expected).addValue("actual", actual)
                .addValue("variance", variance).addValue("snapshot", snapshotJson)
                .addValue("checksum", sha256(snapshotJson)).addValue("now", now)
                .addValue("id", id).addValue("version", request.expectedVersion()));
        if (changed != 1) throw conflict("班次已被其他操作更新");
        audit.success(request.communityId(), "cashier-shift:close", "cashier-shift", id,
                Map.of("expectedCash", expected, "actualCash", actual, "variance", variance));
        event(request.communityId(), "SHIFT", id, "CLOSED", "shift-close:" + id,
                Map.of("expectedCash", expected, "actualCash", actual, "variance", variance));
        return required("SELECT * FROM cashier_shift WHERE id=:id", Map.of("id", id), "班次不存在");
    }

    @Transactional
    public Map<String, Object> lockShift(String id, FinancialModels.LockShift request) {
        security.requirePermission("finance:write");
        security.requireProject(request.communityId());
        Map<String, Object> shift = lockShift(id, request.communityId());
        if ("LOCKED".equals(shift.get("status"))) return shift;
        if (!"CLOSED".equals(shift.get("status"))) throw conflict("只有已交班班次可以锁定");
        requireVersion(shift, request.expectedVersion(), "班次");
        LocalDateTime now = now();
        int changed = jdbc.update("""
                UPDATE cashier_shift SET status='LOCKED', locked_at=:now, locked_by=:userId,
                    version=version+1, updated_at=:now WHERE id=:id AND status='CLOSED' AND version=:version
                """, Map.of("now", now, "userId", security.requirePrincipal().userId(),
                "id", id, "version", request.expectedVersion()));
        if (changed != 1) throw conflict("班次已被其他操作更新");
        audit.success(request.communityId(), "cashier-shift:lock", "cashier-shift", id,
                Map.of("reason", request.reason()));
        event(request.communityId(), "SHIFT", id, "LOCKED", "shift-lock:" + id,
                Map.of("reason", request.reason()));
        return required("SELECT * FROM cashier_shift WHERE id=:id", Map.of("id", id), "班次不存在");
    }

    public List<Map<String, Object>> settlements(String communityId) {
        readFinance(communityId);
        return jdbc.queryForList("""
                SELECT ds.*, u.display_name created_by_name,
                       (SELECT COUNT(*) FROM payment_transaction pt WHERE pt.settlement_id=ds.id) attached_transaction_count
                FROM daily_settlement ds LEFT JOIN sys_user u ON u.id=ds.created_by
                WHERE ds.community_id=:communityId ORDER BY ds.settlement_date DESC LIMIT 366
                """, Map.of("communityId", communityId));
    }

    public Map<String, Object> settlementPreview(String communityId, LocalDate date) {
        readFinance(communityId);
        return calculateSettlement(communityId, date == null ? LocalDate.now(ZoneOffset.UTC) : date, false);
    }

    @Transactional
    public Map<String, Object> closeSettlement(FinancialModels.SettlementRequest request, String idempotencyKey) {
        security.requirePermission("finance:write");
        security.requireProject(request.communityId());
        String key = requireKey(idempotencyKey);
        String requestJson = json(Map.of("communityId", request.communityId(), "settlementDate", request.settlementDate()));
        String requestHash = sha256(requestJson);
        List<Map<String, Object>> replay = jdbc.queryForList("""
                SELECT * FROM daily_settlement WHERE community_id=:communityId AND request_key=:key FOR UPDATE
                """, Map.of("communityId", request.communityId(), "key", key));
        if (!replay.isEmpty()) {
            requireSameRequest(replay.get(0).get("request_hash"), requestHash);
            return replay.get(0);
        }
        List<Map<String, Object>> sameDate = jdbc.queryForList("""
                SELECT * FROM daily_settlement
                WHERE community_id=:communityId AND settlement_date=:date FOR UPDATE
                """, Map.of("communityId", request.communityId(), "date", request.settlementDate()));
        if (!sameDate.isEmpty()) throw conflict("该日期已经完成日结");
        Integer openCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM cashier_shift WHERE community_id=:communityId AND status='OPEN'
                  AND opened_at<:endAt
                """, new MapSqlParameterSource("communityId", request.communityId())
                .addValue("endAt", request.settlementDate().plusDays(1).atStartOfDay()), Integer.class);
        if (openCount != null && openCount > 0) throw conflict("日结前必须关闭该日期及更早开启的收银班次");
        Map<String, Object> calculation = calculateSettlement(request.communityId(), request.settlementDate(), true);
        String id = UUID.randomUUID().toString();
        LocalDateTime now = now();
        String snapshot = json(calculation);
        jdbc.update("""
                INSERT INTO daily_settlement
                    (id, community_id, settlement_date, status, total_amount, gross_amount,
                     reversal_amount, net_amount, transaction_count, calculation_snapshot,
                     request_key, request_hash, snapshot_checksum, created_by, closed_at,
                     version, created_at)
                VALUES (:id, :communityId, :date, 'CLOSED', :net, :gross,
                        :reversal, :net, :count, :snapshot,
                        :key, :requestHash, :checksum, :userId, :now,
                        0, :now)
                """, new MapSqlParameterSource("id", id).addValue("communityId", request.communityId())
                .addValue("date", request.settlementDate()).addValue("gross", calculation.get("grossAmount"))
                .addValue("reversal", calculation.get("reversalAmount")).addValue("net", calculation.get("netAmount"))
                .addValue("count", calculation.get("transactionCount")).addValue("snapshot", snapshot)
                .addValue("key", key).addValue("requestHash", requestHash).addValue("checksum", sha256(snapshot))
                .addValue("userId", security.requirePrincipal().userId()).addValue("now", now));
        jdbc.update("""
                UPDATE payment_transaction SET settlement_id=:settlementId
                WHERE community_id=:communityId AND status='SUCCESS' AND settlement_id IS NULL
                  AND occurred_at>=:startAt AND occurred_at<:endAt
                """, new MapSqlParameterSource("settlementId", id).addValue("communityId", request.communityId())
                .addValue("startAt", request.settlementDate().atStartOfDay())
                .addValue("endAt", request.settlementDate().plusDays(1).atStartOfDay()));
        audit.success(request.communityId(), "daily-settlement:close", "daily-settlement", id, calculation);
        event(request.communityId(), "SETTLEMENT", id, "CLOSED", "settlement-close:" + key, calculation);
        return required("SELECT * FROM daily_settlement WHERE id=:id", Map.of("id", id), "日结创建失败");
    }

    @Transactional
    public Map<String, Object> lockSettlement(String id, FinancialModels.SettlementLock request) {
        security.requirePermission("finance:write");
        security.requireProject(request.communityId());
        Map<String, Object> settlement = required("""
                SELECT * FROM daily_settlement WHERE id=:id AND community_id=:communityId FOR UPDATE
                """, Map.of("id", id, "communityId", request.communityId()), "日结不存在");
        if ("LOCKED".equals(settlement.get("status"))) return settlement;
        if (!"CLOSED".equals(settlement.get("status"))) throw conflict("只有已关闭日结可以锁定");
        requireVersion(settlement, request.expectedVersion(), "日结");
        int changed = jdbc.update("""
                UPDATE daily_settlement SET status='LOCKED', version=version+1
                WHERE id=:id AND status='CLOSED' AND version=:version
                """, Map.of("id", id, "version", request.expectedVersion()));
        if (changed != 1) throw conflict("日结已被其他操作更新");
        audit.success(request.communityId(), "daily-settlement:lock", "daily-settlement", id,
                Map.of("reason", request.reason()));
        event(request.communityId(), "SETTLEMENT", id, "LOCKED", "settlement-lock:" + id,
                Map.of("reason", request.reason()));
        return required("SELECT * FROM daily_settlement WHERE id=:id", Map.of("id", id), "日结不存在");
    }

    public List<Map<String, Object>> receiptSegments(String communityId) {
        readFinance(communityId);
        return jdbc.queryForList("""
                SELECT rns.*, (end_no-next_no+1) remaining_count,
                       (SELECT COUNT(*) FROM receipt r WHERE r.segment_id=rns.id) issued_count
                FROM receipt_number_segment rns WHERE community_id=:communityId ORDER BY segment_code
                """, Map.of("communityId", communityId));
    }

    @Transactional
    public Map<String, Object> createReceiptSegment(FinancialModels.CreateReceiptSegment request) {
        security.requirePermission("finance:instrument-write");
        security.requireProject(request.communityId());
        if (request.endNo() < request.startNo()) throw invalid("票据号段结束号不能小于起始号");
        String id = UUID.randomUUID().toString();
        LocalDateTime now = now();
        jdbc.update("""
                INSERT INTO receipt_number_segment
                    (id, community_id, segment_code, number_prefix, start_no, end_no,
                     next_no, status, version, created_at, updated_at)
                VALUES (:id, :communityId, :code, :prefix, :startNo, :endNo,
                        :startNo, 'ACTIVE', 0, :now, :now)
                """, Map.of("id", id, "communityId", request.communityId(),
                "code", request.segmentCode().trim(), "prefix", request.numberPrefix().trim(),
                "startNo", request.startNo(), "endNo", request.endNo(), "now", now));
        audit.success(request.communityId(), "receipt-segment:create", "receipt-segment", id,
                Map.of("code", request.segmentCode(), "startNo", request.startNo(), "endNo", request.endNo()));
        return required("SELECT * FROM receipt_number_segment WHERE id=:id", Map.of("id", id), "票据号段创建失败");
    }

    public List<Map<String, Object>> receipts(String communityId, String status) {
        readFinance(communityId);
        return jdbc.queryForList("""
                SELECT r.*, po.order_no, po.confirmed_amount, po.payment_channel,
                       original.receipt_no original_receipt_no
                FROM receipt r JOIN payment_order po ON po.id=r.payment_order_id
                LEFT JOIN receipt original ON original.id=r.original_receipt_id
                WHERE r.community_id=:communityId AND (:status='' OR r.status=:status)
                ORDER BY r.issued_at DESC LIMIT 500
                """, Map.of("communityId", communityId, "status", text(status)));
    }

    @Transactional
    public Map<String, Object> replaceReceipt(String id, FinancialModels.ReceiptOperation request) {
        security.requirePermission("finance:instrument-write");
        security.requireProject(request.communityId());
        Map<String, Object> original = lockReceipt(id, request.communityId());
        if ("REPLACED".equals(original.get("status"))) {
            return required("SELECT * FROM receipt WHERE original_receipt_id=:id ORDER BY created_at DESC LIMIT 1",
                    Map.of("id", id), "替换收据不存在");
        }
        if (!"ISSUED".equals(original.get("status"))) throw conflict("只有已签发收据可以换开");
        ReceiptNumber number = nextReceiptNumber(request.communityId());
        String replacementId = UUID.randomUUID().toString();
        LocalDateTime now = now();
        Map<String, Object> snapshotMap = new LinkedHashMap<>();
        snapshotMap.put("operation", "REPLACE");
        snapshotMap.put("originalReceiptId", id);
        snapshotMap.put("originalReceiptNo", original.get("receipt_no"));
        snapshotMap.put("reason", request.reason());
        snapshotMap.put("sourceSnapshotChecksum", original.get("snapshot_checksum"));
        String snapshot = json(snapshotMap);
        jdbc.update("""
                INSERT INTO receipt
                    (id, community_id, payment_order_id, segment_id, sequence_no,
                     receipt_no, status, template_version, data_snapshot, snapshot_checksum,
                     original_receipt_id, event_reason, issued_at, created_at)
                VALUES (:id, :communityId, :orderId, :segmentId, :sequenceNo,
                        :receiptNo, 'ISSUED', :templateVersion, :snapshot, :checksum,
                        :originalId, :reason, :now, :now)
                """, new MapSqlParameterSource("id", replacementId).addValue("communityId", request.communityId())
                .addValue("orderId", original.get("payment_order_id")).addValue("segmentId", number.segmentId())
                .addValue("sequenceNo", number.sequenceNo()).addValue("receiptNo", number.receiptNo())
                .addValue("templateVersion", original.get("template_version")).addValue("snapshot", snapshot)
                .addValue("checksum", sha256(snapshot)).addValue("originalId", id)
                .addValue("reason", request.reason()).addValue("now", now));
        jdbc.update("""
                UPDATE receipt SET status='REPLACED', event_reason=:reason, voided_at=:now, voided_by=:userId
                WHERE id=:id AND status='ISSUED'
                """, Map.of("reason", request.reason(), "now", now,
                "userId", security.requirePrincipal().userId(), "id", id));
        audit.success(request.communityId(), "receipt:replace", "receipt", replacementId,
                Map.of("originalReceiptId", id, "receiptNo", number.receiptNo()));
        event(request.communityId(), "RECEIPT", replacementId, "REPLACED", "receipt-replace:" + id,
                Map.of("originalReceiptId", id, "reason", request.reason()));
        return required("SELECT * FROM receipt WHERE id=:id", Map.of("id", replacementId), "替换收据创建失败");
    }

    @Transactional
    public Map<String, Object> voidReceipt(String id, FinancialModels.ReceiptOperation request) {
        security.requirePermission("finance:instrument-write");
        security.requireProject(request.communityId());
        Map<String, Object> receipt = lockReceipt(id, request.communityId());
        if ("VOIDED".equals(receipt.get("status"))) return receipt;
        if (!"ISSUED".equals(receipt.get("status"))) throw conflict("只有已签发收据可以作废");
        LocalDateTime now = now();
        int changed = jdbc.update("""
                UPDATE receipt SET status='VOIDED', event_reason=:reason,
                    voided_at=:now, voided_by=:userId WHERE id=:id AND status='ISSUED'
                """, Map.of("reason", request.reason(), "now", now,
                "userId", security.requirePrincipal().userId(), "id", id));
        if (changed != 1) throw conflict("收据已被其他操作更新");
        audit.success(request.communityId(), "receipt:void", "receipt", id, Map.of("reason", request.reason()));
        event(request.communityId(), "RECEIPT", id, "VOIDED", "receipt-void:" + id,
                Map.of("reason", request.reason()));
        return required("SELECT * FROM receipt WHERE id=:id", Map.of("id", id), "收据不存在");
    }

    public List<Map<String, Object>> invoices(String communityId) {
        security.requirePermission("invoice:read");
        security.requireProject(communityId);
        return jdbc.queryForList("""
                SELECT ir.*, r.receipt_no, original.request_no original_request_no
                FROM invoice_request ir LEFT JOIN receipt r ON r.id=ir.receipt_id
                LEFT JOIN invoice_request original ON original.id=ir.original_invoice_request_id
                WHERE ir.community_id=:communityId ORDER BY ir.created_at DESC LIMIT 500
                """, Map.of("communityId", communityId));
    }

    @Transactional
    public Map<String, Object> operateInvoice(String id, FinancialModels.InvoiceOperation request) {
        security.requirePermission("invoice:write");
        security.requireProject(request.communityId());
        String operation = request.operationType() == null ? "" : request.operationType().trim().toUpperCase();
        if (!List.of("REPLACE", "RED").contains(operation)) throw invalid("发票操作只支持 REPLACE 或 RED");
        Map<String, Object> original = required("""
                SELECT * FROM invoice_request WHERE id=:id AND community_id=:communityId FOR UPDATE
                """, Map.of("id", id, "communityId", request.communityId()), "原发票请求不存在");
        if (!"ISSUE".equals(original.get("operation_type"))) throw conflict("只能对原始开票记录执行换开或红冲");
        String requestNo = "INV-" + operation + "-" + id;
        String requestJson = json(Map.of("communityId", request.communityId(), "originalInvoiceId", id,
                "operationType", operation, "title", request.title(), "reason", request.reason()));
        String requestHash = sha256(requestJson);
        List<Map<String, Object>> replay = jdbc.queryForList("SELECT * FROM invoice_request WHERE request_no=:number",
                Map.of("number", requestNo));
        if (!replay.isEmpty()) {
            requireSameRequest(replay.get(0).get("request_hash"), requestHash);
            return replay.get(0);
        }
        BigDecimal sourceAmount = decimal(original.get("amount"));
        BigDecimal operationAmount = "RED".equals(operation) ? sourceAmount.negate() : sourceAmount;
        InvoiceAdapter.InvoiceResult result = invoiceAdapter.issue(requestNo, operationAmount, request.title());
        String operationId = UUID.randomUUID().toString();
        LocalDateTime now = now();
        Map<String, Object> snapshotMap = new LinkedHashMap<>();
        snapshotMap.put("operation", operation);
        snapshotMap.put("originalInvoiceId", id);
        snapshotMap.put("originalRequestNo", original.get("request_no"));
        snapshotMap.put("amount", operationAmount);
        snapshotMap.put("title", request.title());
        snapshotMap.put("reason", request.reason());
        snapshotMap.put("simulated", result.simulated());
        String snapshot = json(snapshotMap);
        jdbc.update("""
                INSERT INTO invoice_request
                    (id, community_id, receipt_id, request_no, operation_type,
                     original_invoice_request_id, reason, request_hash, adapter_code,
                     status, amount, title_snapshot, snapshot_json, snapshot_checksum,
                     external_reference, requested_by, created_at, updated_at)
                VALUES (:id, :communityId, :receiptId, :requestNo, :operation,
                        :originalId, :reason, :requestHash, :adapter,
                        :status, :amount, :title, :snapshot, :checksum,
                        :externalReference, :userId, :now, :now)
                """, new MapSqlParameterSource("id", operationId).addValue("communityId", request.communityId())
                .addValue("receiptId", original.get("receipt_id")).addValue("requestNo", requestNo)
                .addValue("operation", operation).addValue("originalId", id).addValue("reason", request.reason())
                .addValue("requestHash", requestHash).addValue("adapter", invoiceAdapter.code())
                .addValue("status", result.status()).addValue("amount", operationAmount)
                .addValue("title", request.title()).addValue("snapshot", snapshot)
                .addValue("checksum", sha256(snapshot)).addValue("externalReference", result.externalReference())
                .addValue("userId", security.requirePrincipal().userId()).addValue("now", now));
        jdbc.update("UPDATE invoice_request SET snapshot_checksum=SHA2(snapshot_json, 256) WHERE id=:id",
                Map.of("id", operationId));
        jdbc.update("UPDATE invoice_request SET status=:status, updated_at=:now WHERE id=:id",
                Map.of("status", "RED".equals(operation) ? "RED_CORRECTED" : "REPLACED", "now", now, "id", id));
        audit.success(request.communityId(), "invoice:" + operation.toLowerCase(), "invoice-request", operationId,
                Map.of("originalInvoiceId", id, "amount", operationAmount, "simulated", true));
        event(request.communityId(), "INVOICE", operationId, operation,
                "invoice-operation:" + operation + ":" + id,
                Map.of("originalInvoiceId", id, "amount", operationAmount, "simulated", true));
        return required("SELECT * FROM invoice_request WHERE id=:id", Map.of("id", operationId), "发票操作失败");
    }

    public Map<String, Object> reconciliation(String communityId) {
        readFinance(communityId);
        int billMismatches = count("""
                SELECT COUNT(*) FROM bill WHERE community_id=:communityId AND
                  (ABS((original_amount+adjustment_amount)-total_amount)>0.001
                   OR ABS(total_amount-paid_amount-outstanding_amount)>0.001)
                """, communityId);
        int paymentMismatches = count("""
                SELECT COUNT(*) FROM (
                  SELECT pt.id FROM payment_transaction pt
                  LEFT JOIN payment_allocation pa ON pa.payment_transaction_id=pt.id
                  WHERE pt.community_id=:communityId AND pt.status='SUCCESS'
                  GROUP BY pt.id, pt.amount HAVING ABS(pt.amount-COALESCE(SUM(pa.allocated_amount),0))>0.001
                ) mismatches
                """, communityId);
        int prepaymentMismatches = count("""
                SELECT COUNT(*) FROM (
                  SELECT pa.id FROM prepayment_account pa LEFT JOIN prepayment_transaction pt ON pt.account_id=pa.id
                  WHERE pa.community_id=:communityId GROUP BY pa.id, pa.balance
                  HAVING ABS(pa.balance-COALESCE(SUM(pt.amount),0))>0.001
                ) mismatches
                """, communityId);
        int depositMismatches = count("""
                SELECT COUNT(*) FROM (
                  SELECT da.id FROM deposit_account da LEFT JOIN deposit_transaction dt ON dt.account_id=da.id
                  WHERE da.community_id=:communityId GROUP BY da.id, da.balance
                  HAVING ABS(da.balance-COALESCE(SUM(dt.amount),0))>0.001
                ) mismatches
                """, communityId);
        int settlementMismatches = count("""
                SELECT COUNT(*) FROM (
                  SELECT ds.id FROM daily_settlement ds LEFT JOIN payment_transaction pt ON pt.settlement_id=ds.id
                  WHERE ds.community_id=:communityId GROUP BY ds.id, ds.net_amount, ds.transaction_count
                  HAVING ABS(ds.net_amount-COALESCE(SUM(pt.amount),0))>0.001
                    OR ds.transaction_count<>COUNT(pt.id)
                ) mismatches
                """, communityId);
        int receiptChecksumMismatches = count("""
                SELECT COUNT(*) FROM receipt WHERE community_id=:communityId
                  AND snapshot_checksum<>SHA2(data_snapshot,256)
                """, communityId);
        int invoiceChecksumMismatches = count("""
                SELECT COUNT(*) FROM invoice_request WHERE community_id=:communityId
                  AND snapshot_checksum<>SHA2(snapshot_json,256)
                """, communityId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("billMismatches", billMismatches);
        result.put("paymentMismatches", paymentMismatches);
        result.put("prepaymentMismatches", prepaymentMismatches);
        result.put("depositMismatches", depositMismatches);
        result.put("settlementMismatches", settlementMismatches);
        result.put("receiptChecksumMismatches", receiptChecksumMismatches);
        result.put("invoiceChecksumMismatches", invoiceChecksumMismatches);
        result.put("healthy", result.values().stream().mapToInt(value -> ((Number) value).intValue()).sum() == 0);
        return result;
    }

    private Map<String, Object> calculateSettlement(String communityId, LocalDate date, boolean lockRows) {
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = date.plusDays(1).atStartOfDay();
        String suffix = lockRows ? " FOR UPDATE" : "";
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, transaction_type, payment_channel, amount FROM payment_transaction
                WHERE community_id=:communityId AND status='SUCCESS'
                  AND occurred_at>=:startAt AND occurred_at<:endAt
                ORDER BY occurred_at, id
                """ + suffix, new MapSqlParameterSource("communityId", communityId)
                .addValue("startAt", start).addValue("endAt", end));
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal reversal = BigDecimal.ZERO;
        Map<String, BigDecimal> channels = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            BigDecimal amount = decimal(row.get("amount"));
            if (amount.signum() >= 0) gross = gross.add(amount); else reversal = reversal.add(amount);
            String channel = String.valueOf(row.get("payment_channel"));
            channels.merge(channel, amount, BigDecimal::add);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("communityId", communityId);
        result.put("settlementDate", date);
        result.put("transactionCount", rows.size());
        result.put("grossAmount", money(gross));
        result.put("reversalAmount", signedMoney(reversal));
        result.put("netAmount", signedMoney(gross.add(reversal)));
        result.put("channelBreakdown", channels);
        return result;
    }

    private ReceiptNumber nextReceiptNumber(String communityId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT * FROM receipt_number_segment
                WHERE community_id=:communityId AND status='ACTIVE' AND next_no<=end_no
                ORDER BY created_at, id LIMIT 1 FOR UPDATE
                """, Map.of("communityId", communityId));
        if (rows.isEmpty()) throw conflict("没有可用票据号段");
        Map<String, Object> segment = rows.get(0);
        long sequence = ((Number) segment.get("next_no")).longValue();
        long end = ((Number) segment.get("end_no")).longValue();
        int changed = jdbc.update("""
                UPDATE receipt_number_segment SET next_no=next_no+1,
                    status=CASE WHEN next_no>=end_no THEN 'EXHAUSTED' ELSE status END,
                    version=version+1, updated_at=:now WHERE id=:id AND version=:version
                """, Map.of("now", now(), "id", segment.get("id"), "version", segment.get("version")));
        if (changed != 1) throw conflict("票据号段已被其他操作占用");
        return new ReceiptNumber(String.valueOf(segment.get("id")), sequence,
                String.valueOf(segment.get("number_prefix")) + String.format("%06d", sequence));
    }

    private Map<String, Object> lockShift(String id, String communityId) {
        return required("""
                SELECT * FROM cashier_shift WHERE id=:id AND community_id=:communityId FOR UPDATE
                """, Map.of("id", id, "communityId", communityId), "班次不存在");
    }

    private Map<String, Object> lockReceipt(String id, String communityId) {
        return required("""
                SELECT * FROM receipt WHERE id=:id AND community_id=:communityId FOR UPDATE
                """, Map.of("id", id, "communityId", communityId), "收据不存在");
    }

    private int count(String sql, String communityId) {
        Integer value = jdbc.queryForObject(sql, Map.of("communityId", communityId), Integer.class);
        return value == null ? 0 : value;
    }

    private void readFinance(String communityId) {
        security.requirePermission("finance:read");
        security.requireProject(communityId);
    }

    private Map<String, Object> required(String sql, Map<String, ?> params, String message) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, params);
        if (rows.isEmpty()) throw notFound(message);
        return rows.get(0);
    }

    private void requireVersion(Map<String, Object> row, long version, String label) {
        if (((Number) row.get("version")).longValue() != version) throw conflict(label + "已被其他操作更新");
    }

    private void requireSameRequest(Object storedHash, String requestHash) {
        if (storedHash == null || !String.valueOf(storedHash).equals(requestHash)) {
            throw conflict("同一个幂等键不能提交不同请求");
        }
    }

    private String requireKey(String key) {
        if (key == null || key.isBlank()) throw invalid("缺少 Idempotency-Key");
        String normalized = key.trim();
        if (normalized.length() > 80) throw invalid("Idempotency-Key 不能超过 80 个字符");
        return normalized;
    }

    private String text(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }

    private BigDecimal decimal(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value));
    }

    private BigDecimal money(BigDecimal value) {
        if (value == null || value.signum() < 0) throw invalid("金额不能为负");
        return value.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private BigDecimal signedMoney(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, java.math.RoundingMode.HALF_UP);
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

    private record ReceiptNumber(String segmentId, long sequenceNo, String receiptNo) {}
}
