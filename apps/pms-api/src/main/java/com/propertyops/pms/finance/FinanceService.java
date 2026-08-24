package com.propertyops.pms.finance;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.validation.Valid;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.propertyops.pms.adapter.InvoiceAdapter;
import com.propertyops.pms.adapter.PaymentAdapter;
import com.propertyops.pms.common.api.BusinessException;
import com.propertyops.pms.common.audit.AuditService;
import com.propertyops.pms.security.SecurityContextService;

@Service
public class FinanceService {
    private final NamedParameterJdbcTemplate jdbc;
    private final SecurityContextService security;
    private final AuditService audit;
    private final PaymentAdapter paymentAdapter;
    private final InvoiceAdapter invoiceAdapter;
    private final ObjectMapper objectMapper;

    public FinanceService(NamedParameterJdbcTemplate jdbc, SecurityContextService security, AuditService audit,
                          PaymentAdapter paymentAdapter, InvoiceAdapter invoiceAdapter, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.security = security;
        this.audit = audit;
        this.paymentAdapter = paymentAdapter;
        this.invoiceAdapter = invoiceAdapter;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> cashierContext(String communityId, String keyword) {
        security.requirePermission("cashier:read");
        security.requireProject(communityId);
        String pattern = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        MapSqlParameterSource params = new MapSqlParameterSource("communityId", communityId).addValue("keyword", pattern);
        List<Map<String, Object>> assets = jdbc.queryForList("""
                SELECT id, code, display_name, asset_type, occupancy_status
                FROM asset WHERE community_id=:communityId AND enabled=TRUE
                  AND (code LIKE :keyword OR display_name LIKE :keyword)
                ORDER BY code LIMIT 20
                """, params);
        List<Map<String, Object>> customers = jdbc.queryForList("""
                SELECT id, display_name, customer_type, mobile_masked
                FROM customer WHERE community_id=:communityId AND status='ACTIVE'
                  AND (display_name LIKE :keyword OR mobile_masked LIKE :keyword)
                ORDER BY display_name LIMIT 20
                """, params);
        List<Map<String, Object>> bills = jdbc.queryForList("""
                SELECT b.id, b.bill_no, b.asset_id, b.customer_id, b.billing_period,
                       b.total_amount, b.paid_amount, b.outstanding_amount, b.due_date, b.status, b.version
                FROM bill b JOIN asset a ON a.id=b.asset_id
                LEFT JOIN customer c ON c.id=b.customer_id
                WHERE b.community_id=:communityId AND b.outstanding_amount > 0
                  AND (b.bill_no LIKE :keyword OR a.code LIKE :keyword OR a.display_name LIKE :keyword
                       OR c.display_name LIKE :keyword)
                ORDER BY b.due_date, b.bill_no LIMIT 100
                """, params);
        return Map.of("assets", assets, "customers", customers, "bills", bills);
    }

    @Transactional
    public PaymentOrderResult createPaymentOrder(PaymentOrderRequest request, String idempotencyKey) {
        security.requirePermission("cashier:write");
        security.requireProject(request.communityId());
        requireKey(idempotencyKey);
        List<Map<String, Object>> previous = jdbc.queryForList("""
                SELECT id, order_no, status, requested_amount FROM payment_order
                WHERE community_id=:communityId AND idempotency_key=:key
                """, Map.of("communityId", request.communityId(), "key", idempotencyKey));
        if (!previous.isEmpty()) return orderResult(previous.get(0), true);
        if (request.bills() == null || request.bills().isEmpty()) throw invalid("至少选择一张账单");
        BigDecimal total = BigDecimal.ZERO;
        List<LockedBill> bills = new ArrayList<>();
        for (BillPayment intent : request.bills()) {
            LedgerMath.positive(intent.amount());
            LockedBill bill = lockBill(request.communityId(), intent.billId());
            if (intent.amount().compareTo(bill.outstanding()) > 0) throw invalid("账单支付金额超过待收余额");
            bills.add(bill);
            total = total.add(intent.amount());
        }
        String orderId = UUID.randomUUID().toString();
        String orderNo = "PAY-" + orderId;
        LocalDateTime now = now();
        jdbc.update("""
                INSERT INTO payment_order
                    (id, community_id, order_no, idempotency_key, payment_method, status,
                     requested_amount, requested_by, version, created_at, updated_at)
                VALUES (:id, :communityId, :orderNo, :key, :method, 'PENDING',
                        :amount, :userId, 0, :now, :now)
                """, Map.of("id", orderId, "communityId", request.communityId(), "orderNo", orderNo,
                "key", idempotencyKey, "method", request.paymentMethod(), "amount", total,
                "userId", security.requirePrincipal().userId(), "now", now));
        for (BillPayment intent : request.bills()) {
            jdbc.update("""
                    INSERT INTO payment_order_intent (id, payment_order_id, bill_id, requested_amount, created_at)
                    VALUES (:id, :orderId, :billId, :amount, :now)
                    """, Map.of("id", UUID.randomUUID().toString(), "orderId", orderId,
                    "billId", intent.billId(), "amount", intent.amount(), "now", now));
        }
        audit.success(request.communityId(), "payment-order:create", "payment-order", orderId,
                Map.of("amount", total, "billCount", bills.size(), "method", request.paymentMethod()));
        return new PaymentOrderResult(orderId, orderNo, "PENDING", total, null, null, false);
    }

    @Transactional
    public PaymentOrderResult confirm(String orderId, String communityId) {
        security.requirePermission("cashier:write");
        security.requireProject(communityId);
        Map<String, Object> order = lockOrder(orderId, communityId);
        if ("CONFIRMED".equals(order.get("status"))) return confirmedResult(order, true);
        if (!"PENDING".equals(order.get("status"))) throw invalid("只有待确认订单可以收款");
        List<Map<String, Object>> intents = jdbc.queryForList("""
                SELECT bill_id, requested_amount FROM payment_order_intent WHERE payment_order_id=:orderId
                ORDER BY bill_id
                """, Map.of("orderId", orderId));
        BigDecimal requested = decimal(order.get("requested_amount"));
        PaymentAdapter.PaymentResult adapterResult = paymentAdapter.confirm(String.valueOf(order.get("order_no")),
                requested, String.valueOf(order.get("payment_method")));
        if (!"SUCCESS".equals(adapterResult.status())) throw new BusinessException("PAYMENT_NOT_CONFIRMED", "模拟支付未成功", HttpStatus.CONFLICT);
        String transactionId = UUID.randomUUID().toString();
        String transactionNo = "TXN-" + transactionId;
        LocalDateTime now = now();
        jdbc.update("""
                INSERT INTO payment_transaction
                    (id, payment_order_id, transaction_no, adapter_code, transaction_type, status,
                     amount, external_reference, occurred_at, created_at)
                VALUES (:id, :orderId, :transactionNo, :adapter, 'PAYMENT', 'SUCCESS',
                        :amount, :reference, :now, :now)
                """, Map.of("id", transactionId, "orderId", orderId, "transactionNo", transactionNo,
                "adapter", paymentAdapter.code(), "amount", requested, "reference", adapterResult.externalReference(), "now", now));
        for (Map<String, Object> intent : intents) {
            String billId = String.valueOf(intent.get("bill_id"));
            BigDecimal amount = decimal(intent.get("requested_amount"));
            LockedBill bill = lockBill(communityId, billId);
            LedgerMath.BillBalance balance = LedgerMath.applyPayment(bill.total(), bill.paid(), bill.outstanding(), amount);
            updateBill(billId, bill.version(), balance);
            jdbc.update("""
                    INSERT INTO payment_allocation (id, payment_transaction_id, bill_id, allocated_amount, created_at)
                    VALUES (:id, :transactionId, :billId, :amount, :now)
                    """, Map.of("id", UUID.randomUUID().toString(), "transactionId", transactionId,
                    "billId", billId, "amount", amount, "now", now));
        }
        jdbc.update("""
                UPDATE payment_order SET status='CONFIRMED', confirmed_amount=:amount,
                    version=version+1, updated_at=:now WHERE id=:id
                """, Map.of("amount", requested, "now", now, "id", orderId));
        String receiptId = UUID.randomUUID().toString();
        String receiptNo = "RCT-" + receiptId;
        jdbc.update("""
                INSERT INTO receipt
                    (id, community_id, payment_order_id, receipt_no, status, template_version,
                     data_snapshot, issued_at, created_at)
                VALUES (:id, :communityId, :orderId, :receiptNo, 'ISSUED', 'SYN-V1', :snapshot, :now, :now)
                """, Map.of("id", receiptId, "communityId", communityId, "orderId", orderId, "receiptNo", receiptNo,
                "snapshot", json(Map.of("orderNo", order.get("order_no"), "amount", requested,
                        "paymentMethod", order.get("payment_method"), "simulated", adapterResult.simulated())), "now", now));
        outbox("PAYMENT_ORDER", orderId, "PaymentConfirmed", Map.of("transactionId", transactionId, "receiptId", receiptId));
        audit.success(communityId, "payment-order:confirm", "payment-order", orderId,
                Map.of("transactionId", transactionId, "receiptId", receiptId, "simulated", true));
        return new PaymentOrderResult(orderId, String.valueOf(order.get("order_no")), "CONFIRMED", requested,
                transactionId, receiptId, false);
    }

    @Transactional
    public Map<String, Object> createPrepaymentAccount(AccountRequest request) {
        security.requirePermission("cashier:write");
        security.requireProject(request.communityId());
        requireCustomer(request.communityId(), request.customerId());
        List<Map<String, Object>> existing = jdbc.queryForList("""
                SELECT * FROM prepayment_account WHERE community_id=:communityId AND customer_id=:customerId
                """, Map.of("communityId", request.communityId(), "customerId", request.customerId()));
        if (!existing.isEmpty()) return existing.get(0);
        String id = UUID.randomUUID().toString();
        LocalDateTime now = now();
        jdbc.update("""
                INSERT INTO prepayment_account
                    (id, community_id, customer_id, balance, frozen_balance, version, created_at, updated_at)
                VALUES (:id, :communityId, :customerId, 0, 0, 0, :now, :now)
                """, Map.of("id", id, "communityId", request.communityId(), "customerId", request.customerId(), "now", now));
        audit.success(request.communityId(), "prepayment-account:create", "prepayment-account", id, Map.of());
        return jdbc.queryForMap("SELECT * FROM prepayment_account WHERE id=:id", Map.of("id", id));
    }

    @Transactional
    public AccountTransactionResult topUp(String accountId, AccountAmountRequest request, String idempotencyKey) {
        security.requirePermission("cashier:write");
        security.requireProject(request.communityId());
        requireKey(idempotencyKey);
        LedgerMath.positive(request.amount());
        List<Map<String, Object>> replay = jdbc.queryForList("""
                SELECT id, balance_after FROM prepayment_transaction
                WHERE account_id=:accountId AND idempotency_key=:key
                """, Map.of("accountId", accountId, "key", idempotencyKey));
        if (!replay.isEmpty()) return new AccountTransactionResult(String.valueOf(replay.get(0).get("id")),
                decimal(replay.get(0).get("balance_after")), true);
        Map<String, Object> account = lockPrepayment(accountId, request.communityId());
        BigDecimal balanceAfter = decimal(account.get("balance")).add(request.amount());
        String transactionId = UUID.randomUUID().toString();
        LocalDateTime now = now();
        jdbc.update("""
                INSERT INTO prepayment_transaction
                    (id, account_id, transaction_type, amount, balance_after, reference_type,
                     idempotency_key, occurred_at, created_at)
                VALUES (:id, :accountId, 'TOP_UP', :amount, :balanceAfter, 'LOCAL', :key, :now, :now)
                """, Map.of("id", transactionId, "accountId", accountId, "amount", request.amount(),
                "balanceAfter", balanceAfter, "key", idempotencyKey, "now", now));
        jdbc.update("""
                UPDATE prepayment_account SET balance=:balance, version=version+1, updated_at=:now
                WHERE id=:id AND version=:version
                """, Map.of("balance", balanceAfter, "now", now, "id", accountId, "version", account.get("version")));
        audit.success(request.communityId(), "prepayment:top-up", "prepayment-account", accountId,
                Map.of("amount", request.amount(), "balanceAfter", balanceAfter));
        return new AccountTransactionResult(transactionId, balanceAfter, false);
    }

    @Transactional
    public PaymentOrderResult applyPrepayment(String accountId, PrepaymentApply request, String idempotencyKey) {
        security.requirePermission("cashier:write");
        security.requireProject(request.communityId());
        requireKey(idempotencyKey);
        List<Map<String, Object>> replay = jdbc.queryForList("""
                SELECT reference_id FROM prepayment_transaction
                WHERE account_id=:accountId AND idempotency_key=:key AND transaction_type='DEDUCT'
                """, Map.of("accountId", accountId, "key", idempotencyKey));
        if (!replay.isEmpty()) {
            Map<String, Object> order = lockOrder(String.valueOf(replay.get(0).get("reference_id")), request.communityId());
            return confirmedResult(order, true);
        }
        Map<String, Object> account = lockPrepayment(accountId, request.communityId());
        BigDecimal total = request.bills().stream().map(BillPayment::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        LedgerMath.positive(total);
        if (total.compareTo(decimal(account.get("balance"))) > 0) throw invalid("预收余额不足");
        String orderId = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
        String receiptId = UUID.randomUUID().toString();
        LocalDateTime now = now();
        jdbc.update("""
                INSERT INTO payment_order
                    (id, community_id, order_no, idempotency_key, payment_method, status, requested_amount,
                     confirmed_amount, requested_by, version, created_at, updated_at)
                VALUES (:id, :communityId, :orderNo, :key, 'PREPAYMENT', 'CONFIRMED', :amount,
                        :amount, :userId, 0, :now, :now)
                """, Map.of("id", orderId, "communityId", request.communityId(), "orderNo", "PAY-" + orderId,
                "key", "PREPAY-" + idempotencyKey, "amount", total, "userId", security.requirePrincipal().userId(), "now", now));
        jdbc.update("""
                INSERT INTO payment_transaction
                    (id, payment_order_id, transaction_no, adapter_code, transaction_type, status,
                     amount, external_reference, occurred_at, created_at)
                VALUES (:id, :orderId, :transactionNo, 'PREPAYMENT_ACCOUNT', 'PAYMENT', 'SUCCESS',
                        :amount, :reference, :now, :now)
                """, Map.of("id", transactionId, "orderId", orderId, "transactionNo", "TXN-" + transactionId,
                "amount", total, "reference", accountId, "now", now));
        for (BillPayment intent : request.bills()) {
            LockedBill bill = lockBill(request.communityId(), intent.billId());
            LedgerMath.BillBalance balance = LedgerMath.applyPayment(bill.total(), bill.paid(), bill.outstanding(), intent.amount());
            updateBill(bill.id(), bill.version(), balance);
            jdbc.update("""
                    INSERT INTO payment_allocation (id, payment_transaction_id, bill_id, allocated_amount, created_at)
                    VALUES (:id, :transactionId, :billId, :amount, :now)
                    """, Map.of("id", UUID.randomUUID().toString(), "transactionId", transactionId,
                    "billId", intent.billId(), "amount", intent.amount(), "now", now));
        }
        BigDecimal balanceAfter = decimal(account.get("balance")).subtract(total);
        jdbc.update("""
                INSERT INTO prepayment_transaction
                    (id, account_id, transaction_type, amount, balance_after, reference_type, reference_id,
                     idempotency_key, occurred_at, created_at)
                VALUES (:id, :accountId, 'DEDUCT', :amount, :balanceAfter, 'PAYMENT_ORDER', :orderId,
                        :key, :now, :now)
                """, Map.of("id", UUID.randomUUID().toString(), "accountId", accountId, "amount", total.negate(),
                "balanceAfter", balanceAfter, "orderId", orderId, "key", idempotencyKey, "now", now));
        jdbc.update("UPDATE prepayment_account SET balance=:balance, version=version+1, updated_at=:now WHERE id=:id",
                Map.of("balance", balanceAfter, "now", now, "id", accountId));
        createReceipt(receiptId, request.communityId(), orderId, total, "PREPAYMENT", now);
        audit.success(request.communityId(), "prepayment:apply", "prepayment-account", accountId,
                Map.of("amount", total, "orderId", orderId));
        return new PaymentOrderResult(orderId, "PAY-" + orderId, "CONFIRMED", total, transactionId, receiptId, false);
    }

    @Transactional
    public AccountTransactionResult collectDeposit(DepositCollect request, String idempotencyKey) {
        security.requirePermission("cashier:write");
        security.requireProject(request.communityId());
        requireKey(idempotencyKey);
        LedgerMath.positive(request.amount());
        List<Map<String, Object>> replay = jdbc.queryForList("""
                SELECT dt.id, dt.balance_after FROM deposit_transaction dt WHERE dt.idempotency_key=:key
                """, Map.of("key", idempotencyKey));
        if (!replay.isEmpty()) return new AccountTransactionResult(String.valueOf(replay.get(0).get("id")),
                decimal(replay.get(0).get("balance_after")), true);
        requireCustomer(request.communityId(), request.customerId());
        String accountId = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
        LocalDateTime now = now();
        var accountParams = new MapSqlParameterSource("id", accountId).addValue("communityId", request.communityId())
                .addValue("customerId", request.customerId()).addValue("assetId", request.assetId())
                .addValue("type", request.depositType()).addValue("amount", request.amount()).addValue("now", now);
        jdbc.update("""
                INSERT INTO deposit_account
                    (id, community_id, customer_id, asset_id, deposit_type, balance, status, version, created_at, updated_at)
                VALUES (:id, :communityId, :customerId, :assetId, :type, :amount, 'ACTIVE', 0, :now, :now)
                """, accountParams);
        jdbc.update("""
                INSERT INTO deposit_transaction
                    (id, account_id, transaction_type, amount, balance_after, idempotency_key, occurred_at, created_at)
                VALUES (:id, :accountId, 'COLLECT', :amount, :amount, :key, :now, :now)
                """, Map.of("id", transactionId, "accountId", accountId, "amount", request.amount(),
                "key", idempotencyKey, "now", now));
        audit.success(request.communityId(), "deposit:collect", "deposit-account", accountId, Map.of("amount", request.amount()));
        return new AccountTransactionResult(transactionId, request.amount(), false);
    }

    @Transactional
    public AccountTransactionResult refundDeposit(String accountId, AccountAmountRequest request, String idempotencyKey) {
        security.requirePermission("cashier:write");
        security.requireProject(request.communityId());
        requireKey(idempotencyKey);
        LedgerMath.positive(request.amount());
        List<Map<String, Object>> replay = jdbc.queryForList("SELECT id, balance_after FROM deposit_transaction WHERE idempotency_key=:key",
                Map.of("key", idempotencyKey));
        if (!replay.isEmpty()) return new AccountTransactionResult(String.valueOf(replay.get(0).get("id")),
                decimal(replay.get(0).get("balance_after")), true);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT * FROM deposit_account WHERE id=:id AND community_id=:communityId FOR UPDATE
                """, Map.of("id", accountId, "communityId", request.communityId()));
        if (rows.isEmpty()) throw notFound("押金账户不存在");
        BigDecimal current = decimal(rows.get(0).get("balance"));
        if (request.amount().compareTo(current) > 0) throw invalid("退还金额不能超过押金余额");
        BigDecimal balanceAfter = current.subtract(request.amount());
        String transactionId = UUID.randomUUID().toString();
        LocalDateTime now = now();
        jdbc.update("""
                INSERT INTO deposit_transaction
                    (id, account_id, transaction_type, amount, balance_after, idempotency_key, occurred_at, created_at)
                VALUES (:id, :accountId, 'REFUND', :amount, :balanceAfter, :key, :now, :now)
                """, Map.of("id", transactionId, "accountId", accountId, "amount", request.amount().negate(),
                "balanceAfter", balanceAfter, "key", idempotencyKey, "now", now));
        jdbc.update("""
                UPDATE deposit_account SET balance=:balance, status=:status, version=version+1, updated_at=:now WHERE id=:id
                """, Map.of("balance", balanceAfter, "status", balanceAfter.signum() == 0 ? "REFUNDED" : "ACTIVE", "now", now, "id", accountId));
        audit.success(request.communityId(), "deposit:refund", "deposit-account", accountId, Map.of("amount", request.amount()));
        return new AccountTransactionResult(transactionId, balanceAfter, false);
    }

    @Transactional
    public Map<String, Object> reverse(String transactionId, ReversalRequest request) {
        security.requirePermission("cashier:write");
        security.requireProject(request.communityId());
        List<Map<String, Object>> existing = jdbc.queryForList("SELECT reversal_transaction_id FROM reversal WHERE original_transaction_id=:id",
                Map.of("id", transactionId));
        if (!existing.isEmpty()) return Map.of("status", "REVERSED", "reversalTransactionId", existing.get(0).get("reversal_transaction_id"), "replayed", true);
        List<Map<String, Object>> txRows = jdbc.queryForList("""
                SELECT pt.*, po.community_id, po.id order_id FROM payment_transaction pt
                JOIN payment_order po ON po.id=pt.payment_order_id
                WHERE pt.id=:id AND po.community_id=:communityId FOR UPDATE
                """, Map.of("id", transactionId, "communityId", request.communityId()));
        if (txRows.isEmpty()) throw notFound("原交易不存在");
        Map<String, Object> tx = txRows.get(0);
        if (!"SUCCESS".equals(tx.get("status")) || !"PAYMENT".equals(tx.get("transaction_type"))) throw invalid("只有成功收款可以冲正");
        String reverseId = UUID.randomUUID().toString();
        LocalDateTime now = now();
        List<Map<String, Object>> allocations = jdbc.queryForList("SELECT bill_id, allocated_amount FROM payment_allocation WHERE payment_transaction_id=:id",
                Map.of("id", transactionId));
        BigDecimal reverseTotal = allocations.stream().map(row -> decimal(row.get("allocated_amount")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        jdbc.update("""
                INSERT INTO payment_transaction
                    (id, payment_order_id, transaction_no, adapter_code, transaction_type, status,
                     amount, external_reference, occurred_at, created_at)
                VALUES (:id, :orderId, :number, 'REVERSAL', 'REVERSAL', 'SUCCESS',
                        :amount, :reference, :now, :now)
                """, Map.of("id", reverseId, "orderId", tx.get("order_id"), "number", "REV-" + reverseId,
                "amount", reverseTotal.negate(), "reference", transactionId, "now", now));
        for (Map<String, Object> allocation : allocations) {
            BigDecimal amount = decimal(allocation.get("allocated_amount"));
            LockedBill bill = lockBill(request.communityId(), String.valueOf(allocation.get("bill_id")));
            LedgerMath.BillBalance balance = LedgerMath.reversePayment(bill.total(), bill.paid(), bill.outstanding(), amount);
            updateBill(bill.id(), bill.version(), balance);
            jdbc.update("""
                    INSERT INTO payment_allocation (id, payment_transaction_id, bill_id, allocated_amount, created_at)
                    VALUES (:id, :reverseId, :billId, :amount, :now)
                    """, Map.of("id", UUID.randomUUID().toString(), "reverseId", reverseId,
                    "billId", bill.id(), "amount", amount.negate(), "now", now));
        }
        jdbc.update("""
                INSERT INTO reversal
                    (id, original_transaction_id, reversal_transaction_id, reason, approved_by, created_at)
                VALUES (:id, :original, :reverse, :reason, :userId, :now)
                """, Map.of("id", UUID.randomUUID().toString(), "original", transactionId, "reverse", reverseId,
                "reason", request.reason(), "userId", security.requirePrincipal().userId(), "now", now));
        jdbc.update("UPDATE payment_order SET status='REVERSED', version=version+1, updated_at=:now WHERE id=:id",
                Map.of("now", now, "id", tx.get("order_id")));
        audit.success(request.communityId(), "payment:reverse", "payment-transaction", transactionId,
                Map.of("reversalTransactionId", reverseId, "amount", reverseTotal));
        return Map.of("status", "REVERSED", "reversalTransactionId", reverseId, "amount", reverseTotal, "replayed", false);
    }

    @Transactional
    public Map<String, Object> simulateInvoice(InvoiceRequest request) {
        security.requirePermission("cashier:write");
        security.requireProject(request.communityId());
        String requestNo = "INV-" + request.receiptId();
        List<Map<String, Object>> previous = jdbc.queryForList("SELECT * FROM invoice_request WHERE request_no=:requestNo",
                Map.of("requestNo", requestNo));
        if (!previous.isEmpty()) return previous.get(0);
        List<Map<String, Object>> receipts = jdbc.queryForList("""
                SELECT r.id, po.confirmed_amount FROM receipt r JOIN payment_order po ON po.id=r.payment_order_id
                WHERE r.id=:receiptId AND r.community_id=:communityId AND r.status='ISSUED'
                """, Map.of("receiptId", request.receiptId(), "communityId", request.communityId()));
        if (receipts.isEmpty()) throw notFound("收据不存在或不可开票");
        BigDecimal amount = decimal(receipts.get(0).get("confirmed_amount"));
        InvoiceAdapter.InvoiceResult result = invoiceAdapter.issue(requestNo, amount, request.title());
        String id = UUID.randomUUID().toString();
        LocalDateTime now = now();
        jdbc.update("""
                INSERT INTO invoice_request
                    (id, community_id, receipt_id, request_no, adapter_code, status, amount,
                     title_snapshot, external_reference, requested_by, created_at, updated_at)
                VALUES (:id, :communityId, :receiptId, :requestNo, :adapter, :status, :amount,
                        :title, :reference, :userId, :now, :now)
                """, new MapSqlParameterSource("id", id).addValue("communityId", request.communityId())
                .addValue("receiptId", request.receiptId()).addValue("requestNo", requestNo)
                .addValue("adapter", invoiceAdapter.code()).addValue("status", result.status())
                .addValue("amount", amount).addValue("title", request.title())
                .addValue("reference", result.externalReference())
                .addValue("userId", security.requirePrincipal().userId()).addValue("now", now));
        audit.success(request.communityId(), "invoice:simulate", "invoice-request", id, Map.of("simulated", true, "amount", amount));
        return Map.of("id", id, "requestNo", requestNo, "status", result.status(), "amount", amount,
                "adapter", invoiceAdapter.code(), "simulated", true);
    }

    private PaymentOrderResult confirmedResult(Map<String, Object> order, boolean replayed) {
        List<Map<String, Object>> transactions = jdbc.queryForList("""
                SELECT id FROM payment_transaction WHERE payment_order_id=:orderId AND transaction_type='PAYMENT' ORDER BY created_at LIMIT 1
                """, Map.of("orderId", order.get("id")));
        List<Map<String, Object>> receipts = jdbc.queryForList("SELECT id FROM receipt WHERE payment_order_id=:orderId ORDER BY created_at LIMIT 1",
                Map.of("orderId", order.get("id")));
        return new PaymentOrderResult(String.valueOf(order.get("id")), String.valueOf(order.get("order_no")),
                String.valueOf(order.get("status")), decimal(order.get("requested_amount")),
                transactions.isEmpty() ? null : String.valueOf(transactions.get(0).get("id")),
                receipts.isEmpty() ? null : String.valueOf(receipts.get(0).get("id")), replayed);
    }

    private PaymentOrderResult orderResult(Map<String, Object> order, boolean replayed) {
        if ("CONFIRMED".equals(order.get("status"))) return confirmedResult(order, replayed);
        return new PaymentOrderResult(String.valueOf(order.get("id")), String.valueOf(order.get("order_no")),
                String.valueOf(order.get("status")), decimal(order.get("requested_amount")), null, null, replayed);
    }

    private LockedBill lockBill(String communityId, String billId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, total_amount, paid_amount, outstanding_amount, version FROM bill
                WHERE id=:billId AND community_id=:communityId FOR UPDATE
                """, Map.of("billId", billId, "communityId", communityId));
        if (rows.isEmpty()) throw notFound("账单不存在或无权访问");
        Map<String, Object> row = rows.get(0);
        return new LockedBill(String.valueOf(row.get("id")), decimal(row.get("total_amount")),
                decimal(row.get("paid_amount")), decimal(row.get("outstanding_amount")), ((Number) row.get("version")).longValue());
    }

    private Map<String, Object> lockOrder(String orderId, String communityId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT * FROM payment_order WHERE id=:id AND community_id=:communityId FOR UPDATE
                """, Map.of("id", orderId, "communityId", communityId));
        if (rows.isEmpty()) throw notFound("支付订单不存在");
        return rows.get(0);
    }

    private Map<String, Object> lockPrepayment(String accountId, String communityId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT * FROM prepayment_account WHERE id=:id AND community_id=:communityId FOR UPDATE
                """, Map.of("id", accountId, "communityId", communityId));
        if (rows.isEmpty()) throw notFound("预收账户不存在");
        return rows.get(0);
    }

