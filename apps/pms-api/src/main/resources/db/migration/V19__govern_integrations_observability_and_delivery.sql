-- G9: fail-closed adapter policies, signed callback inbox, delivery attempts and dead letters.

CREATE TABLE integration_adapter_policy (
    adapter_code VARCHAR(80) PRIMARY KEY,
    adapter_type VARCHAR(40) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    mode VARCHAR(20) NOT NULL,
    enabled BOOLEAN NOT NULL,
    production_ready BOOLEAN NOT NULL DEFAULT FALSE,
    endpoint_masked VARCHAR(240) NOT NULL,
    credential_status VARCHAR(30) NOT NULL,
    signing_required BOOLEAN NOT NULL,
    timeout_ms INT NOT NULL,
    max_attempts INT NOT NULL,
    retry_base_seconds INT NOT NULL,
    last_checked_at DATETIME(3),
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT ck_integration_adapter_mode CHECK (mode IN ('SIMULATOR', 'DISABLED')),
    CONSTRAINT ck_integration_adapter_enabled CHECK (
        (mode='SIMULATOR' AND enabled=TRUE AND production_ready=FALSE)
        OR (mode='DISABLED' AND enabled=FALSE AND production_ready=FALSE)
    ),
    CONSTRAINT ck_integration_adapter_limits CHECK (
        timeout_ms BETWEEN 100 AND 30000 AND max_attempts BETWEEN 1 AND 10
        AND retry_base_seconds BETWEEN 1 AND 3600
    )
);

CREATE TABLE integration_callback_inbox (
    id VARCHAR(36) PRIMARY KEY,
    community_id VARCHAR(36) NOT NULL,
    adapter_code VARCHAR(80) NOT NULL,
    callback_id VARCHAR(160) NOT NULL,
    request_id VARCHAR(100) NOT NULL,
    signed_at DATETIME(3) NOT NULL,
    signature_fingerprint CHAR(16) NOT NULL,
    payload_json JSON NOT NULL,
    payload_checksum CHAR(64) NOT NULL,
    status VARCHAR(30) NOT NULL,
    replay_count INT NOT NULL DEFAULT 0,
    processed_at DATETIME(3),
    last_error VARCHAR(1000),
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_integration_callback UNIQUE (adapter_code, callback_id),
    CONSTRAINT fk_integration_callback_community FOREIGN KEY (community_id) REFERENCES community(id),
    CONSTRAINT fk_integration_callback_adapter FOREIGN KEY (adapter_code) REFERENCES integration_adapter_policy(adapter_code),
    CONSTRAINT ck_integration_callback_status CHECK (status IN ('VERIFIED', 'PROCESSED', 'REJECTED')),
    CONSTRAINT ck_integration_callback_replay CHECK (replay_count >= 0)
);
CREATE INDEX idx_integration_callback_query ON integration_callback_inbox (community_id, status, created_at);

CREATE TABLE integration_delivery_attempt (
    id VARCHAR(36) PRIMARY KEY,
    direction VARCHAR(20) NOT NULL,
    adapter_code VARCHAR(80) NOT NULL,
    reference_id VARCHAR(160) NOT NULL,
    attempt_no INT NOT NULL,
    outcome VARCHAR(30) NOT NULL,
    http_status INT,
    duration_ms BIGINT NOT NULL,
    detail_json JSON NOT NULL,
    detail_checksum CHAR(64) NOT NULL,
    next_retry_at DATETIME(3),
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_integration_attempt UNIQUE (direction, adapter_code, reference_id, attempt_no),
    CONSTRAINT fk_integration_attempt_adapter FOREIGN KEY (adapter_code) REFERENCES integration_adapter_policy(adapter_code),
    CONSTRAINT ck_integration_attempt_direction CHECK (direction IN ('INBOUND', 'OUTBOUND', 'CONNECTION_TEST')),
    CONSTRAINT ck_integration_attempt_outcome CHECK (outcome IN ('SUCCEEDED', 'RETRYABLE_FAILURE', 'REJECTED', 'REPLAYED', 'DISABLED')),
    CONSTRAINT ck_integration_attempt_number CHECK (attempt_no > 0 AND duration_ms >= 0)
);
CREATE INDEX idx_integration_attempt_query ON integration_delivery_attempt (adapter_code, created_at, outcome);

