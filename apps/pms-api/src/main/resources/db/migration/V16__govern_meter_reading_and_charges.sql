-- G7 governed meter readings, share rules, replacements, IoT evidence and charge reconciliation.

ALTER TABLE meter
    ADD CONSTRAINT ck_meter_multiplier CHECK (multiplier > 0),
    ADD CONSTRAINT ck_meter_loss_rate CHECK (loss_rate >= 0 AND loss_rate <= 1),
    ADD CONSTRAINT ck_meter_range CHECK (range_value IS NULL OR range_value > 0),
    ADD CONSTRAINT ck_meter_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'REPLACED'));

ALTER TABLE meter_reading_batch
    ADD COLUMN request_key VARCHAR(160) NULL AFTER source_type,
    ADD COLUMN request_hash CHAR(64) NULL AFTER request_key,
    ADD COLUMN total_count INT NOT NULL DEFAULT 0 AFTER request_hash,
    ADD COLUMN normal_count INT NOT NULL DEFAULT 0 AFTER total_count,
    ADD COLUMN anomaly_count INT NOT NULL DEFAULT 0 AFTER normal_count,
    ADD COLUMN reviewed_count INT NOT NULL DEFAULT 0 AFTER anomaly_count,
    ADD COLUMN approved_by VARCHAR(36) NULL AFTER reviewed_count,
    ADD COLUMN approved_at DATETIME(3) NULL AFTER approved_by,
    ADD COLUMN data_checksum CHAR(64) NULL AFTER approved_at;

UPDATE meter_reading_batch
SET request_key=CONCAT('legacy-', id),
    request_hash=SHA2(CONCAT(community_id, '|', batch_no, '|', reading_period, '|', source_type), 256),
    approved_at=CASE WHEN status='APPROVED' THEN updated_at ELSE NULL END,
    data_checksum=SHA2(CONCAT('legacy-meter-batch|', id), 256);

ALTER TABLE meter_reading_batch
    MODIFY request_key VARCHAR(160) NOT NULL,
    MODIFY request_hash CHAR(64) NOT NULL,
    MODIFY data_checksum CHAR(64) NOT NULL,
    ADD CONSTRAINT uk_meter_batch_request UNIQUE (community_id, request_key),
    ADD CONSTRAINT fk_meter_batch_approver FOREIGN KEY (approved_by) REFERENCES sys_user(id),
    ADD CONSTRAINT ck_meter_batch_status CHECK (status IN ('DRAFT', 'APPROVED')),
    ADD CONSTRAINT ck_meter_batch_source CHECK (source_type IN ('MANUAL', 'IOT_SIMULATOR', 'MIXED')),
    ADD CONSTRAINT ck_meter_batch_counts CHECK (
        total_count >= 0 AND normal_count >= 0 AND anomaly_count >= 0 AND reviewed_count >= 0
        AND total_count = normal_count + anomaly_count
        AND reviewed_count <= anomaly_count);

ALTER TABLE meter_reading
    ADD COLUMN reading_period CHAR(7) NULL AFTER meter_id,
    ADD COLUMN input_source VARCHAR(30) NOT NULL DEFAULT 'MANUAL' AFTER reading_period,
    ADD COLUMN source_reference VARCHAR(160) NULL AFTER input_source,
    ADD COLUMN loss_rate DECIMAL(10,6) NOT NULL DEFAULT 0 AFTER multiplier,
    ADD COLUMN adjusted_usage DECIMAL(18,4) NULL AFTER loss_rate,
    ADD COLUMN validation_status VARCHAR(30) NOT NULL DEFAULT 'NORMAL' AFTER status,
    ADD COLUMN anomaly_code VARCHAR(80) NULL AFTER validation_status,
    ADD COLUMN review_reason VARCHAR(500) NULL AFTER anomaly_code,
    ADD COLUMN reviewed_by VARCHAR(36) NULL AFTER review_reason,
    ADD COLUMN reviewed_at DATETIME(3) NULL AFTER reviewed_by,
    ADD COLUMN calculation_checksum CHAR(64) NULL AFTER calculation_snapshot;

UPDATE meter_reading mr
JOIN meter_reading_batch mb ON mb.id=mr.batch_id
JOIN meter m ON m.id=mr.meter_id
SET mr.reading_period=mb.reading_period,
    mr.input_source=CASE WHEN mb.source_type='IOT_SIMULATOR' THEN 'IOT_SIMULATOR' ELSE 'MANUAL' END,
    mr.loss_rate=m.loss_rate,
    mr.adjusted_usage=ROUND(mr.raw_usage * mr.multiplier * (1 + m.loss_rate), 4),
    mr.validation_status='NORMAL',
    mr.calculation_checksum=SHA2(mr.calculation_snapshot, 256);