    private void updateBill(String billId, long version, LedgerMath.BillBalance balance) {
        int changed = jdbc.update("""
                UPDATE bill SET paid_amount=:paid, outstanding_amount=:outstanding, status=:status,
                    version=version+1, updated_at=:now WHERE id=:id AND version=:version
                """, Map.of("paid", balance.paid(), "outstanding", balance.outstanding(), "status", balance.status(),
                "now", now(), "id", billId, "version", version));
        if (changed != 1) throw new BusinessException("OPTIMISTIC_LOCK_CONFLICT", "账单已被其他收款操作更新", HttpStatus.CONFLICT);
    }

    private void createReceipt(String receiptId, String communityId, String orderId, BigDecimal amount,
                               String method, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO receipt
                    (id, community_id, payment_order_id, receipt_no, status, template_version,
                     data_snapshot, issued_at, created_at)
                VALUES (:id, :communityId, :orderId, :number, 'ISSUED', 'SYN-V1', :snapshot, :now, :now)
                """, Map.of("id", receiptId, "communityId", communityId, "orderId", orderId,
                "number", "RCT-" + receiptId, "snapshot", json(Map.of("amount", amount, "paymentMethod", method)), "now", now));
    }

    private void requireCustomer(String communityId, String customerId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM customer WHERE id=:id AND community_id=:communityId AND status='ACTIVE'",
                Map.of("id", customerId, "communityId", communityId), Long.class);
        if (count == null || count == 0) throw notFound("客户不存在或已停用");
    }

    private void outbox(String aggregateType, String aggregateId, String eventType, Object payload) {
        LocalDateTime now = now();
        jdbc.update("""
                INSERT INTO outbox_event
                    (id, aggregate_type, aggregate_id, event_type, payload_json, status, available_at, retry_count, created_at)
                VALUES (:id, :aggregateType, :aggregateId, :eventType, :payload, 'PENDING', :now, 0, :now)
                """, Map.of("id", UUID.randomUUID().toString(), "aggregateType", aggregateType, "aggregateId", aggregateId,
                "eventType", eventType, "payload", json(payload), "now", now));
    }

    private BigDecimal decimal(Object value) {
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(String.valueOf(value));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize financial snapshot", exception);
        }
    }

    private void requireKey(String key) {
        if (key == null || key.isBlank()) throw new BusinessException("IDEMPOTENCY_KEY_REQUIRED", "财务写操作必须提供 Idempotency-Key", HttpStatus.BAD_REQUEST);
    }

    private BusinessException invalid(String message) {
        return new BusinessException("INVALID_FINANCIAL_OPERATION", message, HttpStatus.BAD_REQUEST);
    }

    private BusinessException notFound(String message) {
        return new BusinessException("FINANCIAL_RECORD_NOT_FOUND", message, HttpStatus.NOT_FOUND);
    }

    private record LockedBill(String id, BigDecimal total, BigDecimal paid, BigDecimal outstanding, long version) {}

    public record BillPayment(@NotBlank String billId,
                              @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal amount) {}
    public record PaymentOrderRequest(@NotBlank String communityId, @NotBlank String paymentMethod,
                                      @NotEmpty List<@Valid BillPayment> bills) {}
    public record PaymentOrderResult(String orderId, String orderNo, String status, BigDecimal amount,
                                     String transactionId, String receiptId, boolean replayed) {}
    public record AccountRequest(@NotBlank String communityId, @NotBlank String customerId) {}
    public record AccountAmountRequest(@NotBlank String communityId,
                                       @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal amount) {}
    public record PrepaymentApply(@NotBlank String communityId, @NotEmpty List<@Valid BillPayment> bills) {}
    public record AccountTransactionResult(String transactionId, BigDecimal balanceAfter, boolean replayed) {}
    public record DepositCollect(@NotBlank String communityId, @NotBlank String customerId, String assetId,
                                 @NotBlank String depositType,
                                 @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal amount) {}
    public record ReversalRequest(@NotBlank String communityId, @NotBlank @Size(max = 500) String reason) {}
    public record InvoiceRequest(@NotBlank String communityId, @NotBlank String receiptId,
                                 @NotBlank @Size(max = 200) String title) {}
}
