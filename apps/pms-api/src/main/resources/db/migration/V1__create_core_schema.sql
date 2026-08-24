CREATE TABLE sys_user (
    id VARCHAR(36) PRIMARY KEY,
    username VARCHAR(80) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_sys_user_username UNIQUE (username)
);

CREATE TABLE sys_role (
    id VARCHAR(36) PRIMARY KEY,
    code VARCHAR(80) NOT NULL,
    name VARCHAR(120) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_sys_role_code UNIQUE (code)
);

CREATE TABLE sys_permission (
    id VARCHAR(36) PRIMARY KEY,
    code VARCHAR(120) NOT NULL,
    name VARCHAR(160) NOT NULL,
    resource_type VARCHAR(40) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_sys_permission_code UNIQUE (code)
);

CREATE TABLE sys_user_role (
    user_id VARCHAR(36) NOT NULL,
    role_id VARCHAR(36) NOT NULL,
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
    CONSTRAINT fk_user_role_role FOREIGN KEY (role_id) REFERENCES sys_role(id)
);

CREATE TABLE sys_role_permission (
    role_id VARCHAR(36) NOT NULL,
    permission_id VARCHAR(36) NOT NULL,
    PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id) REFERENCES sys_role(id),
    CONSTRAINT fk_role_permission_permission FOREIGN KEY (permission_id) REFERENCES sys_permission(id)
);

CREATE TABLE community (
    id VARCHAR(36) PRIMARY KEY,
    source_system VARCHAR(40),
    source_id VARCHAR(80),
    name VARCHAR(160) NOT NULL,
    managed_area DECIMAL(18,2) NOT NULL DEFAULT 0,
    address VARCHAR(500),
    province_code VARCHAR(20),
    city_code VARCHAR(20),
    district_code VARCHAR(20),
    longitude DECIMAL(11,8),
    latitude DECIMAL(11,8),
    service_phone VARCHAR(120),
    contact_name VARCHAR(160),
    status VARCHAR(30) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_community_source UNIQUE (source_system, source_id)
);

CREATE TABLE sys_user_project_scope (
    user_id VARCHAR(36) NOT NULL,
    community_id VARCHAR(36) NOT NULL,
    data_scope VARCHAR(30) NOT NULL DEFAULT 'PROJECT',
    PRIMARY KEY (user_id, community_id),
    CONSTRAINT fk_user_scope_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
    CONSTRAINT fk_user_scope_community FOREIGN KEY (community_id) REFERENCES community(id)
);

CREATE TABLE building (
    id VARCHAR(36) PRIMARY KEY,
    community_id VARCHAR(36) NOT NULL,
    source_id VARCHAR(80),
    code VARCHAR(80) NOT NULL,
    name VARCHAR(160) NOT NULL,
    building_type VARCHAR(30) NOT NULL DEFAULT 'RESIDENTIAL',
    floor_count INT,
    status VARCHAR(30) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_building_code UNIQUE (community_id, code),
    CONSTRAINT fk_building_community FOREIGN KEY (community_id) REFERENCES community(id)
);

CREATE TABLE pms_unit (
    id VARCHAR(36) PRIMARY KEY,
    building_id VARCHAR(36) NOT NULL,
    code VARCHAR(80) NOT NULL,
    name VARCHAR(160) NOT NULL,
    status VARCHAR(30) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_unit_code UNIQUE (building_id, code),
    CONSTRAINT fk_unit_building FOREIGN KEY (building_id) REFERENCES building(id)
);

