-- G5 governed fee configuration and receivable jobs.

ALTER TABLE fee_definition
    ADD COLUMN tax_category_code VARCHAR(80) NULL AFTER prepayment_subject_code,
    ADD COLUMN tax_rate DECIMAL(9,6) NOT NULL DEFAULT 0 AFTER tax_category_code,
    ADD COLUMN rounding_mode VARCHAR(20) NOT NULL DEFAULT 'HALF_UP' AFTER decimal_scale,
    ADD COLUMN currency_code CHAR(3) NOT NULL DEFAULT 'CNY' AFTER rounding_mode,
    ADD COLUMN temporary_allowed BOOLEAN NOT NULL DEFAULT FALSE AFTER late_fee_enabled,
    ADD CONSTRAINT uk_fee_definition_id_community UNIQUE (id, community_id),
    ADD CONSTRAINT ck_fee_definition_scale CHECK (decimal_scale BETWEEN 0 AND 2),
    ADD CONSTRAINT ck_fee_definition_tax CHECK (tax_rate >= 0 AND tax_rate <= 1),
    ADD CONSTRAINT ck_fee_definition_rounding CHECK (rounding_mode IN ('HALF_UP', 'HALF_EVEN', 'DOWN', 'UP')),
    ADD CONSTRAINT ck_fee_definition_currency CHECK (currency_code = 'CNY'),
    ADD CONSTRAINT ck_fee_definition_type CHECK (fee_type IN ('PROPERTY', 'PARKING', 'METER', 'TEMPORARY')),
    ADD CONSTRAINT ck_fee_definition_class CHECK (fee_class IN ('PERIODIC', 'USAGE', 'TEMPORARY'));

ALTER TABLE fee_standard ADD COLUMN community_id VARCHAR(36) NULL AFTER id;
UPDATE fee_standard fs JOIN fee_definition fd ON fd.id=fs.fee_definition_id
SET fs.community_id=fd.community_id;
ALTER TABLE fee_standard
    MODIFY community_id VARCHAR(36) NOT NULL,
    ADD COLUMN calculation_basis VARCHAR(30) NOT NULL DEFAULT 'FIXED' AFTER billing_cycle,
    ADD COLUMN proration_rule VARCHAR(30) NOT NULL DEFAULT 'FULL_PERIOD' AFTER calculation_basis,
    ADD CONSTRAINT uk_fee_standard_id_community UNIQUE (id, community_id),
    ADD CONSTRAINT fk_fee_standard_community FOREIGN KEY (community_id) REFERENCES community(id),
    ADD CONSTRAINT fk_fee_standard_definition_scope FOREIGN KEY (fee_definition_id, community_id)
        REFERENCES fee_definition(id, community_id),
    ADD CONSTRAINT ck_fee_standard_asset_type CHECK (asset_type IN ('ROOM', 'PARKING', 'METER')),
    ADD CONSTRAINT ck_fee_standard_cycle CHECK (billing_cycle IN ('MONTHLY', 'ONCE')),
    ADD CONSTRAINT ck_fee_standard_basis CHECK (calculation_basis IN ('BUILDING_AREA', 'USABLE_AREA', 'FIXED', 'METER_USAGE')),
    ADD CONSTRAINT ck_fee_standard_proration CHECK (proration_rule IN ('FULL_PERIOD')),
    ADD CONSTRAINT ck_fee_standard_status CHECK (status IN ('DRAFT', 'ACTIVE', 'INACTIVE'));
UPDATE fee_standard fs
JOIN fee_standard_version fsv ON fsv.fee_standard_id=fs.id AND fsv.version_no=1
SET fs.calculation_basis=CASE fsv.formula_code
    WHEN 'AREA_PRICE' THEN 'BUILDING_AREA'
    WHEN 'USABLE_AREA_PRICE' THEN 'USABLE_AREA'
    WHEN 'METER_USAGE' THEN 'METER_USAGE'
    ELSE 'FIXED' END;

