-- G6 governed cashier, immutable financial ledger, adjustments and settlement.

ALTER TABLE bill
    ADD COLUMN original_amount DECIMAL(18,2) NULL AFTER status,
    ADD COLUMN adjustment_amount DECIMAL(18,2) NOT NULL DEFAULT 0 AFTER original_amount,
    ADD COLUMN locked BOOLEAN NOT NULL DEFAULT FALSE AFTER outstanding_amount,
    ADD COLUMN lock_reason VARCHAR(500) NULL AFTER locked;
UPDATE bill SET original_amount=total_amount;
ALTER TABLE bill
    MODIFY original_amount DECIMAL(18,2) NOT NULL,
    ADD CONSTRAINT ck_bill_adjustment_balance CHECK (total_amount=original_amount+adjustment_amount),
    ADD CONSTRAINT ck_bill_lock_reason CHECK (locked=FALSE OR lock_reason IS NOT NULL);

CREATE TABLE cashier_shift (
    id VARCHAR(36) PRIMARY KEY,
    community_id VARCHAR(36) NOT NULL,
    shift_no VARCHAR(80) NOT NULL,
    cashier_user_id VARCHAR(36) NOT NULL,
    status VARCHAR(20) NOT NULL,
    opening_cash DECIMAL(18,2) NOT NULL DEFAULT 0,
    expected_cash DECIMAL(18,2) NOT NULL DEFAULT 0,
    actual_cash DECIMAL(18,2),
    variance_amount DECIMAL(18,2),
    request_key VARCHAR(120) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    request_json JSON NOT NULL,
    snapshot_json JSON,
    snapshot_checksum CHAR(64),
    opened_at DATETIME(3) NOT NULL,
    closed_at DATETIME(3),
    locked_at DATETIME(3),
    locked_by VARCHAR(36),
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    open_cashier_identity VARCHAR(80) GENERATED ALWAYS AS (
        CASE WHEN status='OPEN' THEN CONCAT(community_id, '|', cashier_user_id) ELSE NULL END
    ) STORED,
    CONSTRAINT uk_cashier_shift_no UNIQUE (community_id, shift_no),
    CONSTRAINT uk_cashier_shift_request UNIQUE (community_id, request_key),
    CONSTRAINT uk_cashier_open_shift UNIQUE (open_cashier_identity),
    CONSTRAINT fk_cashier_shift_community FOREIGN KEY (community_id) REFERENCES community(id),
    CONSTRAINT fk_cashier_shift_user FOREIGN KEY (cashier_user_id) REFERENCES sys_user(id),
    CONSTRAINT fk_cashier_shift_locker FOREIGN KEY (locked_by) REFERENCES sys_user(id),
    CONSTRAINT ck_cashier_shift_status CHECK (status IN ('OPEN', 'CLOSED', 'LOCKED')),
    CONSTRAINT ck_cashier_shift_amounts CHECK (opening_cash>=0 AND expected_cash>=0)
);

ALTER TABLE payment_order
    ADD COLUMN request_hash CHAR(64) NULL AFTER idempotency_key,
    ADD COLUMN request_json JSON NULL AFTER request_hash,
    ADD COLUMN payment_channel VARCHAR(30) NULL AFTER payment_method,
    ADD COLUMN cashier_shift_id VARCHAR(36) NULL AFTER payment_channel,
    ADD COLUMN confirmed_at DATETIME(3) NULL AFTER confirmed_amount;
UPDATE payment_order
SET request_hash=SHA2(CONCAT(community_id, '|', idempotency_key), 256),
    request_json=JSON_OBJECT('legacy', TRUE), payment_channel=payment_method;
ALTER TABLE payment_order
    MODIFY request_hash CHAR(64) NOT NULL,
    MODIFY request_json JSON NOT NULL,
    MODIFY payment_channel VARCHAR(30) NOT NULL,
    ADD CONSTRAINT fk_payment_order_shift FOREIGN KEY (cashier_shift_id) REFERENCES cashier_shift(id),
    ADD CONSTRAINT ck_payment_order_status CHECK (status IN ('PENDING', 'CONFIRMED', 'REVERSED', 'CANCELLED')),
    ADD CONSTRAINT ck_payment_order_amounts CHECK (
        requested_amount>0 AND (confirmed_amount IS NULL OR confirmed_amount>=0));