CREATE TABLE asset (
    id VARCHAR(36) PRIMARY KEY,
    community_id VARCHAR(36) NOT NULL,
    building_id VARCHAR(36),
    unit_id VARCHAR(36),
    source_id VARCHAR(80),
    asset_type VARCHAR(30) NOT NULL,
    code VARCHAR(100) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    floor_no VARCHAR(30),
    building_area DECIMAL(18,2) NOT NULL DEFAULT 0,
    usable_area DECIMAL(18,2) NOT NULL DEFAULT 0,
    occupancy_status VARCHAR(30) NOT NULL,
    operation_status VARCHAR(30) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    valid_from DATE,
    valid_to DATE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_asset_code UNIQUE (community_id, asset_type, code),
    CONSTRAINT fk_asset_community FOREIGN KEY (community_id) REFERENCES community(id),
    CONSTRAINT fk_asset_building FOREIGN KEY (building_id) REFERENCES building(id),
    CONSTRAINT fk_asset_unit FOREIGN KEY (unit_id) REFERENCES pms_unit(id)
);

CREATE INDEX idx_asset_query ON asset (community_id, asset_type, occupancy_status, display_name);

CREATE TABLE room_detail (
    asset_id VARCHAR(36) PRIMARY KEY,
    room_type VARCHAR(30) NOT NULL,
    delivery_date DATE,
    CONSTRAINT fk_room_asset FOREIGN KEY (asset_id) REFERENCES asset(id)
);

CREATE TABLE parking_space_detail (
    asset_id VARCHAR(36) PRIMARY KEY,
    parking_type VARCHAR(30) NOT NULL,
    ownership_type VARCHAR(30) NOT NULL,
    related_room_asset_id VARCHAR(36),
    CONSTRAINT fk_parking_asset FOREIGN KEY (asset_id) REFERENCES asset(id),
    CONSTRAINT fk_parking_room FOREIGN KEY (related_room_asset_id) REFERENCES asset(id)
);

CREATE TABLE grid_area (
    id VARCHAR(36) PRIMARY KEY,
    community_id VARCHAR(36) NOT NULL,
    code VARCHAR(80) NOT NULL,
    name VARCHAR(160) NOT NULL,
    manager_user_id VARCHAR(36),
    status VARCHAR(30) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_grid_code UNIQUE (community_id, code),
    CONSTRAINT fk_grid_community FOREIGN KEY (community_id) REFERENCES community(id),
    CONSTRAINT fk_grid_manager FOREIGN KEY (manager_user_id) REFERENCES sys_user(id)
);

CREATE TABLE customer (
    id VARCHAR(36) PRIMARY KEY,
    community_id VARCHAR(36) NOT NULL,
    source_id VARCHAR(80),
    display_name VARCHAR(160) NOT NULL,
    customer_type VARCHAR(30) NOT NULL,
    customer_class VARCHAR(30),
    mobile_masked VARCHAR(40),
    mobile_ciphertext VARCHAR(500),
    mobile_search_hash VARCHAR(128),
    certificate_type VARCHAR(30),
    certificate_masked VARCHAR(80),
    certificate_ciphertext VARCHAR(800),
    gender VARCHAR(20),
    status VARCHAR(30) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_customer_community FOREIGN KEY (community_id) REFERENCES community(id)
);

CREATE INDEX idx_customer_query ON customer (community_id, status, display_name);

CREATE TABLE customer_asset_relation (
    id VARCHAR(36) PRIMARY KEY,
    customer_id VARCHAR(36) NOT NULL,
    asset_id VARCHAR(36) NOT NULL,
    relation_type VARCHAR(30) NOT NULL,
    primary_relation BOOLEAN NOT NULL DEFAULT FALSE,
    start_date DATE NOT NULL,
    end_date DATE,
    status VARCHAR(30) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_customer_asset_period UNIQUE (customer_id, asset_id, relation_type, start_date),
    CONSTRAINT fk_relation_customer FOREIGN KEY (customer_id) REFERENCES customer(id),
    CONSTRAINT fk_relation_asset FOREIGN KEY (asset_id) REFERENCES asset(id)
);

