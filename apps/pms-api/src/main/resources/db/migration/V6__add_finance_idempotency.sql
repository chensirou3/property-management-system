ALTER TABLE prepayment_transaction
    ADD COLUMN idempotency_key VARCHAR(160),
    ADD CONSTRAINT uk_prepayment_txn_idempotency UNIQUE (account_id, idempotency_key);

ALTER TABLE deposit_transaction
    ADD COLUMN idempotency_key VARCHAR(160),
    ADD CONSTRAINT uk_deposit_txn_idempotency UNIQUE (idempotency_key);