ALTER TABLE daily_settlement
    ADD COLUMN gross_amount DECIMAL(18,2) NOT NULL DEFAULT 0 AFTER status,
    ADD COLUMN reversal_amount DECIMAL(18,2) NOT NULL DEFAULT 0 AFTER gross_amount,
    ADD COLUMN net_amount DECIMAL(18,2) NOT NULL DEFAULT 0 AFTER reversal_amount,
    ADD COLUMN request_key VARCHAR(120) NULL AFTER transaction_count,
    ADD COLUMN request_hash CHAR(64) NULL AFTER request_key,
    ADD COLUMN snapshot_checksum CHAR(64) NULL AFTER calculation_snapshot,
    ADD COLUMN created_by VARCHAR(36) NULL AFTER snapshot_checksum,
    ADD COLUMN closed_at DATETIME(3) NULL AFTER created_by,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER closed_at,
    ADD CONSTRAINT uk_daily_settlement_request UNIQUE (community_id, request_key),
    ADD CONSTRAINT fk_daily_settlement_user FOREIGN KEY (created_by) REFERENCES sys_user(id),
    ADD CONSTRAINT ck_daily_settlement_status CHECK (status IN ('CLOSED', 'LOCKED')),
    ADD CONSTRAINT ck_daily_settlement_counts CHECK (transaction_count>=0 AND gross_amount>=0 AND reversal_amount<=0);
UPDATE daily_settlement
SET gross_amount=total_amount, net_amount=total_amount,
    snapshot_checksum=SHA2(calculation_snapshot, 256), closed_at=created_at;

ALTER TABLE payment_transaction
    ADD COLUMN community_id VARCHAR(36) NULL AFTER id,
    ADD COLUMN payment_channel VARCHAR(30) NULL AFTER adapter_code,
    ADD COLUMN cashier_shift_id VARCHAR(36) NULL AFTER payment_channel,
    ADD COLUMN settlement_id VARCHAR(36) NULL AFTER cashier_shift_id,
    ADD COLUMN original_transaction_id VARCHAR(36) NULL AFTER transaction_type,
    ADD COLUMN request_key VARCHAR(120) NULL AFTER external_reference,
    ADD COLUMN snapshot_json JSON NULL AFTER request_key;
UPDATE payment_transaction pt JOIN payment_order po ON po.id=pt.payment_order_id
SET pt.community_id=po.community_id,
    pt.payment_channel=po.payment_channel,
    pt.cashier_shift_id=po.cashier_shift_id,
    pt.snapshot_json=JSON_OBJECT('legacy', TRUE);
ALTER TABLE payment_transaction
    MODIFY community_id VARCHAR(36) NOT NULL,
    MODIFY payment_channel VARCHAR(30) NOT NULL,
    MODIFY snapshot_json JSON NOT NULL,
    ADD COLUMN successful_payment_order_id VARCHAR(36) GENERATED ALWAYS AS (
        CASE WHEN transaction_type='PAYMENT' AND status='SUCCESS' THEN payment_order_id ELSE NULL END
    ) STORED,
    ADD CONSTRAINT uk_successful_payment_order UNIQUE (successful_payment_order_id),
    ADD CONSTRAINT uk_payment_transaction_request UNIQUE (community_id, request_key),
    ADD CONSTRAINT fk_payment_transaction_community FOREIGN KEY (community_id) REFERENCES community(id),
    ADD CONSTRAINT fk_payment_transaction_shift FOREIGN KEY (cashier_shift_id) REFERENCES cashier_shift(id),
    ADD CONSTRAINT fk_payment_transaction_settlement FOREIGN KEY (settlement_id) REFERENCES daily_settlement(id),
    ADD CONSTRAINT fk_payment_transaction_original FOREIGN KEY (original_transaction_id) REFERENCES payment_transaction(id),
    ADD CONSTRAINT ck_payment_transaction_type CHECK (transaction_type IN ('PAYMENT', 'REVERSAL', 'REFUND')),
    ADD CONSTRAINT ck_payment_transaction_amount CHECK (
        (transaction_type='PAYMENT' AND amount>0)
        OR (transaction_type IN ('REVERSAL', 'REFUND') AND amount<0));
CREATE INDEX idx_payment_transaction_timeline ON payment_transaction
    (community_id, occurred_at, payment_channel, transaction_type, status);