CREATE TABLE vehicle (
    id VARCHAR(36) PRIMARY KEY,
    community_id VARCHAR(36) NOT NULL,
    plate_no_masked VARCHAR(40) NOT NULL,
    plate_no_search_hash VARCHAR(128) NOT NULL,
    vehicle_type VARCHAR(30) NOT NULL,
    color VARCHAR(30),
    status VARCHAR(30) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_vehicle_plate UNIQUE (community_id, plate_no_search_hash),
    CONSTRAINT fk_vehicle_community FOREIGN KEY (community_id) REFERENCES community(id)
);

CREATE TABLE vehicle_parking_relation (
    id VARCHAR(36) PRIMARY KEY,
    vehicle_id VARCHAR(36) NOT NULL,
    parking_asset_id VARCHAR(36) NOT NULL,
    customer_id VARCHAR(36),
    start_date DATE NOT NULL,
    end_date DATE,
    status VARCHAR(30) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_vpr_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicle(id),
    CONSTRAINT fk_vpr_parking FOREIGN KEY (parking_asset_id) REFERENCES asset(id),
    CONSTRAINT fk_vpr_customer FOREIGN KEY (customer_id) REFERENCES customer(id)
);

CREATE TABLE meter (
    id VARCHAR(36) PRIMARY KEY,
    community_id VARCHAR(36) NOT NULL,
    asset_id VARCHAR(36),
    parent_meter_id VARCHAR(36),
    meter_no VARCHAR(100) NOT NULL,
    meter_type VARCHAR(30) NOT NULL,
    meter_class VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL,
    range_value DECIMAL(18,4),
    multiplier DECIMAL(18,6) NOT NULL DEFAULT 1,
    loss_rate DECIMAL(10,6) NOT NULL DEFAULT 0,
    correction DECIMAL(18,4) NOT NULL DEFAULT 0,
    installed_at DATETIME(3),
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_meter_no UNIQUE (community_id, meter_no),
    CONSTRAINT fk_meter_community FOREIGN KEY (community_id) REFERENCES community(id),
    CONSTRAINT fk_meter_asset FOREIGN KEY (asset_id) REFERENCES asset(id),
    CONSTRAINT fk_meter_parent FOREIGN KEY (parent_meter_id) REFERENCES meter(id)
);

CREATE TABLE fee_definition (
    id VARCHAR(36) PRIMARY KEY,
    community_id VARCHAR(36) NOT NULL,
    source_id VARCHAR(80),
    code VARCHAR(80) NOT NULL,
    name VARCHAR(160) NOT NULL,
    fee_type VARCHAR(30) NOT NULL,
    fee_class VARCHAR(30) NOT NULL,
    unit_code VARCHAR(30) NOT NULL,
    decimal_scale INT NOT NULL DEFAULT 2,
    late_fee_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    accounting_subject_code VARCHAR(80),
    prepayment_subject_code VARCHAR(80),
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_fee_definition_code UNIQUE (community_id, code),
    CONSTRAINT fk_fee_definition_community FOREIGN KEY (community_id) REFERENCES community(id)
);

CREATE TABLE fee_standard (
    id VARCHAR(36) PRIMARY KEY,
    fee_definition_id VARCHAR(36) NOT NULL,
    code VARCHAR(80) NOT NULL,
    name VARCHAR(160) NOT NULL,
    asset_type VARCHAR(30) NOT NULL,
    billing_cycle VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_fee_standard_code UNIQUE (fee_definition_id, code),
    CONSTRAINT fk_fee_standard_definition FOREIGN KEY (fee_definition_id) REFERENCES fee_definition(id)
);

CREATE TABLE fee_standard_version (
    id VARCHAR(36) PRIMARY KEY,
    fee_standard_id VARCHAR(36) NOT NULL,
    version_no INT NOT NULL,
    unit_price DECIMAL(18,6) NOT NULL DEFAULT 0,
    formula_code VARCHAR(80) NOT NULL,
    formula_expression VARCHAR(1000),
    effective_from DATE NOT NULL,
    effective_to DATE,
    status VARCHAR(30) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_standard_version UNIQUE (fee_standard_id, version_no),
    CONSTRAINT fk_standard_version_standard FOREIGN KEY (fee_standard_id) REFERENCES fee_standard(id)
);