ALTER TABLE fee_standard_version
    ADD COLUMN minimum_amount DECIMAL(18,2) NULL AFTER unit_price,
    ADD COLUMN maximum_amount DECIMAL(18,2) NULL AFTER minimum_amount,
    ADD COLUMN created_by VARCHAR(36) NULL AFTER status,
    ADD COLUMN published_at DATETIME(3) NULL AFTER created_by,
    ADD CONSTRAINT fk_standard_version_creator FOREIGN KEY (created_by) REFERENCES sys_user(id),
    ADD CONSTRAINT ck_standard_version_no CHECK (version_no > 0),
    ADD CONSTRAINT ck_standard_version_price CHECK (unit_price >= 0),
    ADD CONSTRAINT ck_standard_version_bounds CHECK (
        (minimum_amount IS NULL OR minimum_amount >= 0)
        AND (maximum_amount IS NULL OR maximum_amount >= 0)
        AND (minimum_amount IS NULL OR maximum_amount IS NULL OR maximum_amount >= minimum_amount)),
    ADD CONSTRAINT ck_standard_version_dates CHECK (effective_to IS NULL OR effective_to >= effective_from),
    ADD CONSTRAINT ck_standard_version_status CHECK (status IN ('ACTIVE', 'INACTIVE'));

ALTER TABLE fee_allocation
    ADD COLUMN community_id VARCHAR(36) NULL AFTER id,
    ADD COLUMN target_type VARCHAR(20) NOT NULL DEFAULT 'ASSET' AFTER fee_standard_id,
    ADD COLUMN meter_id VARCHAR(36) NULL AFTER asset_id,
    ADD COLUMN source_type VARCHAR(30) NOT NULL DEFAULT 'MANUAL' AFTER coefficient,
    ADD COLUMN cancellation_reason VARCHAR(500) NULL AFTER effective_to;
UPDATE fee_allocation fa JOIN asset a ON a.id=fa.asset_id SET fa.community_id=a.community_id;
ALTER TABLE fee_allocation
    MODIFY community_id VARCHAR(36) NOT NULL,
    MODIFY asset_id VARCHAR(36) NULL,
    DROP INDEX uk_fee_allocation_period,
    ADD COLUMN target_identity VARCHAR(36)
        GENERATED ALWAYS AS (CASE WHEN target_type='ASSET' THEN asset_id ELSE meter_id END) STORED,
    ADD CONSTRAINT uk_fee_allocation_period UNIQUE
        (fee_standard_id, target_type, target_identity, effective_from),
    ADD CONSTRAINT fk_allocation_community FOREIGN KEY (community_id) REFERENCES community(id),
    ADD CONSTRAINT fk_allocation_standard_scope FOREIGN KEY (fee_standard_id, community_id)
        REFERENCES fee_standard(id, community_id),
    ADD CONSTRAINT fk_allocation_asset_scope FOREIGN KEY (asset_id, community_id)
        REFERENCES asset(id, community_id),
    ADD CONSTRAINT fk_allocation_meter_scope FOREIGN KEY (meter_id, community_id)
        REFERENCES meter(id, community_id),
    ADD CONSTRAINT ck_allocation_target_type CHECK (target_type IN ('ASSET', 'METER')),
    ADD CONSTRAINT ck_allocation_target CHECK (
        (target_type='ASSET' AND asset_id IS NOT NULL AND meter_id IS NULL)
        OR (target_type='METER' AND meter_id IS NOT NULL AND asset_id IS NULL)),
    ADD CONSTRAINT ck_allocation_coefficient CHECK (coefficient > 0),
    ADD CONSTRAINT ck_allocation_dates CHECK (effective_to IS NULL OR effective_to >= effective_from),
    ADD CONSTRAINT ck_allocation_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    ADD CONSTRAINT ck_allocation_source CHECK (source_type IN ('MANUAL', 'IMPORT', 'MIGRATION'));