ALTER TABLE payment_allocation
    ADD CONSTRAINT ck_payment_allocation_amount CHECK (allocated_amount<>0);

ALTER TABLE prepayment_transaction
    ADD COLUMN request_hash CHAR(64) NULL AFTER idempotency_key,
    ADD COLUMN request_json JSON NULL AFTER request_hash,
    ADD COLUMN original_transaction_id VARCHAR(36) NULL AFTER reference_id,
    ADD COLUMN reason VARCHAR(500) NULL AFTER original_transaction_id,
    ADD COLUMN created_by VARCHAR(36) NULL AFTER reason;
UPDATE prepayment_transaction
SET request_hash=CASE WHEN idempotency_key IS NULL THEN NULL ELSE SHA2(CONCAT(account_id, '|', idempotency_key), 256) END,
    request_json=JSON_OBJECT('legacy', TRUE);
ALTER TABLE prepayment_transaction
    ADD CONSTRAINT fk_prepayment_txn_original FOREIGN KEY (original_transaction_id) REFERENCES prepayment_transaction(id),
    ADD CONSTRAINT fk_prepayment_txn_user FOREIGN KEY (created_by) REFERENCES sys_user(id),
    ADD CONSTRAINT ck_prepayment_txn_type CHECK (transaction_type IN ('TOP_UP', 'DEDUCT', 'REVERSAL')),
    ADD CONSTRAINT ck_prepayment_txn_amount CHECK (
        (transaction_type='TOP_UP' AND amount>0)
        OR (transaction_type='DEDUCT' AND amount<0)
        OR (transaction_type='REVERSAL' AND amount<>0));

ALTER TABLE deposit_account
    ADD COLUMN deposit_identity VARCHAR(160) GENERATED ALWAYS AS (
        CONCAT(community_id, '|', customer_id, '|', COALESCE(asset_id, ''), '|', deposit_type)
    ) STORED,
    ADD CONSTRAINT uk_deposit_account_identity UNIQUE (deposit_identity),
    ADD CONSTRAINT ck_deposit_account_balance CHECK (balance>=0),
    ADD CONSTRAINT ck_deposit_account_status CHECK (status IN ('ACTIVE', 'REFUNDED', 'LOCKED'));

ALTER TABLE deposit_transaction
    DROP INDEX uk_deposit_txn_idempotency,
    ADD COLUMN request_hash CHAR(64) NULL AFTER idempotency_key,
    ADD COLUMN request_json JSON NULL AFTER request_hash,
    ADD COLUMN original_transaction_id VARCHAR(36) NULL AFTER request_json,
    ADD COLUMN reason VARCHAR(500) NULL AFTER original_transaction_id,
    ADD COLUMN created_by VARCHAR(36) NULL AFTER reason,
    ADD CONSTRAINT uk_deposit_txn_idempotency UNIQUE (account_id, idempotency_key),
    ADD CONSTRAINT fk_deposit_txn_original FOREIGN KEY (original_transaction_id) REFERENCES deposit_transaction(id),
    ADD CONSTRAINT fk_deposit_txn_user FOREIGN KEY (created_by) REFERENCES sys_user(id),
    ADD CONSTRAINT ck_deposit_txn_type CHECK (transaction_type IN ('COLLECT', 'REFUND')),
    ADD CONSTRAINT ck_deposit_txn_amount CHECK (
        (transaction_type='COLLECT' AND amount>0) OR (transaction_type='REFUND' AND amount<0));
UPDATE deposit_transaction
SET request_hash=CASE WHEN idempotency_key IS NULL THEN NULL ELSE SHA2(CONCAT(account_id, '|', idempotency_key), 256) END,
    request_json=JSON_OBJECT('legacy', TRUE);

CREATE TABLE receipt_number_segment (
    id VARCHAR(36) PRIMARY KEY,
    community_id VARCHAR(36) NOT NULL,
    segment_code VARCHAR(80) NOT NULL,
    number_prefix VARCHAR(40) NOT NULL,
    start_no BIGINT NOT NULL,
    end_no BIGINT NOT NULL,
    next_no BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_receipt_segment_code UNIQUE (community_id, segment_code),
    CONSTRAINT uk_receipt_segment_prefix UNIQUE (community_id, number_prefix),
    CONSTRAINT fk_receipt_segment_community FOREIGN KEY (community_id) REFERENCES community(id),
    CONSTRAINT ck_receipt_segment_range CHECK (start_no>0 AND end_no>=start_no AND next_no>=start_no AND next_no<=end_no+1),
    CONSTRAINT ck_receipt_segment_status CHECK (status IN ('ACTIVE', 'EXHAUSTED', 'INACTIVE'))
);
INSERT INTO receipt_number_segment
    (id, community_id, segment_code, number_prefix, start_no, end_no, next_no, status, version, created_at, updated_at)