CREATE TABLE fee_allocation (
    id VARCHAR(36) PRIMARY KEY,
    fee_standard_id VARCHAR(36) NOT NULL,
    asset_id VARCHAR(36) NOT NULL,
    coefficient DECIMAL(18,6) NOT NULL DEFAULT 1,
    effective_from DATE NOT NULL,
    effective_to DATE,
    status VARCHAR(30) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_fee_allocation_period UNIQUE (fee_standard_id, asset_id, effective_from),
    CONSTRAINT fk_allocation_standard FOREIGN KEY (fee_standard_id) REFERENCES fee_standard(id),
    CONSTRAINT fk_allocation_asset FOREIGN KEY (asset_id) REFERENCES asset(id)
);

CREATE TABLE receivable_generation_job (
    id VARCHAR(36) PRIMARY KEY,
    community_id VARCHAR(36) NOT NULL,
    billing_period VARCHAR(20) NOT NULL,
    request_key VARCHAR(120) NOT NULL,
    status VARCHAR(30) NOT NULL,
    preview BOOLEAN NOT NULL,
    generated_count INT NOT NULL DEFAULT 0,
    error_count INT NOT NULL DEFAULT 0,
    requested_by VARCHAR(36) NOT NULL,
    started_at DATETIME(3),
    completed_at DATETIME(3),
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_receivable_job_request UNIQUE (community_id, request_key),
    CONSTRAINT fk_receivable_job_community FOREIGN KEY (community_id) REFERENCES community(id),
    CONSTRAINT fk_receivable_job_user FOREIGN KEY (requested_by) REFERENCES sys_user(id)
);

CREATE TABLE receivable_generation_error (
    id VARCHAR(36) PRIMARY KEY,
    job_id VARCHAR(36) NOT NULL,
    asset_id VARCHAR(36),
    fee_standard_id VARCHAR(36),
    error_code VARCHAR(80) NOT NULL,
    error_message VARCHAR(500) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_receivable_error_job FOREIGN KEY (job_id) REFERENCES receivable_generation_job(id)
);

CREATE TABLE bill (
    id VARCHAR(36) PRIMARY KEY,
    community_id VARCHAR(36) NOT NULL,
    asset_id VARCHAR(36) NOT NULL,
    customer_id VARCHAR(36),
    bill_no VARCHAR(80) NOT NULL,
    billing_period VARCHAR(20) NOT NULL,
    status VARCHAR(30) NOT NULL,
    total_amount DECIMAL(18,2) NOT NULL,
    paid_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
    outstanding_amount DECIMAL(18,2) NOT NULL,
    due_date DATE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_bill_no UNIQUE (bill_no),
    CONSTRAINT fk_bill_community FOREIGN KEY (community_id) REFERENCES community(id),
    CONSTRAINT fk_bill_asset FOREIGN KEY (asset_id) REFERENCES asset(id),
    CONSTRAINT fk_bill_customer FOREIGN KEY (customer_id) REFERENCES customer(id)
);

CREATE INDEX idx_bill_cashier ON bill (community_id, asset_id, status, due_date);

CREATE TABLE bill_item (
    id VARCHAR(36) PRIMARY KEY,
    bill_id VARCHAR(36) NOT NULL,
    fee_definition_id VARCHAR(36) NOT NULL,
    fee_standard_version_id VARCHAR(36),
    item_name_snapshot VARCHAR(160) NOT NULL,
    quantity DECIMAL(18,6) NOT NULL,
    unit_price DECIMAL(18,6) NOT NULL,
    coefficient DECIMAL(18,6) NOT NULL DEFAULT 1,
    amount DECIMAL(18,2) NOT NULL,
    calculation_snapshot TEXT,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_bill_item_bill FOREIGN KEY (bill_id) REFERENCES bill(id),
    CONSTRAINT fk_bill_item_definition FOREIGN KEY (fee_definition_id) REFERENCES fee_definition(id)
);