ALTER TABLE meter_reading
    MODIFY reading_period CHAR(7) NOT NULL,
    MODIFY adjusted_usage DECIMAL(18,4) NOT NULL,
    MODIFY calculation_checksum CHAR(64) NOT NULL,
    ADD CONSTRAINT uk_meter_reading_source UNIQUE (meter_id, source_reference),
    ADD CONSTRAINT fk_meter_reading_reviewer FOREIGN KEY (reviewed_by) REFERENCES sys_user(id),
    ADD CONSTRAINT ck_meter_reading_period CHECK (reading_period REGEXP '^[0-9]{4}-(0[1-9]|1[0-2])$'),
    ADD CONSTRAINT ck_meter_reading_source CHECK (input_source IN ('MANUAL', 'IOT_SIMULATOR', 'MIGRATION')),
    ADD CONSTRAINT ck_meter_reading_values CHECK (
        previous_reading >= 0 AND current_reading >= previous_reading AND raw_usage >= 0
        AND multiplier > 0 AND loss_rate >= 0 AND loss_rate <= 1
        AND adjusted_usage >= 0 AND allocated_share >= 0 AND billable_usage >= 0),
    ADD CONSTRAINT ck_meter_reading_validation CHECK (validation_status IN ('NORMAL', 'REVIEW_REQUIRED', 'REVIEWED')),
    ADD CONSTRAINT ck_meter_reading_review CHECK (
        (validation_status='NORMAL' AND anomaly_code IS NULL)
        OR (validation_status='REVIEW_REQUIRED' AND anomaly_code IS NOT NULL)
        OR (validation_status='REVIEWED' AND anomaly_code IS NOT NULL AND review_reason IS NOT NULL
            AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL));
CREATE INDEX idx_meter_reading_validation ON meter_reading (batch_id, validation_status, reading_at);

UPDATE meter_reading_batch mb
SET total_count=(SELECT COUNT(*) FROM meter_reading mr WHERE mr.batch_id=mb.id),
    normal_count=(SELECT COUNT(*) FROM meter_reading mr WHERE mr.batch_id=mb.id AND mr.validation_status='NORMAL'),
    anomaly_count=(SELECT COUNT(*) FROM meter_reading mr WHERE mr.batch_id=mb.id AND mr.validation_status<>'NORMAL'),
    reviewed_count=(SELECT COUNT(*) FROM meter_reading mr WHERE mr.batch_id=mb.id AND mr.validation_status='REVIEWED');

CREATE TABLE meter_share_rule_version (
    id VARCHAR(36) PRIMARY KEY,
    rule_id VARCHAR(36) NOT NULL,
    version_no INT NOT NULL,
    strategy_code VARCHAR(80) NOT NULL,
    config_json JSON NOT NULL,
    effective_from DATE NOT NULL,
    effective_to DATE,
    status VARCHAR(20) NOT NULL,
    assumption_rule BOOLEAN NOT NULL DEFAULT TRUE,
    created_by VARCHAR(36),
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_meter_share_rule_version UNIQUE (rule_id, version_no),
    CONSTRAINT fk_meter_share_version_rule FOREIGN KEY (rule_id) REFERENCES meter_share_rule(id),
    CONSTRAINT fk_meter_share_version_creator FOREIGN KEY (created_by) REFERENCES sys_user(id),
    CONSTRAINT ck_meter_share_version_strategy CHECK (strategy_code IN ('AREA_RATIO')),
    CONSTRAINT ck_meter_share_version_dates CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CONSTRAINT ck_meter_share_version_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);
CREATE INDEX idx_meter_share_version_effective ON meter_share_rule_version
    (rule_id, status, effective_from, effective_to);

INSERT INTO meter_share_rule_version
    (id, rule_id, version_no, strategy_code, config_json, effective_from, effective_to,
     status, assumption_rule, created_by, created_at)
SELECT UUID(), id, 1, strategy_code, CAST(config_json AS JSON), '2026-01-01', NULL,
       status, TRUE, NULL, created_at
FROM meter_share_rule;

ALTER TABLE meter_share_result
    ADD COLUMN rule_version_id VARCHAR(36) NULL AFTER rule_id,
    ADD COLUMN allocation_basis DECIMAL(18,4) NOT NULL DEFAULT 0 AFTER asset_id,
    ADD COLUMN basis_total DECIMAL(20,4) NOT NULL DEFAULT 0 AFTER allocation_basis,
    ADD COLUMN calculation_checksum CHAR(64) NULL AFTER calculation_snapshot;
UPDATE meter_share_result msr
JOIN meter_share_rule_version msv ON msv.rule_id=msr.rule_id AND msv.version_no=1
SET msr.rule_version_id=msv.id,
    msr.calculation_checksum=SHA2(msr.calculation_snapshot, 256);