CREATE INDEX idx_allocation_effective ON fee_allocation
    (community_id, target_type, target_identity, status, effective_from, effective_to);

ALTER TABLE receivable_generation_job
    ADD COLUMN job_type VARCHAR(20) NOT NULL DEFAULT 'PERIODIC' AFTER community_id,
    ADD COLUMN request_hash CHAR(64) NULL AFTER request_key,
    ADD COLUMN request_json JSON NULL AFTER request_hash,
    ADD COLUMN requested_count INT NOT NULL DEFAULT 0 AFTER preview,
    ADD COLUMN skipped_count INT NOT NULL DEFAULT 0 AFTER generated_count,
    ADD COLUMN total_amount DECIMAL(18,2) NOT NULL DEFAULT 0 AFTER error_count,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER completed_at,
    ADD COLUMN updated_at DATETIME(3) NULL AFTER created_at;
UPDATE receivable_generation_job
SET request_hash=SHA2(CONCAT(community_id, '|', request_key), 256),
    request_json=JSON_OBJECT('legacy', TRUE), updated_at=created_at;
ALTER TABLE receivable_generation_job
    MODIFY request_hash CHAR(64) NOT NULL,
    MODIFY request_json JSON NOT NULL,
    MODIFY updated_at DATETIME(3) NOT NULL,
    ADD CONSTRAINT ck_receivable_job_type CHECK (job_type IN ('PERIODIC', 'TEMPORARY')),
    ADD CONSTRAINT ck_receivable_job_status CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'PARTIAL', 'FAILED')),
    ADD CONSTRAINT ck_receivable_job_counts CHECK (
        requested_count >= 0 AND generated_count >= 0 AND skipped_count >= 0 AND error_count >= 0 AND total_amount >= 0);
CREATE INDEX idx_receivable_job_query ON receivable_generation_job (community_id, created_at, status, job_type);

ALTER TABLE receivable_generation_error
    ADD COLUMN row_no INT NULL AFTER job_id,
    ADD COLUMN detail_json JSON NULL AFTER error_message;

ALTER TABLE bill
    ADD COLUMN receivable_job_id VARCHAR(36) NULL AFTER community_id,
    ADD COLUMN bill_type VARCHAR(20) NOT NULL DEFAULT 'PERIODIC' AFTER bill_no,
    ADD COLUMN charge_date DATE NULL AFTER billing_period,
    ADD COLUMN configuration_checksum CHAR(64) NULL AFTER charge_date,
    DROP INDEX uk_bill_asset_period;
UPDATE bill
SET charge_date=STR_TO_DATE(CONCAT(billing_period, '-01'), '%Y-%m-%d'),
    configuration_checksum=SHA2(CONCAT('legacy|', id), 256);
ALTER TABLE bill
    MODIFY charge_date DATE NOT NULL,
    MODIFY configuration_checksum CHAR(64) NOT NULL,
    ADD COLUMN periodic_bill_key VARCHAR(100)
        GENERATED ALWAYS AS (
            CASE WHEN bill_type='PERIODIC'
                 THEN CONCAT(community_id, '|', asset_id, '|', billing_period)
                 ELSE NULL END
        ) STORED,
    ADD CONSTRAINT uk_bill_periodic UNIQUE (periodic_bill_key),
    ADD CONSTRAINT fk_bill_receivable_job FOREIGN KEY (receivable_job_id) REFERENCES receivable_generation_job(id),
    ADD CONSTRAINT ck_bill_type CHECK (bill_type IN ('PERIODIC', 'TEMPORARY'));

ALTER TABLE bill_item
    MODIFY source_id VARCHAR(120),
    ADD COLUMN fee_allocation_id VARCHAR(36) NULL AFTER fee_standard_version_id,
    ADD CONSTRAINT fk_bill_item_allocation FOREIGN KEY (fee_allocation_id) REFERENCES fee_allocation(id);