CREATE TABLE payment_order (
    id VARCHAR(36) PRIMARY KEY,
    community_id VARCHAR(36) NOT NULL,
    order_no VARCHAR(80) NOT NULL,
    idempotency_key VARCHAR(120) NOT NULL,
    payment_method VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL,
    requested_amount DECIMAL(18,2) NOT NULL,
    confirmed_amount DECIMAL(18,2),
    requested_by VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_payment_order_no UNIQUE (order_no),
    CONSTRAINT uk_payment_order_idempotency UNIQUE (community_id, idempotency_key),
    CONSTRAINT fk_payment_order_community FOREIGN KEY (community_id) REFERENCES community(id),
    CONSTRAINT fk_payment_order_user FOREIGN KEY (requested_by) REFERENCES sys_user(id)
);

CREATE TABLE payment_transaction (
    id VARCHAR(36) PRIMARY KEY,
    payment_order_id VARCHAR(36) NOT NULL,
    transaction_no VARCHAR(100) NOT NULL,
    adapter_code VARCHAR(40) NOT NULL,
    transaction_type VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL,
    amount DECIMAL(18,2) NOT NULL,
    external_reference VARCHAR(160),
    occurred_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_payment_transaction_no UNIQUE (transaction_no),
    CONSTRAINT fk_payment_transaction_order FOREIGN KEY (payment_order_id) REFERENCES payment_order(id)
);

CREATE TABLE payment_allocation (
    id VARCHAR(36) PRIMARY KEY,
    payment_transaction_id VARCHAR(36) NOT NULL,
    bill_id VARCHAR(36) NOT NULL,
    allocated_amount DECIMAL(18,2) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_payment_bill UNIQUE (payment_transaction_id, bill_id),
    CONSTRAINT fk_payment_allocation_transaction FOREIGN KEY (payment_transaction_id) REFERENCES payment_transaction(id),
    CONSTRAINT fk_payment_allocation_bill FOREIGN KEY (bill_id) REFERENCES bill(id)
);

CREATE TABLE prepayment_account (
    id VARCHAR(36) PRIMARY KEY,
    community_id VARCHAR(36) NOT NULL,
    customer_id VARCHAR(36) NOT NULL,
    balance DECIMAL(18,2) NOT NULL DEFAULT 0,
    frozen_balance DECIMAL(18,2) NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_prepayment_customer UNIQUE (community_id, customer_id),
    CONSTRAINT fk_prepayment_community FOREIGN KEY (community_id) REFERENCES community(id),
    CONSTRAINT fk_prepayment_customer FOREIGN KEY (customer_id) REFERENCES customer(id)
);

CREATE TABLE prepayment_transaction (
    id VARCHAR(36) PRIMARY KEY,
    account_id VARCHAR(36) NOT NULL,
    transaction_type VARCHAR(30) NOT NULL,
    amount DECIMAL(18,2) NOT NULL,
    balance_after DECIMAL(18,2) NOT NULL,
    reference_type VARCHAR(40),
    reference_id VARCHAR(36),
    occurred_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_prepayment_txn_account FOREIGN KEY (account_id) REFERENCES prepayment_account(id)
);

CREATE TABLE deposit_account (
    id VARCHAR(36) PRIMARY KEY,
    community_id VARCHAR(36) NOT NULL,
    customer_id VARCHAR(36) NOT NULL,
    asset_id VARCHAR(36),
    deposit_type VARCHAR(40) NOT NULL,
    balance DECIMAL(18,2) NOT NULL DEFAULT 0,
    status VARCHAR(30) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_deposit_community FOREIGN KEY (community_id) REFERENCES community(id),
    CONSTRAINT fk_deposit_customer FOREIGN KEY (customer_id) REFERENCES customer(id),
    CONSTRAINT fk_deposit_asset FOREIGN KEY (asset_id) REFERENCES asset(id)
);