ALTER TABLE meter_share_result
    MODIFY rule_version_id VARCHAR(36) NOT NULL,
    MODIFY calculation_checksum CHAR(64) NOT NULL,
    ADD CONSTRAINT fk_meter_share_result_version FOREIGN KEY (rule_version_id) REFERENCES meter_share_rule_version(id),
    ADD CONSTRAINT ck_meter_share_result_values CHECK (
        allocation_basis >= 0 AND basis_total >= 0 AND allocated_usage >= 0);

ALTER TABLE meter_replacement
    ADD COLUMN community_id VARCHAR(36) NULL AFTER id,
    ADD COLUMN request_key VARCHAR(160) NULL AFTER community_id,
    ADD COLUMN request_hash CHAR(64) NULL AFTER request_key,
    ADD COLUMN evidence_no VARCHAR(100) NULL AFTER request_hash,
    ADD COLUMN old_meter_version BIGINT NOT NULL DEFAULT 0 AFTER new_initial_reading,
    ADD COLUMN new_meter_version BIGINT NOT NULL DEFAULT 0 AFTER old_meter_version,
    ADD COLUMN snapshot_json JSON NULL AFTER reason,
    ADD COLUMN snapshot_checksum CHAR(64) NULL AFTER snapshot_json;
UPDATE meter_replacement mr
JOIN meter m ON m.id=mr.old_meter_id
SET mr.community_id=m.community_id,
    mr.request_key=CONCAT('legacy-', mr.id),
    mr.request_hash=SHA2(CONCAT(m.community_id, '|', mr.old_meter_id, '|', mr.new_meter_id, '|', mr.replaced_at), 256),
    mr.evidence_no=CONCAT('LEGACY-MR-', LEFT(mr.id, 12)),
    mr.snapshot_json=JSON_OBJECT('legacy', TRUE, 'oldMeterId', mr.old_meter_id, 'newMeterId', mr.new_meter_id,
                                 'oldFinalReading', mr.old_final_reading, 'newInitialReading', mr.new_initial_reading),
    mr.snapshot_checksum=SHA2(CAST(JSON_OBJECT('legacy', TRUE, 'oldMeterId', mr.old_meter_id,
        'newMeterId', mr.new_meter_id, 'oldFinalReading', mr.old_final_reading,
        'newInitialReading', mr.new_initial_reading) AS CHAR), 256);
ALTER TABLE meter_replacement
    MODIFY community_id VARCHAR(36) NOT NULL,
    MODIFY request_key VARCHAR(160) NOT NULL,
    MODIFY request_hash CHAR(64) NOT NULL,
    MODIFY evidence_no VARCHAR(100) NOT NULL,
    MODIFY snapshot_json JSON NOT NULL,
    MODIFY snapshot_checksum CHAR(64) NOT NULL,
    ADD CONSTRAINT uk_meter_replacement_request UNIQUE (community_id, request_key),
    ADD CONSTRAINT uk_meter_replacement_evidence UNIQUE (community_id, evidence_no),
    ADD CONSTRAINT fk_meter_replacement_community FOREIGN KEY (community_id) REFERENCES community(id),
    ADD CONSTRAINT ck_meter_replacement_values CHECK (old_final_reading >= 0 AND new_initial_reading >= 0);

CREATE TABLE iot_reading_inbox (
    id VARCHAR(36) PRIMARY KEY,
    community_id VARCHAR(36) NOT NULL,
    batch_id VARCHAR(36) NOT NULL,
    meter_id VARCHAR(36) NOT NULL,
    adapter_code VARCHAR(80) NOT NULL,
    source_reference VARCHAR(160) NOT NULL,
    reading_value DECIMAL(18,4) NOT NULL,
    reading_at DATETIME(3) NOT NULL,
    simulated BOOLEAN NOT NULL,
    payload_json JSON NOT NULL,
    payload_checksum CHAR(64) NOT NULL,
    status VARCHAR(30) NOT NULL,
    meter_reading_id VARCHAR(36),
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_iot_reading_source UNIQUE (adapter_code, source_reference),
    CONSTRAINT fk_iot_inbox_community FOREIGN KEY (community_id) REFERENCES community(id),
    CONSTRAINT fk_iot_inbox_batch FOREIGN KEY (batch_id) REFERENCES meter_reading_batch(id),
    CONSTRAINT fk_iot_inbox_meter FOREIGN KEY (meter_id) REFERENCES meter(id),
    CONSTRAINT fk_iot_inbox_reading FOREIGN KEY (meter_reading_id) REFERENCES meter_reading(id),
    CONSTRAINT ck_iot_inbox_value CHECK (reading_value >= 0),
    CONSTRAINT ck_iot_inbox_status CHECK (status IN ('RECEIVED', 'APPLIED', 'REPLAYED', 'REJECTED'))
);
CREATE INDEX idx_iot_inbox_batch ON iot_reading_inbox (batch_id, status, created_at);