CREATE TABLE fee_configuration_event (
    id VARCHAR(36) PRIMARY KEY,
    community_id VARCHAR(36) NOT NULL,
    aggregate_type VARCHAR(30) NOT NULL,
    aggregate_id VARCHAR(36) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    effective_date DATE,
    previous_version BIGINT,
    new_version BIGINT,
    detail_json JSON NOT NULL,
    actor_user_id VARCHAR(36) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_fee_event_community FOREIGN KEY (community_id) REFERENCES community(id),
    CONSTRAINT fk_fee_event_actor FOREIGN KEY (actor_user_id) REFERENCES sys_user(id),
    CONSTRAINT ck_fee_event_aggregate CHECK (aggregate_type IN ('DEFINITION', 'STANDARD', 'VERSION', 'ALLOCATION'))
);
CREATE INDEX idx_fee_event_timeline ON fee_configuration_event
    (community_id, aggregate_type, aggregate_id, created_at);

CREATE TABLE receivable_generation_item (
    id VARCHAR(36) PRIMARY KEY,
    job_id VARCHAR(36) NOT NULL,
    row_no INT NOT NULL,
    asset_id VARCHAR(36) NOT NULL,
    customer_id VARCHAR(36),
    fee_definition_id VARCHAR(36),
    fee_standard_version_id VARCHAR(36),
    fee_allocation_id VARCHAR(36),
    item_name VARCHAR(160) NOT NULL,
    amount DECIMAL(18,2) NOT NULL,
    status VARCHAR(20) NOT NULL,
    bill_id VARCHAR(36),
    snapshot_json JSON NOT NULL,
    error_code VARCHAR(80),
    error_message VARCHAR(500),
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_receivable_item_row UNIQUE (job_id, row_no),
    CONSTRAINT fk_receivable_item_job FOREIGN KEY (job_id) REFERENCES receivable_generation_job(id),
    CONSTRAINT fk_receivable_item_asset FOREIGN KEY (asset_id) REFERENCES asset(id),
    CONSTRAINT fk_receivable_item_customer FOREIGN KEY (customer_id) REFERENCES customer(id),
    CONSTRAINT fk_receivable_item_definition FOREIGN KEY (fee_definition_id) REFERENCES fee_definition(id),
    CONSTRAINT fk_receivable_item_version FOREIGN KEY (fee_standard_version_id) REFERENCES fee_standard_version(id),
    CONSTRAINT fk_receivable_item_allocation FOREIGN KEY (fee_allocation_id) REFERENCES fee_allocation(id),
    CONSTRAINT fk_receivable_item_bill FOREIGN KEY (bill_id) REFERENCES bill(id),
    CONSTRAINT ck_receivable_item_amount CHECK (amount >= 0),
    CONSTRAINT ck_receivable_item_status CHECK (status IN ('PENDING', 'GENERATED', 'SKIPPED', 'FAILED'))
);
CREATE INDEX idx_receivable_item_job_asset ON receivable_generation_item (job_id, asset_id, status);

CREATE TABLE receivable_generation_reconciliation (
    id VARCHAR(36) PRIMARY KEY,
    job_id VARCHAR(36) NOT NULL,
    metric_name VARCHAR(100) NOT NULL,
    source_value DECIMAL(20,4) NOT NULL,
    target_value DECIMAL(20,4) NOT NULL,
    difference_value DECIMAL(20,4) NOT NULL,
    status VARCHAR(20) NOT NULL,
    detail_json JSON,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_receivable_reconciliation_metric UNIQUE (job_id, metric_name),
    CONSTRAINT fk_receivable_reconciliation_job FOREIGN KEY (job_id) REFERENCES receivable_generation_job(id),
    CONSTRAINT ck_receivable_reconciliation_status CHECK (status IN ('MATCHED', 'MISMATCH'))
);
