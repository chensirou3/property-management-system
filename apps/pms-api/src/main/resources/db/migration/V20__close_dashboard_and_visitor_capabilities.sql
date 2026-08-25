-- G10: replace the final structural-only pages with governed internal capabilities.

CREATE TABLE dashboard_widget_configuration (
    id VARCHAR(36) PRIMARY KEY,
    community_id VARCHAR(36) NOT NULL,
    role_code VARCHAR(80) NOT NULL,
    widget_code VARCHAR(80) NOT NULL,
    widget_name VARCHAR(160) NOT NULL,
    metric_code VARCHAR(80) NOT NULL,
    position_code VARCHAR(40) NOT NULL,
    visible BOOLEAN NOT NULL,
    refresh_interval_seconds INT NOT NULL,
    display_order INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    published_at DATETIME(3),
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_dashboard_widget_scope UNIQUE (community_id, role_code, widget_code),
    CONSTRAINT fk_dashboard_widget_community FOREIGN KEY (community_id) REFERENCES community(id),
    CONSTRAINT ck_dashboard_widget_position CHECK (position_code IN ('SUMMARY', 'MAIN', 'SIDE')),
    CONSTRAINT ck_dashboard_widget_interval CHECK (refresh_interval_seconds BETWEEN 30 AND 3600),
    CONSTRAINT ck_dashboard_widget_order CHECK (display_order BETWEEN 1 AND 100),
    CONSTRAINT ck_dashboard_widget_status CHECK (status IN ('DRAFT', 'PUBLISHED'))
);
CREATE INDEX idx_dashboard_widget_scope
    ON dashboard_widget_configuration (community_id, role_code, display_order);

CREATE TABLE visitor_record (
    id VARCHAR(36) PRIMARY KEY,
    community_id VARCHAR(36) NOT NULL,
    visit_no VARCHAR(80) NOT NULL,
    visitor_name_masked VARCHAR(120) NOT NULL,
    visitor_mobile_masked VARCHAR(40) NOT NULL,
    host_name_masked VARCHAR(120) NOT NULL,
    asset_name VARCHAR(160) NOT NULL,
    scheduled_at DATETIME(3) NOT NULL,
    check_in_at DATETIME(3),
    check_out_at DATETIME(3),
    visit_status VARCHAR(20) NOT NULL,
    source_mode VARCHAR(30) NOT NULL,
    production_connected BOOLEAN NOT NULL DEFAULT FALSE,
    adapter_evidence VARCHAR(160),
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_visitor_record_no UNIQUE (community_id, visit_no),
    CONSTRAINT fk_visitor_record_community FOREIGN KEY (community_id) REFERENCES community(id),
    CONSTRAINT ck_visitor_record_status CHECK (visit_status IN ('REGISTERED', 'CHECKED_IN', 'CHECKED_OUT', 'CANCELLED')),
    CONSTRAINT ck_visitor_record_source CHECK (source_mode='IOT_SIMULATOR' AND production_connected=FALSE),
    CONSTRAINT ck_visitor_record_timeline CHECK (
        (visit_status='REGISTERED' AND check_in_at IS NULL AND check_out_at IS NULL)
        OR (visit_status='CHECKED_IN' AND check_in_at IS NOT NULL AND check_out_at IS NULL)
        OR (visit_status='CHECKED_OUT' AND check_in_at IS NOT NULL AND check_out_at IS NOT NULL AND check_out_at>=check_in_at)
        OR (visit_status='CANCELLED' AND check_out_at IS NULL)
    )
);
CREATE INDEX idx_visitor_record_query
    ON visitor_record (community_id, visit_status, scheduled_at);

INSERT INTO dashboard_widget_configuration
    (id, community_id, role_code, widget_code, widget_name, metric_code, position_code,
     visible, refresh_interval_seconds, display_order, status, published_at, version, created_at, updated_at)
VALUES
    ('98000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001', 'ALL', 'ASSET_SUMMARY', '资产概览', 'ASSET_COUNTS', 'SUMMARY', TRUE, 300, 1, 'PUBLISHED', '2026-08-25 00:00:00.000', 0, '2026-08-25 00:00:00.000', '2026-08-25 00:00:00.000'),
    ('98000000-0000-0000-0000-000000000002', '30000000-0000-0000-0000-000000000001', 'ALL', 'FINANCE_OVERVIEW', '收费概览', 'COLLECTION_RATE', 'MAIN', TRUE, 300, 2, 'PUBLISHED', '2026-08-25 00:00:00.000', 0, '2026-08-25 00:00:00.000', '2026-08-25 00:00:00.000'),
    ('98000000-0000-0000-0000-000000000003', '30000000-0000-0000-0000-000000000001', 'ALL', 'DATA_QUALITY', '数据质量', 'DATA_QUALITY', 'SIDE', TRUE, 600, 3, 'PUBLISHED', '2026-08-25 00:00:00.000', 0, '2026-08-25 00:00:00.000', '2026-08-25 00:00:00.000'),
    ('98000000-0000-0000-0000-000000000004', '30000000-0000-0000-0000-000000000001', 'ALL', 'INTEGRATION_STATUS', '集成状态', 'INTEGRATION_STATUS', 'SIDE', FALSE, 600, 4, 'PUBLISHED', '2026-08-25 00:00:00.000', 0, '2026-08-25 00:00:00.000', '2026-08-25 00:00:00.000');

INSERT INTO visitor_record
    (id, community_id, visit_no, visitor_name_masked, visitor_mobile_masked, host_name_masked,
     asset_name, scheduled_at, check_in_at, check_out_at, visit_status, source_mode,
     production_connected, adapter_evidence, version, created_at, updated_at)
VALUES
    ('99000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001', 'SYN-VISIT-0001', '合成访客甲**', '138****0001', '合成住户甲**', '1号楼-1单元-0101', '2026-08-25 08:30:00.000', '2026-08-25 08:35:00.000', '2026-08-25 09:15:00.000', 'CHECKED_OUT', 'IOT_SIMULATOR', FALSE, 'seed:simulated-only', 0, '2026-08-25 08:00:00.000', '2026-08-25 09:15:00.000'),
    ('99000000-0000-0000-0000-000000000002', '30000000-0000-0000-0000-000000000001', 'SYN-VISIT-0002', '合成访客乙**', '139****0002', '合成住户乙**', '2号楼-2单元-0202', '2026-08-25 10:00:00.000', '2026-08-25 10:03:00.000', NULL, 'CHECKED_IN', 'IOT_SIMULATOR', FALSE, 'seed:simulated-only', 0, '2026-08-25 09:30:00.000', '2026-08-25 10:03:00.000'),
    ('99000000-0000-0000-0000-000000000003', '30000000-0000-0000-0000-000000000001', 'SYN-VISIT-0003', '合成访客丙**', '137****0003', '合成住户丙**', '3号楼-1单元-0301', '2026-08-25 14:00:00.000', NULL, NULL, 'REGISTERED', 'IOT_SIMULATOR', FALSE, 'seed:simulated-only', 0, '2026-08-25 12:00:00.000', '2026-08-25 12:00:00.000');