CREATE TABLE meter_charge_reconciliation (
    id VARCHAR(36) PRIMARY KEY,
    community_id VARCHAR(36) NOT NULL,
    batch_id VARCHAR(36) NOT NULL,
    fee_standard_id VARCHAR(36) NOT NULL,
    metric_name VARCHAR(100) NOT NULL,
    source_value DECIMAL(20,4) NOT NULL,
    target_value DECIMAL(20,4) NOT NULL,
    difference_value DECIMAL(20,4) NOT NULL,
    status VARCHAR(20) NOT NULL,
    detail_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_meter_charge_reconciliation UNIQUE (batch_id, fee_standard_id, metric_name),
    CONSTRAINT fk_meter_charge_recon_community FOREIGN KEY (community_id) REFERENCES community(id),
    CONSTRAINT fk_meter_charge_recon_batch FOREIGN KEY (batch_id) REFERENCES meter_reading_batch(id),
    CONSTRAINT fk_meter_charge_recon_standard FOREIGN KEY (fee_standard_id) REFERENCES fee_standard(id),
    CONSTRAINT ck_meter_charge_recon_status CHECK (status IN ('MATCHED', 'MISMATCH'))
);

CREATE TABLE meter_event (
    id VARCHAR(36) PRIMARY KEY,
    community_id VARCHAR(36) NOT NULL,
    aggregate_type VARCHAR(30) NOT NULL,
    aggregate_id VARCHAR(36) NOT NULL,
    event_type VARCHAR(60) NOT NULL,
    reference_type VARCHAR(40),
    reference_id VARCHAR(36),
    detail_json JSON NOT NULL,
    detail_checksum CHAR(64) NOT NULL,
    actor_user_id VARCHAR(36) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_meter_event_community FOREIGN KEY (community_id) REFERENCES community(id),
    CONSTRAINT fk_meter_event_actor FOREIGN KEY (actor_user_id) REFERENCES sys_user(id),
    CONSTRAINT ck_meter_event_aggregate CHECK (aggregate_type IN ('METER', 'BATCH', 'READING', 'SHARE', 'REPLACEMENT', 'CHARGE'))
);
CREATE INDEX idx_meter_event_timeline ON meter_event (community_id, aggregate_type, aggregate_id, created_at);

-- Deterministic, explicitly synthetic meter-usage fee configuration for G7 acceptance.
UPDATE fee_definition
SET name='合成计量水费', fee_type='METER', fee_class='USAGE', unit_code='M3',
    decimal_scale=2, rounding_mode='HALF_UP', updated_at='2026-08-25 00:00:00.000'
WHERE id='70000000-0000-0000-0000-000000000019';

INSERT INTO fee_standard
    (id, community_id, fee_definition_id, code, name, asset_type, billing_cycle,
     calculation_basis, proration_rule, status, version, created_at, updated_at)
VALUES
    ('71000000-0000-0000-0000-000000000019', '30000000-0000-0000-0000-000000000001',
     '70000000-0000-0000-0000-000000000019', 'METER-WATER-USAGE', '合成计量水费标准',
     'METER', 'MONTHLY', 'METER_USAGE', 'FULL_PERIOD', 'ACTIVE', 0,
     '2026-08-25 00:00:00.000', '2026-08-25 00:00:00.000');

INSERT INTO fee_standard_version
    (id, fee_standard_id, version_no, unit_price, minimum_amount, maximum_amount,
     formula_code, formula_expression, effective_from, effective_to, status,
     created_by, published_at, created_at)
VALUES
    ('72000000-0000-0000-0000-000000000019', '71000000-0000-0000-0000-000000000019',
     1, 1.250000, 0.00, NULL, 'METER_USAGE',
     'billable_usage * unit_price * allocation_coefficient', '2026-01-01', NULL, 'ACTIVE',
     NULL, '2026-08-25 00:00:00.000', '2026-08-25 00:00:00.000');

INSERT INTO fee_allocation
    (id, community_id, fee_standard_id, target_type, asset_id, meter_id, coefficient,
     source_type, effective_from, effective_to, cancellation_reason, status, version, created_at, updated_at)
SELECT CONCAT('74000000-0000-0000-0000-', LPAD(seq.n, 12, '0')),
       '30000000-0000-0000-0000-000000000001',
       '71000000-0000-0000-0000-000000000019', 'METER', NULL,
       CONCAT('61000000-0000-0000-0000-', LPAD(seq.n, 12, '0')), 1.000000,
       'MIGRATION', '2026-01-01', NULL, NULL, 'ACTIVE', 0,
       '2026-08-25 00:00:00.000', '2026-08-25 00:00:00.000'
FROM (
    SELECT ones.n + tens.n * 10 + 1 n
    FROM (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
          UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) ones
    CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2) tens
) seq
WHERE seq.n <= 30;
