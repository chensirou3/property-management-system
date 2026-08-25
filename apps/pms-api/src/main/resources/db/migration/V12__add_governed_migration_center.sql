-- G4 governed migration center. Raw source evidence is append-only at the service
-- boundary; every later layer can be rebuilt without changing the uploaded rows.

CREATE TABLE migration_batch (
    id VARCHAR(36) PRIMARY KEY,
    community_id VARCHAR(36) NOT NULL,
    batch_no VARCHAR(60) NOT NULL,
    source_type VARCHAR(30) NOT NULL DEFAULT 'JSON',
    source_name VARCHAR(240) NOT NULL,
    source_sha256 CHAR(64) NOT NULL,
    mapping_version VARCHAR(60) NOT NULL,
    status VARCHAR(30) NOT NULL,
    review_status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    review_comment VARCHAR(500),
    requested_by VARCHAR(36) NOT NULL,
    reviewed_by VARCHAR(36),
    reviewed_at DATETIME(3),
    total_count INT NOT NULL DEFAULT 0,
    quarantine_count INT NOT NULL DEFAULT 0,
    canonical_count INT NOT NULL DEFAULT 0,
    staged_count INT NOT NULL DEFAULT 0,
    imported_count INT NOT NULL DEFAULT 0,
    skipped_count INT NOT NULL DEFAULT 0,
    error_count INT NOT NULL DEFAULT 0,
    rollback_token VARCHAR(80) NOT NULL,
    executed_at DATETIME(3),
    reconciled_at DATETIME(3),
    rolled_back_at DATETIME(3),
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_migration_batch_no UNIQUE (batch_no),
    CONSTRAINT uk_migration_batch_replay UNIQUE (community_id, source_sha256, mapping_version),
    CONSTRAINT fk_migration_batch_community FOREIGN KEY (community_id) REFERENCES community(id),
    CONSTRAINT fk_migration_batch_requester FOREIGN KEY (requested_by) REFERENCES sys_user(id),
    CONSTRAINT fk_migration_batch_reviewer FOREIGN KEY (reviewed_by) REFERENCES sys_user(id),
    CONSTRAINT ck_migration_batch_status CHECK (status IN (
        'UPLOADED', 'VALIDATING', 'READY', 'PARTIAL_FAILED', 'APPROVED',
        'EXECUTING', 'COMPLETED', 'RECONCILED', 'ROLLED_BACK', 'FAILED')),
    CONSTRAINT ck_migration_review_status CHECK (review_status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_migration_batch_counts CHECK (
        total_count >= 0 AND quarantine_count >= 0 AND canonical_count >= 0
        AND staged_count >= 0 AND imported_count >= 0 AND skipped_count >= 0 AND error_count >= 0)
);
CREATE INDEX idx_migration_batch_query ON migration_batch (community_id, created_at, status);

CREATE TABLE migration_raw_record (
    id VARCHAR(36) PRIMARY KEY,
    batch_id VARCHAR(36) NOT NULL,
    row_no INT NOT NULL,
    resource_type VARCHAR(30) NOT NULL,
    source_id VARCHAR(120) NOT NULL,
    raw_json JSON NOT NULL,
    record_sha256 CHAR(64) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_migration_raw_row UNIQUE (batch_id, row_no),
    CONSTRAINT fk_migration_raw_batch FOREIGN KEY (batch_id) REFERENCES migration_batch(id),
    CONSTRAINT ck_migration_raw_resource CHECK (resource_type IN ('PROJECT', 'BUILDING', 'ASSET', 'CUSTOMER', 'RELATION')),
    CONSTRAINT ck_migration_raw_row_no CHECK (row_no > 0)
);

CREATE TABLE migration_quarantine_record (
    id VARCHAR(36) PRIMARY KEY,
    batch_id VARCHAR(36) NOT NULL,
    raw_record_id VARCHAR(36) NOT NULL,
    error_code VARCHAR(80) NOT NULL,
    field_name VARCHAR(120),
    error_message VARCHAR(500) NOT NULL,
    detail_json JSON,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_migration_quarantine_batch FOREIGN KEY (batch_id) REFERENCES migration_batch(id),
    CONSTRAINT fk_migration_quarantine_raw FOREIGN KEY (raw_record_id) REFERENCES migration_raw_record(id)
);
CREATE INDEX idx_migration_quarantine_batch ON migration_quarantine_record (batch_id, raw_record_id);

CREATE TABLE migration_canonical_record (
    id VARCHAR(36) PRIMARY KEY,
    batch_id VARCHAR(36) NOT NULL,
    raw_record_id VARCHAR(36) NOT NULL,
    resource_type VARCHAR(30) NOT NULL,
    source_id VARCHAR(120) NOT NULL,
    canonical_json JSON NOT NULL,
    canonical_sha256 CHAR(64) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_migration_canonical_raw UNIQUE (raw_record_id),
    CONSTRAINT uk_migration_canonical_source UNIQUE (batch_id, resource_type, source_id),
    CONSTRAINT fk_migration_canonical_batch FOREIGN KEY (batch_id) REFERENCES migration_batch(id),
    CONSTRAINT fk_migration_canonical_raw FOREIGN KEY (raw_record_id) REFERENCES migration_raw_record(id)
);

CREATE TABLE migration_staging_record (
    id VARCHAR(36) PRIMARY KEY,
    batch_id VARCHAR(36) NOT NULL,
    canonical_record_id VARCHAR(36) NOT NULL,
    validation_status VARCHAR(20) NOT NULL,
    target_action VARCHAR(20) NOT NULL,
    target_id VARCHAR(36),
    staged_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_migration_staging_canonical UNIQUE (canonical_record_id),
    CONSTRAINT fk_migration_staging_batch FOREIGN KEY (batch_id) REFERENCES migration_batch(id),
    CONSTRAINT fk_migration_staging_canonical FOREIGN KEY (canonical_record_id) REFERENCES migration_canonical_record(id),
    CONSTRAINT ck_migration_staging_status CHECK (validation_status IN ('VALID', 'INVALID')),
    CONSTRAINT ck_migration_target_action CHECK (target_action IN ('MAP', 'INSERT', 'SKIP'))
);

CREATE TABLE migration_object_map (
    id VARCHAR(36) PRIMARY KEY,
    batch_id VARCHAR(36) NOT NULL,
    community_id VARCHAR(36) NOT NULL,
    resource_type VARCHAR(30) NOT NULL,
    source_system VARCHAR(80) NOT NULL,
    source_id VARCHAR(120) NOT NULL,
    target_id VARCHAR(36) NOT NULL,
    target_code VARCHAR(120),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    rolled_back_at DATETIME(3),
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_migration_map_source UNIQUE (batch_id, resource_type, source_id),
    CONSTRAINT uk_migration_map_target UNIQUE (batch_id, resource_type, target_id),
    CONSTRAINT fk_migration_map_batch FOREIGN KEY (batch_id) REFERENCES migration_batch(id),
    CONSTRAINT fk_migration_map_community FOREIGN KEY (community_id) REFERENCES community(id)
);
CREATE INDEX idx_migration_map_lookup ON migration_object_map (community_id, resource_type, source_system, source_id, active);

CREATE TABLE migration_reconciliation (
    id VARCHAR(36) PRIMARY KEY,
    batch_id VARCHAR(36) NOT NULL,
    metric_name VARCHAR(100) NOT NULL,
    source_value DECIMAL(20,4) NOT NULL,
    target_value DECIMAL(20,4) NOT NULL,
    difference_value DECIMAL(20,4) NOT NULL,
    status VARCHAR(20) NOT NULL,
    detail_json JSON,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_migration_reconciliation_metric UNIQUE (batch_id, metric_name),
    CONSTRAINT fk_migration_reconciliation_batch FOREIGN KEY (batch_id) REFERENCES migration_batch(id),
    CONSTRAINT ck_migration_reconciliation_status CHECK (status IN ('MATCHED', 'MISMATCH'))
);

CREATE TABLE migration_change_log (
    id VARCHAR(36) PRIMARY KEY,
    batch_id VARCHAR(36) NOT NULL,
    execution_order INT NOT NULL,
    resource_type VARCHAR(30) NOT NULL,
    target_table VARCHAR(80) NOT NULL,
    target_id VARCHAR(36) NOT NULL,
    action_type VARCHAR(20) NOT NULL,
    snapshot_json JSON NOT NULL,
    rolled_back_at DATETIME(3),
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_migration_change_order UNIQUE (batch_id, execution_order),
    CONSTRAINT fk_migration_change_batch FOREIGN KEY (batch_id) REFERENCES migration_batch(id),
    CONSTRAINT ck_migration_change_action CHECK (action_type IN ('INSERT'))
);
CREATE INDEX idx_migration_change_reverse ON migration_change_log (batch_id, execution_order DESC);

CREATE TABLE migration_batch_event (
    id VARCHAR(36) PRIMARY KEY,
    batch_id VARCHAR(36) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    from_status VARCHAR(30),
    to_status VARCHAR(30) NOT NULL,
    detail_json JSON,
    actor_user_id VARCHAR(36) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_migration_event_batch FOREIGN KEY (batch_id) REFERENCES migration_batch(id),
    CONSTRAINT fk_migration_event_actor FOREIGN KEY (actor_user_id) REFERENCES sys_user(id)
);
CREATE INDEX idx_migration_event_timeline ON migration_batch_event (batch_id, created_at);