VALUES
    ('d1000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001', 'SYN-MAIN-2026', 'SYN-RCT-M-', 1, 999999, 1, 'ACTIVE', 0, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)),
    ('d1000000-0000-0000-0000-000000000002', '30000000-0000-0000-0000-000000000002', 'SYN-ISOLATED-2026', 'SYN-RCT-I-', 1, 999999, 1, 'ACTIVE', 0, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3));

ALTER TABLE receipt
    ADD COLUMN segment_id VARCHAR(36) NULL AFTER payment_order_id,
    ADD COLUMN sequence_no BIGINT NULL AFTER segment_id,
    ADD COLUMN snapshot_checksum CHAR(64) NULL AFTER data_snapshot,
    ADD COLUMN original_receipt_id VARCHAR(36) NULL AFTER snapshot_checksum,
    ADD COLUMN event_reason VARCHAR(500) NULL AFTER original_receipt_id,
    ADD COLUMN voided_at DATETIME(3) NULL AFTER issued_at,
    ADD COLUMN voided_by VARCHAR(36) NULL AFTER voided_at;
UPDATE receipt SET snapshot_checksum=SHA2(data_snapshot, 256);
ALTER TABLE receipt
    MODIFY snapshot_checksum CHAR(64) NOT NULL,
    ADD CONSTRAINT uk_receipt_segment_sequence UNIQUE (segment_id, sequence_no),
    ADD CONSTRAINT fk_receipt_segment FOREIGN KEY (segment_id) REFERENCES receipt_number_segment(id),
    ADD CONSTRAINT fk_receipt_original FOREIGN KEY (original_receipt_id) REFERENCES receipt(id),
    ADD CONSTRAINT fk_receipt_void_user FOREIGN KEY (voided_by) REFERENCES sys_user(id),
    ADD CONSTRAINT ck_receipt_status CHECK (status IN ('ISSUED', 'VOIDED', 'REPLACED', 'RED_CORRECTED'));

CREATE TABLE discount_policy (
    id VARCHAR(36) PRIMARY KEY,
    community_id VARCHAR(36) NOT NULL,
    policy_code VARCHAR(80) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    discount_type VARCHAR(20) NOT NULL,
    discount_value DECIMAL(18,6) NOT NULL,
    maximum_amount DECIMAL(18,2),
    effective_from DATE NOT NULL,
    effective_to DATE,
    approval_required BOOLEAN NOT NULL DEFAULT TRUE,
    status VARCHAR(20) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_discount_policy_code UNIQUE (community_id, policy_code),
    CONSTRAINT fk_discount_policy_community FOREIGN KEY (community_id) REFERENCES community(id),
    CONSTRAINT ck_discount_policy_type CHECK (discount_type IN ('PERCENT', 'FIXED')),
    CONSTRAINT ck_discount_policy_value CHECK (discount_value>0 AND (discount_type<>'PERCENT' OR discount_value<=1)),
    CONSTRAINT ck_discount_policy_dates CHECK (effective_to IS NULL OR effective_to>=effective_from),
    CONSTRAINT ck_discount_policy_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);
INSERT INTO discount_policy
    (id, community_id, policy_code, display_name, discount_type, discount_value, maximum_amount,
     effective_from, effective_to, approval_required, status, version, created_at, updated_at)
VALUES
    ('d2000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001',
     'SYN-DISCOUNT-5', '合成示范减免 5%', 'PERCENT', 0.050000, 50.00,
     '2026-01-01', NULL, TRUE, 'ACTIVE', 0, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3));