CREATE TABLE deposit_transaction (
    id VARCHAR(36) PRIMARY KEY,
    account_id VARCHAR(36) NOT NULL,
    transaction_type VARCHAR(30) NOT NULL,
    amount DECIMAL(18,2) NOT NULL,
    balance_after DECIMAL(18,2) NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_deposit_txn_account FOREIGN KEY (account_id) REFERENCES deposit_account(id)
);

CREATE TABLE receipt (
    id VARCHAR(36) PRIMARY KEY,
    community_id VARCHAR(36) NOT NULL,
    payment_order_id VARCHAR(36) NOT NULL,
    receipt_no VARCHAR(80) NOT NULL,
    status VARCHAR(30) NOT NULL,
    template_version VARCHAR(40) NOT NULL,
    data_snapshot TEXT NOT NULL,
    issued_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_receipt_no UNIQUE (receipt_no),
    CONSTRAINT fk_receipt_community FOREIGN KEY (community_id) REFERENCES community(id),
    CONSTRAINT fk_receipt_order FOREIGN KEY (payment_order_id) REFERENCES payment_order(id)
);

CREATE TABLE reversal (
    id VARCHAR(36) PRIMARY KEY,
    original_transaction_id VARCHAR(36) NOT NULL,
    reversal_transaction_id VARCHAR(36) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    approved_by VARCHAR(36),
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_reversal_original UNIQUE (original_transaction_id),
    CONSTRAINT fk_reversal_original FOREIGN KEY (original_transaction_id) REFERENCES payment_transaction(id),
    CONSTRAINT fk_reversal_transaction FOREIGN KEY (reversal_transaction_id) REFERENCES payment_transaction(id),
    CONSTRAINT fk_reversal_approver FOREIGN KEY (approved_by) REFERENCES sys_user(id)
);

CREATE TABLE daily_settlement (
    id VARCHAR(36) PRIMARY KEY,
    community_id VARCHAR(36) NOT NULL,
    settlement_date DATE NOT NULL,
    status VARCHAR(30) NOT NULL,
    total_amount DECIMAL(18,2) NOT NULL,
    transaction_count INT NOT NULL,
    calculation_snapshot TEXT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_daily_settlement UNIQUE (community_id, settlement_date),
    CONSTRAINT fk_settlement_community FOREIGN KEY (community_id) REFERENCES community(id)
);

CREATE TABLE meter_reading_batch (
    id VARCHAR(36) PRIMARY KEY,
    community_id VARCHAR(36) NOT NULL,
    batch_no VARCHAR(80) NOT NULL,
    reading_period VARCHAR(20) NOT NULL,
    status VARCHAR(30) NOT NULL,
    source_type VARCHAR(30) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_meter_batch_no UNIQUE (community_id, batch_no),
    CONSTRAINT fk_meter_batch_community FOREIGN KEY (community_id) REFERENCES community(id)
);

CREATE TABLE meter_reading (
    id VARCHAR(36) PRIMARY KEY,
    batch_id VARCHAR(36) NOT NULL,
    meter_id VARCHAR(36) NOT NULL,
    previous_reading DECIMAL(18,4) NOT NULL,
    current_reading DECIMAL(18,4) NOT NULL,
    raw_usage DECIMAL(18,4) NOT NULL,
    multiplier DECIMAL(18,6) NOT NULL,
    correction DECIMAL(18,4) NOT NULL,
    allocated_share DECIMAL(18,4) NOT NULL DEFAULT 0,
    billable_usage DECIMAL(18,4) NOT NULL,
    reading_at DATETIME(3) NOT NULL,
    status VARCHAR(30) NOT NULL,
    calculation_snapshot TEXT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_meter_reading_batch UNIQUE (batch_id, meter_id),
    CONSTRAINT fk_meter_reading_batch FOREIGN KEY (batch_id) REFERENCES meter_reading_batch(id),
    CONSTRAINT fk_meter_reading_meter FOREIGN KEY (meter_id) REFERENCES meter(id)
);

