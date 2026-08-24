ALTER TABLE bill
    ADD CONSTRAINT uk_bill_asset_period UNIQUE (community_id, asset_id, billing_period);

CREATE TABLE payment_order_intent (
    id VARCHAR(36) PRIMARY KEY,
    payment_order_id VARCHAR(36) NOT NULL,
    bill_id VARCHAR(36) NOT NULL,
    requested_amount DECIMAL(18,2) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_payment_order_intent UNIQUE (payment_order_id, bill_id),
    CONSTRAINT fk_payment_intent_order FOREIGN KEY (payment_order_id) REFERENCES payment_order(id),
    CONSTRAINT fk_payment_intent_bill FOREIGN KEY (bill_id) REFERENCES bill(id)
);

CREATE TABLE invoice_request (
    id VARCHAR(36) PRIMARY KEY,
    community_id VARCHAR(36) NOT NULL,
    receipt_id VARCHAR(36),
    request_no VARCHAR(80) NOT NULL,
    adapter_code VARCHAR(40) NOT NULL,
    status VARCHAR(30) NOT NULL,
    amount DECIMAL(18,2) NOT NULL,
    title_snapshot VARCHAR(500) NOT NULL,
    external_reference VARCHAR(160),
    requested_by VARCHAR(36) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_invoice_request_no UNIQUE (request_no),
    CONSTRAINT fk_invoice_request_community FOREIGN KEY (community_id) REFERENCES community(id),
    CONSTRAINT fk_invoice_request_receipt FOREIGN KEY (receipt_id) REFERENCES receipt(id),
    CONSTRAINT fk_invoice_request_user FOREIGN KEY (requested_by) REFERENCES sys_user(id)
);
