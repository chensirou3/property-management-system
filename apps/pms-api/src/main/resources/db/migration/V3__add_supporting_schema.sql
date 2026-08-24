CREATE TABLE meter_import_job (
    id VARCHAR(36) PRIMARY KEY,
    community_id VARCHAR(36) NOT NULL,
    batch_id VARCHAR(36),
    file_name VARCHAR(255) NOT NULL,
    file_sha256 VARCHAR(64) NOT NULL,
    status VARCHAR(30) NOT NULL,
    total_count INT NOT NULL DEFAULT 0,
    success_count INT NOT NULL DEFAULT 0,
    error_count INT NOT NULL DEFAULT 0,
    error_report_json TEXT,
    requested_by VARCHAR(36) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    completed_at DATETIME(3),
    CONSTRAINT uk_meter_import_hash UNIQUE (community_id, file_sha256),
    CONSTRAINT fk_meter_import_community FOREIGN KEY (community_id) REFERENCES community(id),
    CONSTRAINT fk_meter_import_batch FOREIGN KEY (batch_id) REFERENCES meter_reading_batch(id),
    CONSTRAINT fk_meter_import_user FOREIGN KEY (requested_by) REFERENCES sys_user(id)
);

CREATE TABLE data_transfer_job (
    id VARCHAR(36) PRIMARY KEY,
    community_id VARCHAR(36),
    job_type VARCHAR(30) NOT NULL,
    resource_type VARCHAR(80) NOT NULL,
    filter_json TEXT,
    status VARCHAR(30) NOT NULL,
    total_count INT NOT NULL DEFAULT 0,
    success_count INT NOT NULL DEFAULT 0,
    error_count INT NOT NULL DEFAULT 0,
    result_uri VARCHAR(500),
    requested_by VARCHAR(36) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    completed_at DATETIME(3),
    CONSTRAINT fk_transfer_job_community FOREIGN KEY (community_id) REFERENCES community(id),
    CONSTRAINT fk_transfer_job_user FOREIGN KEY (requested_by) REFERENCES sys_user(id)
);

CREATE INDEX idx_transfer_job_query ON data_transfer_job (community_id, resource_type, created_at);

CREATE TABLE system_dictionary (
    id VARCHAR(36) PRIMARY KEY,
    dictionary_type VARCHAR(80) NOT NULL,
    code VARCHAR(80) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_dictionary_code UNIQUE (dictionary_type, code)
);

INSERT INTO system_dictionary
    (id, dictionary_type, code, display_name, sort_order, enabled, created_at, updated_at)
VALUES
    ('21000000-0000-0000-0000-000000000001', 'STATUS', 'ACTIVE', '启用', 10, TRUE, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)),
    ('21000000-0000-0000-0000-000000000002', 'STATUS', 'INACTIVE', '停用', 20, TRUE, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)),
    ('21000000-0000-0000-0000-000000000003', 'ASSET_TYPE', 'ROOM', '房屋', 10, TRUE, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)),
    ('21000000-0000-0000-0000-000000000004', 'ASSET_TYPE', 'PARKING', '车位', 20, TRUE, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)),
    ('21000000-0000-0000-0000-000000000005', 'RELATION_TYPE', 'OWNER', '业主', 10, TRUE, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)),
    ('21000000-0000-0000-0000-000000000006', 'RELATION_TYPE', 'CO_OWNER', '共有', 20, TRUE, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)),
    ('21000000-0000-0000-0000-000000000007', 'RELATION_TYPE', 'TENANT', '租户', 30, TRUE, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)),
    ('21000000-0000-0000-0000-000000000008', 'PAYMENT_METHOD', 'CASH', '现金', 10, TRUE, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)),
    ('21000000-0000-0000-0000-000000000009', 'PAYMENT_METHOD', 'TRANSFER', '转账', 20, TRUE, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)),
    ('21000000-0000-0000-0000-000000000010', 'PAYMENT_METHOD', 'SIMULATED_ONLINE', '模拟线上支付', 30, TRUE, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3));