CREATE TABLE meter_replacement (
    id VARCHAR(36) PRIMARY KEY,
    old_meter_id VARCHAR(36) NOT NULL,
    new_meter_id VARCHAR(36) NOT NULL,
    old_final_reading DECIMAL(18,4) NOT NULL,
    new_initial_reading DECIMAL(18,4) NOT NULL,
    replaced_at DATETIME(3) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    operated_by VARCHAR(36) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_meter_replacement_old UNIQUE (old_meter_id),
    CONSTRAINT fk_replacement_old FOREIGN KEY (old_meter_id) REFERENCES meter(id),
    CONSTRAINT fk_replacement_new FOREIGN KEY (new_meter_id) REFERENCES meter(id),
    CONSTRAINT fk_replacement_user FOREIGN KEY (operated_by) REFERENCES sys_user(id)
);

CREATE TABLE meter_share_rule (
    id VARCHAR(36) PRIMARY KEY,
    community_id VARCHAR(36) NOT NULL,
    code VARCHAR(80) NOT NULL,
    name VARCHAR(160) NOT NULL,
    strategy_code VARCHAR(80) NOT NULL,
    config_json TEXT NOT NULL,
    status VARCHAR(30) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_meter_share_rule UNIQUE (community_id, code),
    CONSTRAINT fk_meter_share_community FOREIGN KEY (community_id) REFERENCES community(id)
);

CREATE TABLE meter_share_result (
    id VARCHAR(36) PRIMARY KEY,
    rule_id VARCHAR(36) NOT NULL,
    batch_id VARCHAR(36) NOT NULL,
    asset_id VARCHAR(36) NOT NULL,
    allocated_usage DECIMAL(18,4) NOT NULL,
    calculation_snapshot TEXT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_meter_share_result UNIQUE (rule_id, batch_id, asset_id),
    CONSTRAINT fk_share_result_rule FOREIGN KEY (rule_id) REFERENCES meter_share_rule(id),
    CONSTRAINT fk_share_result_batch FOREIGN KEY (batch_id) REFERENCES meter_reading_batch(id),
    CONSTRAINT fk_share_result_asset FOREIGN KEY (asset_id) REFERENCES asset(id)
);

CREATE TABLE audit_event (
    id VARCHAR(36) PRIMARY KEY,
    actor_user_id VARCHAR(36),
    community_id VARCHAR(36),
    action_code VARCHAR(120) NOT NULL,
    resource_type VARCHAR(80) NOT NULL,
    resource_id VARCHAR(80),
    request_id VARCHAR(80),
    result_status VARCHAR(30) NOT NULL,
    detail_json TEXT,
    occurred_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_audit_actor FOREIGN KEY (actor_user_id) REFERENCES sys_user(id),
    CONSTRAINT fk_audit_community FOREIGN KEY (community_id) REFERENCES community(id)
);

CREATE INDEX idx_audit_query ON audit_event (community_id, occurred_at, action_code);

CREATE TABLE idempotency_record (
    id VARCHAR(36) PRIMARY KEY,
    scope_key VARCHAR(160) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_hash VARCHAR(128) NOT NULL,
    response_status INT,
    response_body TEXT,
    status VARCHAR(30) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_idempotency UNIQUE (scope_key, idempotency_key)
);

CREATE TABLE outbox_event (
    id VARCHAR(36) PRIMARY KEY,
    aggregate_type VARCHAR(80) NOT NULL,
    aggregate_id VARCHAR(80) NOT NULL,
    event_type VARCHAR(120) NOT NULL,
    payload_json TEXT NOT NULL,
    status VARCHAR(30) NOT NULL,
    available_at DATETIME(3) NOT NULL,
    published_at DATETIME(3),
    retry_count INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL
);

CREATE INDEX idx_outbox_pending ON outbox_event (status, available_at);

