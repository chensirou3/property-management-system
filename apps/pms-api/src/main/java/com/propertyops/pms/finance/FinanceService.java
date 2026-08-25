package com.propertyops.pms.finance;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

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
        String key = requireKey(idempotencyKey);
        List<BillPayment> intents = normalizedPayments(request.bills());
        String channel = normalizePaymentChannel(request.paymentMethod());
        Map<String, Object> requestSnapshot = new LinkedHashMap<>();
        requestSnapshot.put("communityId", request.communityId());
        requestSnapshot.put("paymentChannel", channel);
        requestSnapshot.put("bills", intents);
        String requestJson = json(requestSnapshot);
        String requestHash = sha256(requestJson);
        List<Map<String, Object>> previous = jdbc.queryForList("""
                SELECT id, order_no, status, requested_amount, payment_channel, cashier_shift_id, request_hash
                FROM payment_order
                WHERE community_id=:communityId AND idempotency_key=:key
                """, Map.of("communityId", request.communityId(), "key", key));
        if (!previous.isEmpty()) {
            requireSameRequest(previous.get(0).get("request_hash"), requestHash);
            return orderResult(previous.get(0), true);
        }
        BigDecimal total = BigDecimal.ZERO;
        List<LockedBill> bills = new ArrayList<>();
        for (BillPayment intent : intents) {
            LockedBill bill = lockBill(request.communityId(), intent.billId());
            if (bill.locked()) throw invalid("账单已锁定，不能收款");
            if (intent.amount().compareTo(bill.outstanding()) > 0) throw invalid("账单支付金额超过待收余额");
            bills.add(bill);
            total = total.add(intent.amount());
        }
        String shiftId = currentOpenShift(request.communityId());
        if ("CASH".equals(channel) && shiftId == null) throw invalid("现金收款前必须先开启收银交班");
        String orderId = UUID.randomUUID().toString();
        String orderNo = "PAY-" + orderId;
        LocalDateTime now = now();
        jdbc.update("""
                INSERT INTO payment_order
                    (id, community_id, order_no, idempotency_key, request_hash, request_json,
                     payment_method, payment_channel, cashier_shift_id, status,
                     requested_amount, requested_by, version, created_at, updated_at)
                VALUES (:id, :communityId, :orderNo, :key, :requestHash, :requestJson,
                        :method, :channel, :shiftId, 'PENDING',
                        :amount, :userId, 0, :now, :now)
                """, new MapSqlParameterSource("id", orderId).addValue("communityId", request.communityId())
                .addValue("orderNo", orderNo).addValue("key", key).addValue("requestHash", requestHash)
                .addValue("requestJson", requestJson).addValue("method", request.paymentMethod())
                .addValue("channel", channel).addValue("shiftId", shiftId).addValue("amount", total)
                .addValue("userId", security.requirePrincipal().userId()).addValue("now", now));
        for (BillPayment intent : intents) {
            jdbc.update("""
                    INSERT INTO payment_order_intent (id, payment_order_id, bill_id, requested_amount, created_at)
                    VALUES (:id, :orderId, :billId, :amount, :now)
                    """, Map.of("id", UUID.randomUUID().toString(), "orderId", orderId,
                    "billId", intent.billId(), "amount", intent.amount(), "now", now));
        }
        audit.success(request.communityId(), "payment-order:create", "payment-order", orderId,
                Map.of("amount", total, "billCount", bills.size(), "channel", channel));
        financialEvent(request.communityId(), "PAYMENT_ORDER", orderId, "CREATED", "payment-order:" + key,
                Map.of("amount", total, "billCount", bills.size(), "channel", channel));
        return new PaymentOrderResult(orderId, orderNo, "PENDING", total, null, null, false, channel, shiftId);
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
        String channel = String.valueOf(order.get("payment_channel"));
        String shiftId = nullableString(order.get("cashier_shift_id"));
        String transactionSnapshot = json(Map.of(
                "orderNo", order.get("order_no"), "channel", channel,
                "amount", requested, "adapter", paymentAdapter.code(),
                "simulated", adapterResult.simulated()));
        jdbc.update("""
                INSERT INTO payment_transaction
                    (id, community_id, payment_order_id, transaction_no, adapter_code, payment_channel,
                     cashier_shift_id, transaction_type, status, amount, external_reference,
                     request_key, snapshot_json, occurred_at, created_at)
                VALUES (:id, :communityId, :orderId, :transactionNo, :adapter, :channel,
                        :shiftId, 'PAYMENT', 'SUCCESS', :amount, :reference,
                        :requestKey, :snapshot, :now, :now)
                """, new MapSqlParameterSource("id", transactionId).addValue("communityId", communityId)
                .addValue("orderId", orderId).addValue("transactionNo", transactionNo)
                .addValue("adapter", paymentAdapter.code()).addValue("channel", channel)
                .addValue("shiftId", shiftId).addValue("amount", requested)
                .addValue("reference", adapterResult.externalReference()).addValue("requestKey", "confirm:" + orderId)
                .addValue("snapshot", transactionSnapshot).addValue("now", now));
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
                    confirmed_at=:now, version=version+1, updated_at=:now WHERE id=:id
                """, Map.of("amount", requested, "now", now, "id", orderId));
        String receiptId = UUID.randomUUID().toString();
        createReceipt(receiptId, communityId, orderId, requested, channel, now, null, null);
        outbox("PAYMENT_ORDER", orderId, "PaymentConfirmed", Map.of("transactionId", transactionId, "receiptId", receiptId));
        audit.success(communityId, "payment-order:confirm", "payment-order", orderId,
                Map.of("transactionId", transactionId, "receiptId", receiptId, "simulated", true));
        financialEvent(communityId, "PAYMENT_ORDER", orderId, "CONFIRMED", "confirm:" + orderId,
                Map.of("transactionId", transactionId, "receiptId", receiptId, "amount", requested, "channel", channel));
        return new PaymentOrderResult(orderId, String.valueOf(order.get("order_no")), "CONFIRMED", requested,
                transactionId, receiptId, false, channel, shiftId);
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
        String key = requireKey(idempotencyKey);
        LedgerMath.positive(request.amount());
        String requestJson = json(Map.of("communityId", request.communityId(), "accountId", accountId,
                "amount", request.amount(), "reason", request.reason() == null ? "" : request.reason()));
        String requestHash = sha256(requestJson);
        List<Map<String, Object>> replay = jdbc.queryForList("""
                SELECT id, balance_after, request_hash FROM prepayment_transaction
                WHERE account_id=:accountId AND idempotency_key=:key
                """, Map.of("accountId", accountId, "key", key));
        if (!replay.isEmpty()) {
            requireSameRequest(replay.get(0).get("request_hash"), requestHash);
            return new AccountTransactionResult(accountId, String.valueOf(replay.get(0).get("id")),
                    decimal(replay.get(0).get("balance_after")), true);
        }
        Map<String, Object> account = lockPrepayment(accountId, request.communityId());
        BigDecimal balanceAfter = decimal(account.get("balance")).add(request.amount());
        String transactionId = UUID.randomUUID().toString();
        LocalDateTime now = now();
        jdbc.update("""
                INSERT INTO prepayment_transaction
                    (id, account_id, transaction_type, amount, balance_after, reference_type,
                     idempotency_key, request_hash, request_json, reason, created_by, occurred_at, created_at)
                VALUES (:id, :accountId, 'TOP_UP', :amount, :balanceAfter, 'LOCAL', :key,
                        :requestHash, :requestJson, :reason, :userId, :now, :now)
                """, Map.of("id", transactionId, "accountId", accountId, "amount", request.amount(),
                "balanceAfter", balanceAfter, "key", key, "requestHash", requestHash,
                "requestJson", requestJson, "reason", request.reason() == null ? "本地预收充值" : request.reason(),
                "userId", security.requirePrincipal().userId(), "now", now));
        jdbc.update("""
                UPDATE prepayment_account SET balance=:balance, version=version+1, updated_at=:now
                WHERE id=:id AND version=:version
                """, Map.of("balance", balanceAfter, "now", now, "id", accountId, "version", account.get("version")));
        audit.success(request.communityId(), "prepayment:top-up", "prepayment-account", accountId,
                Map.of("amount", request.amount(), "balanceAfter", balanceAfter));
        financialEvent(request.communityId(), "PREPAYMENT", accountId, "TOPPED_UP", "prepayment-topup:" + key,
                Map.of("transactionId", transactionId, "amount", request.amount(), "balanceAfter", balanceAfter));
        return new AccountTransactionResult(accountId, transactionId, balanceAfter, false);
    }

    @Transactional
    public PaymentOrderResult applyPrepayment(String accountId, PrepaymentApply request, String idempotencyKey) {
        security.requirePermission("cashier:write");
        security.requireProject(request.communityId());
        String key = requireKey(idempotencyKey);
        List<BillPayment> intents = normalizedPayments(request.bills());
        String requestJson = json(Map.of("communityId", request.communityId(), "accountId", accountId, "bills", intents));
        String requestHash = sha256(requestJson);
        List<Map<String, Object>> replay = jdbc.queryForList("""
                SELECT reference_id, request_hash FROM prepayment_transaction
                WHERE account_id=:accountId AND idempotency_key=:key AND transaction_type='DEDUCT'
                """, Map.of("accountId", accountId, "key", key));
        if (!replay.isEmpty()) {
            requireSameRequest(replay.get(0).get("request_hash"), requestHash);
            Map<String, Object> order = lockOrder(String.valueOf(replay.get(0).get("reference_id")), request.communityId());
            return confirmedResult(order, true);
        }
        Map<String, Object> account = lockPrepayment(accountId, request.communityId());
        BigDecimal total = intents.stream().map(BillPayment::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        LedgerMath.positive(total);
        if (total.compareTo(decimal(account.get("balance"))) > 0) throw invalid("预收余额不足");
        List<LockedBill> lockedBills = new ArrayList<>();
        for (BillPayment intent : intents) {
            LockedBill bill = lockBill(request.communityId(), intent.billId());
            if (bill.locked()) throw invalid("账单已锁定，不能使用预收款");
            if (intent.amount().compareTo(bill.outstanding()) > 0) throw invalid("预收抵扣金额超过待收余额");
            lockedBills.add(bill);
        }
        String orderId = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
        String receiptId = UUID.randomUUID().toString();
        LocalDateTime now = now();
        String orderRequestKey = "PREPAY-" + sha256(key).substring(0, 32);
        jdbc.update("""
                INSERT INTO payment_order
                    (id, community_id, order_no, idempotency_key, request_hash, request_json,
                     payment_method, payment_channel, status, requested_amount, confirmed_amount,
                     confirmed_at, requested_by, version, created_at, updated_at)
                VALUES (:id, :communityId, :orderNo, :key, :requestHash, :requestJson,
                        'PREPAYMENT', 'PREPAYMENT', 'CONFIRMED', :amount, :amount,
                        :now, :userId, 0, :now, :now)
                """, Map.of("id", orderId, "communityId", request.communityId(), "orderNo", "PAY-" + orderId,
                "key", orderRequestKey, "requestHash", requestHash, "requestJson", requestJson,
                "amount", total, "userId", security.requirePrincipal().userId(), "now", now));
        jdbc.update("""
                INSERT INTO payment_transaction
                    (id, community_id, payment_order_id, transaction_no, adapter_code, payment_channel,
                     transaction_type, status, amount, external_reference, request_key, snapshot_json,
                     occurred_at, created_at)
                VALUES (:id, :communityId, :orderId, :transactionNo, 'PREPAYMENT_ACCOUNT', 'PREPAYMENT',
                        'PAYMENT', 'SUCCESS', :amount, :reference, :requestKey, :snapshot, :now, :now)
                """, Map.of("id", transactionId, "orderId", orderId, "transactionNo", "TXN-" + transactionId,
                "communityId", request.communityId(), "amount", total, "reference", accountId,
                "requestKey", "prepayment-payment:" + key,
                "snapshot", json(Map.of("accountId", accountId, "bills", intents, "amount", total)), "now", now));
        for (int index = 0; index < intents.size(); index++) {
            BillPayment intent = intents.get(index);
            LockedBill bill = lockedBills.get(index);
            jdbc.update("""
                    INSERT INTO payment_order_intent (id, payment_order_id, bill_id, requested_amount, created_at)
                    VALUES (:id, :orderId, :billId, :amount, :now)
                    """, Map.of("id", UUID.randomUUID().toString(), "orderId", orderId,
                    "billId", intent.billId(), "amount", intent.amount(), "now", now));
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
                     idempotency_key, request_hash, request_json, created_by, occurred_at, created_at)
                VALUES (:id, :accountId, 'DEDUCT', :amount, :balanceAfter, 'PAYMENT_ORDER', :orderId,
                        :key, :requestHash, :requestJson, :userId, :now, :now)
                """, Map.of("id", UUID.randomUUID().toString(), "accountId", accountId, "amount", total.negate(),
                "balanceAfter", balanceAfter, "orderId", orderId, "key", key, "requestHash", requestHash,
                "requestJson", requestJson, "userId", security.requirePrincipal().userId(), "now", now));
        int accountChanged = jdbc.update("""
                UPDATE prepayment_account SET balance=:balance, version=version+1, updated_at=:now
                WHERE id=:id AND version=:version
                """, Map.of("balance", balanceAfter, "now", now, "id", accountId, "version", account.get("version")));
        if (accountChanged != 1) throw conflict("预收账户已被其他操作更新");
        createReceipt(receiptId, request.communityId(), orderId, total, "PREPAYMENT", now, null, null);
        outbox("PAYMENT_ORDER", orderId, "PrepaymentApplied", Map.of("transactionId", transactionId, "receiptId", receiptId));
        audit.success(request.communityId(), "prepayment:apply", "prepayment-account", accountId,
                Map.of("amount", total, "orderId", orderId));
        financialEvent(request.communityId(), "PREPAYMENT", accountId, "APPLIED", "prepayment-apply:" + key,
                Map.of("amount", total, "orderId", orderId, "transactionId", transactionId));
        return new PaymentOrderResult(orderId, "PAY-" + orderId, "CONFIRMED", total,
                transactionId, receiptId, false, "PREPAYMENT", null);
    }

    @Transactional
    public AccountTransactionResult collectDeposit(DepositCollect request, String idempotencyKey) {
        security.requirePermission("finance:deposit-write");
        security.requireProject(request.communityId());
        String key = requireKey(idempotencyKey);
        LedgerMath.positive(request.amount());
        requireCustomer(request.communityId(), request.customerId());
        requireAsset(request.communityId(), request.assetId());
        String requestJson = json(Map.of(
                "communityId", request.communityId(), "customerId", request.customerId(),
                "assetId", request.assetId() == null ? "" : request.assetId(), "depositType", request.depositType(),
                "amount", request.amount(), "reason", request.reason() == null ? "" : request.reason()));
        String requestHash = sha256(requestJson);
        MapSqlParameterSource identity = new MapSqlParameterSource("communityId", request.communityId())
                .addValue("customerId", request.customerId()).addValue("assetId", request.assetId())
                .addValue("type", request.depositType());
        List<Map<String, Object>> accounts = jdbc.queryForList("""
                SELECT * FROM deposit_account
                WHERE community_id=:communityId AND customer_id=:customerId
                  AND asset_id <=> :assetId AND deposit_type=:type FOR UPDATE
                """, identity);
        String accountId;
        Map<String, Object> account;
        LocalDateTime now = now();
        if (accounts.isEmpty()) {
            accountId = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO deposit_account
                        (id, community_id, customer_id, asset_id, deposit_type, balance, status,
                         version, created_at, updated_at)
                    VALUES (:id, :communityId, :customerId, :assetId, :type, 0, 'ACTIVE', 0, :now, :now)
                    """, new MapSqlParameterSource("id", accountId).addValue("communityId", request.communityId())
                    .addValue("customerId", request.customerId()).addValue("assetId", request.assetId())
                    .addValue("type", request.depositType()).addValue("now", now));
            account = jdbc.queryForMap("SELECT * FROM deposit_account WHERE id=:id FOR UPDATE", Map.of("id", accountId));
        } else {
            account = accounts.get(0);
            accountId = String.valueOf(account.get("id"));
        }
        List<Map<String, Object>> replay = jdbc.queryForList("""
                SELECT id, balance_after, request_hash FROM deposit_transaction
                WHERE account_id=:accountId AND idempotency_key=:key
                """, Map.of("accountId", accountId, "key", key));
        if (!replay.isEmpty()) {
            requireSameRequest(replay.get(0).get("request_hash"), requestHash);
            return new AccountTransactionResult(accountId, String.valueOf(replay.get(0).get("id")),
                    decimal(replay.get(0).get("balance_after")), true);
        }
        BigDecimal balanceAfter = decimal(account.get("balance")).add(request.amount());
        String transactionId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO deposit_transaction
                    (id, account_id, transaction_type, amount, balance_after, idempotency_key,
                     request_hash, request_json, reason, created_by, occurred_at, created_at)
                VALUES (:id, :accountId, 'COLLECT', :amount, :balanceAfter, :key,
                        :requestHash, :requestJson, :reason, :userId, :now, :now)
                """, Map.of("id", transactionId, "accountId", accountId, "amount", request.amount(),
                "balanceAfter", balanceAfter, "key", key, "requestHash", requestHash, "requestJson", requestJson,
                "reason", request.reason() == null ? "本地押金收取" : request.reason(),
                "userId", security.requirePrincipal().userId(), "now", now));
        int changed = jdbc.update("""
                UPDATE deposit_account SET balance=:balance, status='ACTIVE', version=version+1, updated_at=:now
                WHERE id=:id AND version=:version
                """, Map.of("balance", balanceAfter, "now", now, "id", accountId, "version", account.get("version")));
        if (changed != 1) throw conflict("押金账户已被其他操作更新");
        audit.success(request.communityId(), "deposit:collect", "deposit-account", accountId,
                Map.of("amount", request.amount(), "balanceAfter", balanceAfter));
        financialEvent(request.communityId(), "DEPOSIT", accountId, "COLLECTED", "deposit-collect:" + key,
                Map.of("transactionId", transactionId, "amount", request.amount(), "balanceAfter", balanceAfter));
        return new AccountTransactionResult(accountId, transactionId, balanceAfter, false);
    }

    @Transactional
    public AccountTransactionResult refundDeposit(String accountId, AccountAmountRequest request, String idempotencyKey) {
        security.requirePermission("finance:deposit-write");
        security.requireProject(request.communityId());
        String key = requireKey(idempotencyKey);
        LedgerMath.positive(request.amount());
        String requestJson = json(Map.of("communityId", request.communityId(), "accountId", accountId,
                "amount", request.amount(), "reason", request.reason() == null ? "" : request.reason()));
        String requestHash = sha256(requestJson);
        List<Map<String, Object>> replay = jdbc.queryForList("""
                SELECT id, balance_after, request_hash FROM deposit_transaction
                WHERE account_id=:accountId AND idempotency_key=:key
                """, Map.of("accountId", accountId, "key", key));
        if (!replay.isEmpty()) {
            requireSameRequest(replay.get(0).get("request_hash"), requestHash);
            return new AccountTransactionResult(accountId, String.valueOf(replay.get(0).get("id")),
                    decimal(replay.get(0).get("balance_after")), true);
        }
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
                    (id, account_id, transaction_type, amount, balance_after, idempotency_key,
                     request_hash, request_json, reason, created_by, occurred_at, created_at)
                VALUES (:id, :accountId, 'REFUND', :amount, :balanceAfter, :key,
                        :requestHash, :requestJson, :reason, :userId, :now, :now)
                """, Map.of("id", transactionId, "accountId", accountId, "amount", request.amount().negate(),
                "balanceAfter", balanceAfter, "key", key, "requestHash", requestHash, "requestJson", requestJson,
                "reason", request.reason() == null ? "本地押金退还" : request.reason(),
                "userId", security.requirePrincipal().userId(), "now", now));
        int changed = jdbc.update("""
                UPDATE deposit_account SET balance=:balance, status=:status, version=version+1, updated_at=:now
                WHERE id=:id AND version=:version
                """, Map.of("balance", balanceAfter, "status", balanceAfter.signum() == 0 ? "REFUNDED" : "ACTIVE",
                "now", now, "id", accountId, "version", rows.get(0).get("version")));
        if (changed != 1) throw conflict("押金账户已被其他操作更新");
        audit.success(request.communityId(), "deposit:refund", "deposit-account", accountId, Map.of("amount", request.amount()));
        financialEvent(request.communityId(), "DEPOSIT", accountId, "REFUNDED", "deposit-refund:" + key,
                Map.of("transactionId", transactionId, "amount", request.amount(), "balanceAfter", balanceAfter));
        return new AccountTransactionResult(accountId, transactionId, balanceAfter, false);
    }

    @Transactional
    public Map<String, Object> reverse(String transactionId, ReversalRequest request) {
        security.requirePermission("finance:reverse");
        security.requireProject(request.communityId());
        List<Map<String, Object>> existing = jdbc.queryForList("SELECT reversal_transaction_id FROM reversal WHERE original_transaction_id=:id",
                Map.of("id", transactionId));
        if (!existing.isEmpty()) return Map.of("status", "REVERSED", "reversalTransactionId", existing.get(0).get("reversal_transaction_id"), "replayed", true);
        List<Map<String, Object>> txRows = jdbc.queryForList("""
                SELECT pt.*, po.community_id, po.id order_id, ds.status settlement_status
                FROM payment_transaction pt
                JOIN payment_order po ON po.id=pt.payment_order_id
                LEFT JOIN daily_settlement ds ON ds.id=pt.settlement_id
                WHERE pt.id=:id AND po.community_id=:communityId FOR UPDATE
                """, Map.of("id", transactionId, "communityId", request.communityId()));
        if (txRows.isEmpty()) throw notFound("原交易不存在");
        Map<String, Object> tx = txRows.get(0);
        if (!"SUCCESS".equals(tx.get("status")) || !"PAYMENT".equals(tx.get("transaction_type"))) throw invalid("只有成功收款可以冲正");
        if ("LOCKED".equals(tx.get("settlement_status"))) throw conflict("交易所属日结已锁定，不能直接冲正");
        String reverseId = UUID.randomUUID().toString();
        LocalDateTime now = now();
        List<Map<String, Object>> allocations = jdbc.queryForList("SELECT bill_id, allocated_amount FROM payment_allocation WHERE payment_transaction_id=:id",
                Map.of("id", transactionId));
        BigDecimal reverseTotal = allocations.stream().map(row -> decimal(row.get("allocated_amount")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (reverseTotal.signum() <= 0) throw invalid("原交易没有可冲正的账单分配");
        String reverseSnapshot = json(Map.of("originalTransactionId", transactionId,
                "amount", reverseTotal, "reason", request.reason()));
        jdbc.update("""
                INSERT INTO payment_transaction
                    (id, community_id, payment_order_id, transaction_no, adapter_code, payment_channel,
                     cashier_shift_id, original_transaction_id, transaction_type, status, amount,
                     external_reference, request_key, snapshot_json, occurred_at, created_at)
                VALUES (:id, :communityId, :orderId, :number, 'REVERSAL', :channel,
                        :shiftId, :original, 'REVERSAL', 'SUCCESS', :amount,
                        :reference, :requestKey, :snapshot, :now, :now)
                """, new MapSqlParameterSource("id", reverseId).addValue("orderId", tx.get("order_id"))
                .addValue("number", "REV-" + reverseId).addValue("communityId", request.communityId())
                .addValue("channel", tx.get("payment_channel")).addValue("shiftId", tx.get("cashier_shift_id"))
                .addValue("original", transactionId).addValue("amount", reverseTotal.negate())
                .addValue("reference", transactionId).addValue("requestKey", "reverse:" + transactionId)
                .addValue("snapshot", reverseSnapshot).addValue("now", now));
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
        if ("PREPAYMENT_ACCOUNT".equals(tx.get("adapter_code"))) {
            reversePrepaymentDeduction(request.communityId(), String.valueOf(tx.get("order_id")), transactionId,
                    reverseTotal, request.reason(), now);
        }
        jdbc.update("""
                INSERT INTO reversal
                    (id, original_transaction_id, reversal_transaction_id, reason, approved_by, created_at)
                VALUES (:id, :original, :reverse, :reason, :userId, :now)
                """, Map.of("id", UUID.randomUUID().toString(), "original", transactionId, "reverse", reverseId,
                "reason", request.reason(), "userId", security.requirePrincipal().userId(), "now", now));
        jdbc.update("UPDATE payment_order SET status='REVERSED', version=version+1, updated_at=:now WHERE id=:id",
                Map.of("now", now, "id", tx.get("order_id")));
        jdbc.update("""
                UPDATE receipt SET status='VOIDED', event_reason=:reason, voided_at=:now, voided_by=:userId
                WHERE payment_order_id=:orderId AND status='ISSUED'
                """, Map.of("reason", request.reason(), "now", now,
                "userId", security.requirePrincipal().userId(), "orderId", tx.get("order_id")));
        outbox("PAYMENT_ORDER", String.valueOf(tx.get("order_id")), "PaymentReversed",
                Map.of("originalTransactionId", transactionId, "reversalTransactionId", reverseId));
        audit.success(request.communityId(), "payment:reverse", "payment-transaction", transactionId,
                Map.of("reversalTransactionId", reverseId, "amount", reverseTotal));
        financialEvent(request.communityId(), "PAYMENT_TRANSACTION", transactionId, "REVERSED",
                "reverse:" + transactionId, Map.of("reversalTransactionId", reverseId,
                        "amount", reverseTotal, "reason", request.reason()));
        return Map.of("status", "REVERSED", "reversalTransactionId", reverseId, "amount", reverseTotal, "replayed", false);
    }

    @Transactional
    public Map<String, Object> simulateInvoice(InvoiceRequest request) {
        security.requirePermission("invoice:write");
        security.requireProject(request.communityId());
        String requestNo = "INV-" + request.receiptId();
        String requestJson = json(Map.of("communityId", request.communityId(), "receiptId", request.receiptId(),
                "title", request.title(), "operationType", "ISSUE"));
        String requestHash = sha256(requestJson);
        List<Map<String, Object>> previous = jdbc.queryForList("SELECT * FROM invoice_request WHERE request_no=:requestNo",
                Map.of("requestNo", requestNo));
        if (!previous.isEmpty()) {
            requireSameRequest(previous.get(0).get("request_hash"), requestHash);
            return previous.get(0);
        }
        List<Map<String, Object>> receipts = jdbc.queryForList("""
                SELECT r.id, po.confirmed_amount FROM receipt r JOIN payment_order po ON po.id=r.payment_order_id
                WHERE r.id=:receiptId AND r.community_id=:communityId AND r.status='ISSUED'
                """, Map.of("receiptId", request.receiptId(), "communityId", request.communityId()));
        if (receipts.isEmpty()) throw notFound("收据不存在或不可开票");
        BigDecimal amount = decimal(receipts.get(0).get("confirmed_amount"));
        InvoiceAdapter.InvoiceResult result = invoiceAdapter.issue(requestNo, amount, request.title());
        String id = UUID.randomUUID().toString();
        LocalDateTime now = now();
        String snapshotJson = json(Map.of("receiptId", request.receiptId(), "title", request.title(),
                "amount", amount, "adapter", invoiceAdapter.code(), "simulated", true));
        jdbc.update("""
                INSERT INTO invoice_request
                    (id, community_id, receipt_id, request_no, operation_type, request_hash,
                     adapter_code, status, amount, title_snapshot, snapshot_json, snapshot_checksum,
                     external_reference, requested_by, created_at, updated_at)
                VALUES (:id, :communityId, :receiptId, :requestNo, 'ISSUE', :requestHash,
                        :adapter, :status, :amount, :title, :snapshot, :checksum,
                        :reference, :userId, :now, :now)
                """, new MapSqlParameterSource("id", id).addValue("communityId", request.communityId())
                .addValue("receiptId", request.receiptId()).addValue("requestNo", requestNo)
                .addValue("requestHash", requestHash)
                .addValue("adapter", invoiceAdapter.code()).addValue("status", result.status())
                .addValue("amount", amount).addValue("title", request.title())
                .addValue("snapshot", snapshotJson).addValue("checksum", sha256(snapshotJson))
                .addValue("reference", result.externalReference())
                .addValue("userId", security.requirePrincipal().userId()).addValue("now", now));
        jdbc.update("UPDATE invoice_request SET snapshot_checksum=SHA2(snapshot_json, 256) WHERE id=:id",
                Map.of("id", id));
        audit.success(request.communityId(), "invoice:simulate", "invoice-request", id, Map.of("simulated", true, "amount", amount));
        financialEvent(request.communityId(), "INVOICE", id, "ISSUED", "invoice:" + request.receiptId(),
                Map.of("receiptId", request.receiptId(), "amount", amount, "simulated", true));
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
                receipts.isEmpty() ? null : String.valueOf(receipts.get(0).get("id")), replayed,
                String.valueOf(order.get("payment_channel")), nullableString(order.get("cashier_shift_id")));
    }

    private PaymentOrderResult orderResult(Map<String, Object> order, boolean replayed) {
        if ("CONFIRMED".equals(order.get("status"))) return confirmedResult(order, replayed);
        return new PaymentOrderResult(String.valueOf(order.get("id")), String.valueOf(order.get("order_no")),
                String.valueOf(order.get("status")), decimal(order.get("requested_amount")), null, null, replayed,
                String.valueOf(order.get("payment_channel")), nullableString(order.get("cashier_shift_id")));
    }

    private LockedBill lockBill(String communityId, String billId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, total_amount, paid_amount, outstanding_amount, locked, version FROM bill
                WHERE id=:billId AND community_id=:communityId FOR UPDATE
                """, Map.of("billId", billId, "communityId", communityId));
        if (rows.isEmpty()) throw notFound("账单不存在或无权访问");
        Map<String, Object> row = rows.get(0);
        return new LockedBill(String.valueOf(row.get("id")), decimal(row.get("total_amount")),
                decimal(row.get("paid_amount")), decimal(row.get("outstanding_amount")),
                Boolean.TRUE.equals(row.get("locked")), ((Number) row.get("version")).longValue());
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
                               String method, LocalDateTime now, String originalReceiptId, String reason) {
        ReceiptNumber number = nextReceiptNumber(communityId, now);
        String snapshot = json(Map.of("amount", amount, "paymentMethod", method,
                "simulated", true, "orderId", orderId));
        jdbc.update("""
                INSERT INTO receipt
                    (id, community_id, payment_order_id, segment_id, sequence_no, receipt_no,
                     status, template_version, data_snapshot, snapshot_checksum, original_receipt_id,
                     event_reason, issued_at, created_at)
                VALUES (:id, :communityId, :orderId, :segmentId, :sequenceNo, :number,
                        'ISSUED', 'SYN-V2', :snapshot, :checksum, :originalReceiptId,
                        :reason, :now, :now)
                """, new MapSqlParameterSource("id", receiptId).addValue("communityId", communityId)
                .addValue("orderId", orderId).addValue("segmentId", number.segmentId())
                .addValue("sequenceNo", number.sequenceNo()).addValue("number", number.receiptNo())
                .addValue("snapshot", snapshot).addValue("checksum", sha256(snapshot))
                .addValue("originalReceiptId", originalReceiptId).addValue("reason", reason).addValue("now", now));
    }

    private ReceiptNumber nextReceiptNumber(String communityId, LocalDateTime now) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, number_prefix, next_no, end_no, version FROM receipt_number_segment
                WHERE community_id=:communityId AND status='ACTIVE' AND next_no<=end_no
                ORDER BY created_at, id LIMIT 1 FOR UPDATE
                """, Map.of("communityId", communityId));
        if (rows.isEmpty()) throw conflict("没有可用的收据号段");
        Map<String, Object> row = rows.get(0);
        long sequence = ((Number) row.get("next_no")).longValue();
        long end = ((Number) row.get("end_no")).longValue();
        int changed = jdbc.update("""
                UPDATE receipt_number_segment
                SET next_no=:nextNo, status=:status, version=version+1, updated_at=:now
                WHERE id=:id AND version=:version
                """, Map.of("nextNo", sequence + 1, "status", sequence == end ? "EXHAUSTED" : "ACTIVE",
                "now", now, "id", row.get("id"), "version", row.get("version")));
        if (changed != 1) throw conflict("收据号段已被其他收银操作更新");
        return new ReceiptNumber(String.valueOf(row.get("id")), sequence,
                String.valueOf(row.get("number_prefix")) + String.format("%06d", sequence));
    }

    private void reversePrepaymentDeduction(String communityId, String orderId, String paymentTransactionId,
                                            BigDecimal amount, String reason, LocalDateTime now) {
        List<Map<String, Object>> deductions = jdbc.queryForList("""
                SELECT pt.*, pa.community_id, pa.version account_version, pa.balance account_balance
                FROM prepayment_transaction pt
                JOIN prepayment_account pa ON pa.id=pt.account_id
                WHERE pt.reference_type='PAYMENT_ORDER' AND pt.reference_id=:orderId
                  AND pt.transaction_type='DEDUCT' AND pa.community_id=:communityId
                FOR UPDATE
                """, Map.of("orderId", orderId, "communityId", communityId));
        if (deductions.size() != 1) throw conflict("预收抵扣交易缺失，不能完成守恒冲正");
        Map<String, Object> deduction = deductions.get(0);
        String accountId = String.valueOf(deduction.get("account_id"));
        String reversalKey = "reverse:" + paymentTransactionId;
        BigDecimal balanceAfter = decimal(deduction.get("account_balance")).add(amount);
        String requestJson = json(Map.of("originalPaymentTransactionId", paymentTransactionId,
                "originalPrepaymentTransactionId", deduction.get("id"), "amount", amount, "reason", reason));
        jdbc.update("""
                INSERT INTO prepayment_transaction
                    (id, account_id, transaction_type, amount, balance_after, reference_type, reference_id,
                     idempotency_key, request_hash, request_json, original_transaction_id, reason,
                     created_by, occurred_at, created_at)
                VALUES (:id, :accountId, 'REVERSAL', :amount, :balanceAfter,
                        'PAYMENT_TRANSACTION', :referenceId, :key, :requestHash, :requestJson,
                        :original, :reason, :userId, :now, :now)
                """, new MapSqlParameterSource("id", UUID.randomUUID().toString()).addValue("accountId", accountId)
                .addValue("amount", amount).addValue("balanceAfter", balanceAfter)
                .addValue("referenceId", paymentTransactionId).addValue("key", reversalKey)
                .addValue("requestHash", sha256(requestJson)).addValue("requestJson", requestJson)
                .addValue("original", deduction.get("id")).addValue("reason", reason)
                .addValue("userId", security.requirePrincipal().userId()).addValue("now", now));
        int changed = jdbc.update("""
                UPDATE prepayment_account SET balance=:balance, version=version+1, updated_at=:now
                WHERE id=:id AND version=:version
                """, Map.of("balance", balanceAfter, "now", now, "id", accountId,
                "version", deduction.get("account_version")));
        if (changed != 1) throw conflict("预收账户已被其他冲正操作更新");
    }

    private void requireCustomer(String communityId, String customerId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM customer WHERE id=:id AND community_id=:communityId AND status='ACTIVE'",
                Map.of("id", customerId, "communityId", communityId), Long.class);
        if (count == null || count == 0) throw notFound("客户不存在或已停用");
    }

    private void requireAsset(String communityId, String assetId) {
        if (assetId == null || assetId.isBlank()) return;
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM asset
                WHERE id=:id AND community_id=:communityId AND enabled=TRUE
                """, Map.of("id", assetId, "communityId", communityId), Long.class);
        if (count == null || count == 0) throw notFound("押金关联资产不存在、已停用或不属于当前项目");
    }

    private String currentOpenShift(String communityId) {
        List<String> shifts = jdbc.queryForList("""
                SELECT id FROM cashier_shift
                WHERE community_id=:communityId AND cashier_user_id=:userId AND status='OPEN'
                ORDER BY opened_at DESC LIMIT 1
                """, Map.of("communityId", communityId, "userId", security.requirePrincipal().userId()), String.class);
        return shifts.isEmpty() ? null : shifts.get(0);
    }

    private List<BillPayment> normalizedPayments(List<BillPayment> payments) {
        if (payments == null || payments.isEmpty()) throw invalid("至少选择一张账单");
        if (payments.size() > 200) throw invalid("单次最多处理 200 张账单");
        Set<String> billIds = new HashSet<>();
        List<BillPayment> normalized = new ArrayList<>();
        for (BillPayment payment : payments) {
            LedgerMath.positive(payment.amount());
            if (!billIds.add(payment.billId())) throw invalid("同一账单不能重复选择");
            normalized.add(new BillPayment(payment.billId(), payment.amount()));
        }
        normalized.sort(Comparator.comparing(BillPayment::billId));
        return List.copyOf(normalized);
    }

    private String normalizePaymentChannel(String value) {
        String channel = value == null ? "" : value.trim().toUpperCase();
        return switch (channel) {
            case "CASH", "BANK_TRANSFER", "QR_SIMULATOR", "CASH_SIMULATOR", "SIMULATOR", "PREPAYMENT" -> channel;
            default -> throw invalid("不支持的收款渠道");
        };
    }

    private void requireSameRequest(Object storedHash, String requestHash) {
        if (storedHash == null || !MessageDigest.isEqual(
                String.valueOf(storedHash).getBytes(StandardCharsets.UTF_8),
                requestHash.getBytes(StandardCharsets.UTF_8))) {
            throw conflict("同一 Idempotency-Key 不能用于不同财务请求");
        }
    }

    private void financialEvent(String communityId, String aggregateType, String aggregateId,
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

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String nullableString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new BusinessException("IDEMPOTENCY_KEY_REQUIRED", "财务写操作必须提供 Idempotency-Key", HttpStatus.BAD_REQUEST);
        }
        String normalized = key.trim();
        if (normalized.length() > 80) throw invalid("财务 Idempotency-Key 最长为 80 个字符");
        return normalized;
    }

    private BusinessException invalid(String message) {
        return new BusinessException("INVALID_FINANCIAL_OPERATION", message, HttpStatus.BAD_REQUEST);
    }

    private BusinessException notFound(String message) {
        return new BusinessException("FINANCIAL_RECORD_NOT_FOUND", message, HttpStatus.NOT_FOUND);
    }

    private BusinessException conflict(String message) {
        return new BusinessException("FINANCIAL_CONFLICT", message, HttpStatus.CONFLICT);
    }

    private record LockedBill(String id, BigDecimal total, BigDecimal paid, BigDecimal outstanding,
                              boolean locked, long version) {}
    private record ReceiptNumber(String segmentId, long sequenceNo, String receiptNo) {}

    public record BillPayment(@NotBlank String billId,
                              @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal amount) {}
    public record PaymentOrderRequest(@NotBlank String communityId, @NotBlank String paymentMethod,
                                      @NotEmpty List<@Valid BillPayment> bills) {}
    public record PaymentOrderResult(String orderId, String orderNo, String status, BigDecimal amount,
                                     String transactionId, String receiptId, boolean replayed,
                                     String paymentChannel, String cashierShiftId) {}
    public record AccountRequest(@NotBlank String communityId, @NotBlank String customerId) {}
    public record AccountAmountRequest(@NotBlank String communityId,
                                       @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal amount,
                                       @Size(max = 500) String reason) {
        public AccountAmountRequest(String communityId, BigDecimal amount) {
            this(communityId, amount, null);
        }
    }
    public record PrepaymentApply(@NotBlank String communityId, @NotEmpty List<@Valid BillPayment> bills) {}
    public record AccountTransactionResult(String accountId, String transactionId,
                                           BigDecimal balanceAfter, boolean replayed) {}
    public record DepositCollect(@NotBlank String communityId, @NotBlank String customerId, String assetId,
                                 @NotBlank String depositType,
                                 @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal amount,
                                 @Size(max = 500) String reason) {}
    public record ReversalRequest(@NotBlank String communityId, @NotBlank @Size(max = 500) String reason) {}
    public record InvoiceRequest(@NotBlank String communityId, @NotBlank String receiptId,
                                 @NotBlank @Size(max = 200) String title) {}
}