CREATE TABLE integration_dead_letter (
    id VARCHAR(36) PRIMARY KEY,
    direction VARCHAR(20) NOT NULL,
    adapter_code VARCHAR(80) NOT NULL,
    reference_id VARCHAR(160) NOT NULL,
    payload_checksum CHAR(64) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    retry_count INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    replayed_at DATETIME(3),
    resolved_at DATETIME(3),
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_integration_dead_letter UNIQUE (direction, adapter_code, reference_id),
    CONSTRAINT fk_integration_dead_letter_adapter FOREIGN KEY (adapter_code) REFERENCES integration_adapter_policy(adapter_code),
    CONSTRAINT ck_integration_dead_letter_direction CHECK (direction IN ('INBOUND', 'OUTBOUND')),
    CONSTRAINT ck_integration_dead_letter_status CHECK (status IN ('OPEN', 'REPLAYED', 'RESOLVED')),
    CONSTRAINT ck_integration_dead_letter_retry CHECK (retry_count > 0)
);
CREATE INDEX idx_integration_dead_letter_query ON integration_dead_letter (status, created_at);

ALTER TABLE outbox_event
    ADD COLUMN request_id VARCHAR(100) NULL AFTER payload_json,
    ADD COLUMN payload_checksum CHAR(64) NULL AFTER request_id,
    ADD COLUMN last_error VARCHAR(1000) NULL AFTER retry_count,
    ADD COLUMN locked_at DATETIME(3) NULL AFTER last_error,
    ADD COLUMN updated_at DATETIME(3) NULL AFTER created_at;

UPDATE outbox_event
SET payload_checksum=SHA2(payload_json, 256), updated_at=created_at
WHERE payload_checksum IS NULL OR updated_at IS NULL;

ALTER TABLE outbox_event
    MODIFY payload_checksum CHAR(64) NOT NULL,
    MODIFY updated_at DATETIME(3) NOT NULL,
    ADD CONSTRAINT ck_outbox_delivery_status CHECK (status IN ('PENDING', 'PROCESSING', 'PUBLISHED', 'RETRY', 'DEAD_LETTER')),
    ADD CONSTRAINT ck_outbox_retry_count CHECK (retry_count >= 0);

INSERT INTO integration_adapter_policy
    (adapter_code, adapter_type, display_name, mode, enabled, production_ready,
     endpoint_masked, credential_status, signing_required, timeout_ms, max_attempts,
     retry_base_seconds, last_checked_at, created_at, updated_at)
VALUES
    ('PAYMENT_SIMULATOR', 'PAYMENT', '支付模拟器', 'SIMULATOR', TRUE, FALSE, 'local://payment-simulator', 'NOT_REQUIRED', TRUE, 2000, 3, 5, NULL, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)),
    ('INVOICE_SIMULATOR', 'INVOICE', '发票模拟器', 'SIMULATOR', TRUE, FALSE, 'local://invoice-simulator', 'NOT_REQUIRED', TRUE, 2000, 3, 5, NULL, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)),
    ('BANK_TRUST_SIMULATOR', 'BANK', '银行信托模拟器', 'SIMULATOR', TRUE, FALSE, 'local://bank-trust-simulator', 'NOT_REQUIRED', TRUE, 3000, 3, 10, NULL, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)),
    ('IOT_SIMULATOR', 'IOT', 'IoT 模拟器', 'SIMULATOR', TRUE, FALSE, 'local://iot-simulator', 'NOT_REQUIRED', TRUE, 2000, 3, 5, NULL, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)),
    ('JAVA110_DISABLED', 'JAVA110', 'Java110 兼容适配器', 'DISABLED', FALSE, FALSE, 'disabled://java110', 'NOT_CONFIGURED', TRUE, 3000, 3, 10, NULL, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3));