CREATE TABLE bill_adjustment (
    id VARCHAR(36) PRIMARY KEY,
    community_id VARCHAR(36) NOT NULL,
    bill_id VARCHAR(36) NOT NULL,
    discount_policy_id VARCHAR(36),
    adjustment_no VARCHAR(80) NOT NULL,
    adjustment_type VARCHAR(20) NOT NULL,
    amount DECIMAL(18,2) NOT NULL,
    status VARCHAR(20) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    request_key VARCHAR(120) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    request_json JSON NOT NULL,
    before_snapshot JSON NOT NULL,
    after_snapshot JSON,
    requested_by VARCHAR(36) NOT NULL,
    approved_by VARCHAR(36),
    approved_at DATETIME(3),
    applied_at DATETIME(3),
    rejected_at DATETIME(3),
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_bill_adjustment_no UNIQUE (adjustment_no),
    CONSTRAINT uk_bill_adjustment_request UNIQUE (community_id, request_key),
    CONSTRAINT fk_bill_adjustment_community FOREIGN KEY (community_id) REFERENCES community(id),
    CONSTRAINT fk_bill_adjustment_bill FOREIGN KEY (bill_id) REFERENCES bill(id),
    CONSTRAINT fk_bill_adjustment_policy FOREIGN KEY (discount_policy_id) REFERENCES discount_policy(id),
    CONSTRAINT fk_bill_adjustment_requester FOREIGN KEY (requested_by) REFERENCES sys_user(id),
    CONSTRAINT fk_bill_adjustment_approver FOREIGN KEY (approved_by) REFERENCES sys_user(id),
    CONSTRAINT ck_bill_adjustment_type CHECK (adjustment_type IN ('DISCOUNT', 'WAIVER', 'CREDIT', 'DEBIT', 'VOID')),
    CONSTRAINT ck_bill_adjustment_amount CHECK (amount<>0),
    CONSTRAINT ck_bill_adjustment_status CHECK (status IN ('PENDING', 'APPLIED', 'REJECTED'))
);
CREATE INDEX idx_bill_adjustment_query ON bill_adjustment (community_id, status, created_at, bill_id);

ALTER TABLE invoice_request
    ADD COLUMN operation_type VARCHAR(20) NOT NULL DEFAULT 'ISSUE' AFTER request_no,
    ADD COLUMN original_invoice_request_id VARCHAR(36) NULL AFTER operation_type,
    ADD COLUMN reason VARCHAR(500) NULL AFTER original_invoice_request_id,
    ADD COLUMN request_hash CHAR(64) NULL AFTER reason,
    ADD COLUMN snapshot_json JSON NULL AFTER title_snapshot,
    ADD COLUMN snapshot_checksum CHAR(64) NULL AFTER snapshot_json,
    ADD CONSTRAINT fk_invoice_original FOREIGN KEY (original_invoice_request_id) REFERENCES invoice_request(id),
    ADD CONSTRAINT ck_invoice_operation CHECK (operation_type IN ('ISSUE', 'REPLACE', 'RED'));
UPDATE invoice_request
SET request_hash=SHA2(CONCAT(community_id, '|', request_no), 256),
    snapshot_json=JSON_OBJECT('legacy', TRUE),
    snapshot_checksum=SHA2(JSON_OBJECT('legacy', TRUE), 256);
ALTER TABLE invoice_request
    MODIFY request_hash CHAR(64) NOT NULL,
    MODIFY snapshot_json JSON NOT NULL,
    MODIFY snapshot_checksum CHAR(64) NOT NULL;

CREATE TABLE financial_event (
    id VARCHAR(36) PRIMARY KEY,
    community_id VARCHAR(36) NOT NULL,
    aggregate_type VARCHAR(30) NOT NULL,
    aggregate_id VARCHAR(36) NOT NULL,
    event_type VARCHAR(60) NOT NULL,
    request_key VARCHAR(120),
    detail_json JSON NOT NULL,
    actor_user_id VARCHAR(36) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_financial_event_request UNIQUE (community_id, request_key),
    CONSTRAINT fk_financial_event_community FOREIGN KEY (community_id) REFERENCES community(id),
    CONSTRAINT fk_financial_event_actor FOREIGN KEY (actor_user_id) REFERENCES sys_user(id),
    CONSTRAINT ck_financial_event_aggregate CHECK (
        aggregate_type IN ('BILL', 'PAYMENT_ORDER', 'PAYMENT_TRANSACTION', 'PREPAYMENT', 'DEPOSIT', 'RECEIPT', 'INVOICE', 'SHIFT', 'SETTLEMENT'))
);
CREATE INDEX idx_financial_event_timeline ON financial_event
    (community_id, aggregate_type, aggregate_id, created_at);
