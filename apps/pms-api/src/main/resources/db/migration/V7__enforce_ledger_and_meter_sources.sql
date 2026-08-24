UPDATE bill
SET outstanding_amount = total_amount - paid_amount
WHERE total_amount <> paid_amount + outstanding_amount;

ALTER TABLE bill
    ADD CONSTRAINT chk_bill_balance CHECK (
        total_amount >= 0 AND paid_amount >= 0 AND outstanding_amount >= 0
        AND total_amount = paid_amount + outstanding_amount
    );

ALTER TABLE bill_item
    ADD COLUMN source_type VARCHAR(40),
    ADD COLUMN source_id VARCHAR(36),
    ADD CONSTRAINT uk_bill_item_source UNIQUE (source_type, source_id);
