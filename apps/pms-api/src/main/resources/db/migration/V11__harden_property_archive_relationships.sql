-- G3 property/archive invariants. Existing target-derived statistics remain aggregate-only;
-- all new rows below are deterministic synthetic fixtures for project-isolation acceptance.

ALTER TABLE grid_area
    ADD COLUMN parent_id VARCHAR(36) NULL AFTER community_id,
    ADD COLUMN sort_order INT NOT NULL DEFAULT 0 AFTER manager_user_id,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER status,
    ADD CONSTRAINT fk_grid_parent FOREIGN KEY (parent_id) REFERENCES grid_area(id);

CREATE INDEX idx_grid_tree ON grid_area (community_id, parent_id, sort_order, name);

ALTER TABLE building ADD COLUMN grid_id VARCHAR(36) NULL AFTER community_id;
ALTER TABLE asset ADD COLUMN grid_id VARCHAR(36) NULL AFTER community_id;

ALTER TABLE pms_unit ADD COLUMN community_id VARCHAR(36) NULL AFTER id;
UPDATE pms_unit u JOIN building b ON b.id = u.building_id SET u.community_id = b.community_id;
ALTER TABLE pms_unit MODIFY community_id VARCHAR(36) NOT NULL;

-- V4 intentionally exercised sparse unit coverage but its first rows carried an inconsistent
-- building/unit pair. The unit is authoritative for hierarchy, so canonicalize before adding FKs.
UPDATE asset a
JOIN pms_unit u ON u.id = a.unit_id
SET a.building_id = u.building_id
WHERE a.unit_id IS NOT NULL AND a.building_id <> u.building_id;

-- V4 generated a handful of synthetic rooms whose calculated usable area exceeded
-- building area. Preserve the audited building-area measure and clamp only the
-- invalid derived usable-area value before enforcing the permanent invariant.
UPDATE asset
SET usable_area = building_area
WHERE usable_area > building_area;

UPDATE asset a JOIN building b ON b.id = a.building_id SET a.grid_id = b.grid_id WHERE a.grid_id IS NULL;

ALTER TABLE customer
    ADD COLUMN customer_no VARCHAR(80) NULL AFTER source_id,
    ADD COLUMN birthday DATE NULL AFTER gender,
    ADD COLUMN remarks VARCHAR(500) NULL AFTER birthday;
UPDATE customer
SET customer_no = COALESCE(NULLIF(source_id, ''), CONCAT('SYN-', REPLACE(id, '-', '')))
WHERE customer_no IS NULL;
ALTER TABLE customer MODIFY customer_no VARCHAR(80) NOT NULL;

ALTER TABLE customer_asset_relation
    ADD COLUMN community_id VARCHAR(36) NULL AFTER id,
    ADD COLUMN change_reason VARCHAR(500) NULL AFTER end_date;
UPDATE customer_asset_relation r
JOIN customer c ON c.id = r.customer_id
SET r.community_id = c.community_id;
ALTER TABLE customer_asset_relation MODIFY community_id VARCHAR(36) NOT NULL;

ALTER TABLE vehicle_parking_relation
    ADD COLUMN community_id VARCHAR(36) NULL AFTER id,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER status,
    ADD COLUMN change_reason VARCHAR(500) NULL AFTER end_date;
UPDATE vehicle_parking_relation r
JOIN vehicle v ON v.id = r.vehicle_id
SET r.community_id = v.community_id;
ALTER TABLE vehicle_parking_relation MODIFY community_id VARCHAR(36) NOT NULL;

ALTER TABLE grid_area
    ADD CONSTRAINT uk_grid_id_community UNIQUE (id, community_id),
    ADD CONSTRAINT ck_grid_status CHECK (status IN ('ACTIVE', 'INACTIVE'));
ALTER TABLE building
    ADD CONSTRAINT uk_building_id_community UNIQUE (id, community_id),
    ADD CONSTRAINT fk_building_grid_scope FOREIGN KEY (grid_id, community_id) REFERENCES grid_area(id, community_id),
    ADD CONSTRAINT ck_building_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    ADD CONSTRAINT ck_building_floor_count CHECK (floor_count IS NULL OR floor_count >= 0);
ALTER TABLE pms_unit
    ADD CONSTRAINT uk_unit_id_community UNIQUE (id, community_id),
    ADD CONSTRAINT uk_unit_hierarchy UNIQUE (id, building_id, community_id),
    ADD CONSTRAINT fk_unit_community FOREIGN KEY (community_id) REFERENCES community(id),
    ADD CONSTRAINT fk_unit_building_scope FOREIGN KEY (building_id, community_id) REFERENCES building(id, community_id),
    ADD CONSTRAINT ck_unit_status CHECK (status IN ('ACTIVE', 'INACTIVE'));
ALTER TABLE asset
    ADD CONSTRAINT uk_asset_id_community UNIQUE (id, community_id),
    ADD CONSTRAINT fk_asset_grid_scope FOREIGN KEY (grid_id, community_id) REFERENCES grid_area(id, community_id),
    ADD CONSTRAINT fk_asset_building_scope FOREIGN KEY (building_id, community_id) REFERENCES building(id, community_id),
    ADD CONSTRAINT fk_asset_unit_scope FOREIGN KEY (unit_id, building_id, community_id) REFERENCES pms_unit(id, building_id, community_id),
    ADD CONSTRAINT ck_asset_type CHECK (asset_type IN ('ROOM', 'PARKING', 'PUBLIC_AREA')),
    ADD CONSTRAINT ck_asset_area CHECK (building_area >= 0 AND usable_area >= 0 AND usable_area <= building_area),
    ADD CONSTRAINT ck_asset_validity CHECK (valid_to IS NULL OR valid_from IS NULL OR valid_to >= valid_from),
    ADD CONSTRAINT ck_asset_hierarchy CHECK (unit_id IS NULL OR building_id IS NOT NULL);
ALTER TABLE customer
    ADD CONSTRAINT uk_customer_no UNIQUE (community_id, customer_no),
    ADD CONSTRAINT uk_customer_id_community UNIQUE (id, community_id),
    ADD CONSTRAINT ck_customer_status CHECK (status IN ('ACTIVE', 'INACTIVE'));
ALTER TABLE vehicle
    ADD CONSTRAINT uk_vehicle_id_community UNIQUE (id, community_id),
    ADD CONSTRAINT ck_vehicle_status CHECK (status IN ('ACTIVE', 'INACTIVE'));
ALTER TABLE meter
    ADD CONSTRAINT uk_meter_id_community UNIQUE (id, community_id);
ALTER TABLE meter
    ADD CONSTRAINT fk_meter_asset_scope FOREIGN KEY (asset_id, community_id) REFERENCES asset(id, community_id),
    ADD CONSTRAINT fk_meter_parent_scope FOREIGN KEY (parent_meter_id, community_id) REFERENCES meter(id, community_id),
    ADD CONSTRAINT ck_meter_values CHECK (multiplier > 0 AND loss_rate >= 0 AND correction >= 0),
    ADD CONSTRAINT ck_meter_parent CHECK (parent_meter_id IS NULL OR parent_meter_id <> id);

ALTER TABLE customer_asset_relation
    ADD COLUMN active_relation_key VARCHAR(160)
        GENERATED ALWAYS AS (
            CASE WHEN status = 'ACTIVE' AND end_date IS NULL
                 THEN CONCAT(customer_id, '|', asset_id, '|', relation_type)
                 ELSE NULL END
        ) STORED,
    ADD CONSTRAINT uk_relation_active UNIQUE (active_relation_key),
    ADD CONSTRAINT fk_relation_customer_scope FOREIGN KEY (customer_id, community_id) REFERENCES customer(id, community_id),
    ADD CONSTRAINT fk_relation_asset_scope FOREIGN KEY (asset_id, community_id) REFERENCES asset(id, community_id),
    ADD CONSTRAINT ck_relation_type CHECK (relation_type IN ('OWNER', 'CO_OWNER', 'TENANT', 'OCCUPANT')),
    ADD CONSTRAINT ck_relation_status CHECK (status IN ('ACTIVE', 'ENDED', 'INACTIVE')),
    ADD CONSTRAINT ck_relation_dates CHECK (end_date IS NULL OR end_date >= start_date);
CREATE INDEX idx_relation_timeline ON customer_asset_relation (community_id, customer_id, start_date, created_at);
CREATE INDEX idx_relation_asset_active ON customer_asset_relation (community_id, asset_id, status, relation_type);

ALTER TABLE vehicle_parking_relation
    ADD COLUMN active_relation_key VARCHAR(120)
        GENERATED ALWAYS AS (
            CASE WHEN status = 'ACTIVE' AND end_date IS NULL
                 THEN CONCAT(vehicle_id, '|', parking_asset_id)
                 ELSE NULL END
        ) STORED,
    ADD CONSTRAINT uk_vehicle_parking_active UNIQUE (active_relation_key),
    ADD CONSTRAINT fk_vpr_vehicle_scope FOREIGN KEY (vehicle_id, community_id) REFERENCES vehicle(id, community_id),
    ADD CONSTRAINT fk_vpr_parking_scope FOREIGN KEY (parking_asset_id, community_id) REFERENCES asset(id, community_id),
    ADD CONSTRAINT fk_vpr_customer_scope FOREIGN KEY (customer_id, community_id) REFERENCES customer(id, community_id),
    ADD CONSTRAINT ck_vpr_status CHECK (status IN ('ACTIVE', 'ENDED', 'INACTIVE')),
    ADD CONSTRAINT ck_vpr_dates CHECK (end_date IS NULL OR end_date >= start_date);
CREATE INDEX idx_vpr_timeline ON vehicle_parking_relation (community_id, parking_asset_id, start_date, created_at);

CREATE TABLE property_relation_event (
    id VARCHAR(36) PRIMARY KEY,
    community_id VARCHAR(36) NOT NULL,
    asset_id VARCHAR(36) NOT NULL,
    customer_id VARCHAR(36),
    event_type VARCHAR(40) NOT NULL,
    relation_type VARCHAR(30),
    effective_date DATE NOT NULL,
    previous_relation_id VARCHAR(36),
    new_relation_id VARCHAR(36),
    reason VARCHAR(500),
    snapshot_json TEXT NOT NULL,
    request_key VARCHAR(120) NOT NULL,
    actor_user_id VARCHAR(36),
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_property_event_request UNIQUE (community_id, request_key),
    CONSTRAINT fk_property_event_community FOREIGN KEY (community_id) REFERENCES community(id),
    CONSTRAINT fk_property_event_asset FOREIGN KEY (asset_id, community_id) REFERENCES asset(id, community_id),
    CONSTRAINT fk_property_event_customer FOREIGN KEY (customer_id, community_id) REFERENCES customer(id, community_id),
    CONSTRAINT fk_property_event_previous FOREIGN KEY (previous_relation_id) REFERENCES customer_asset_relation(id),
    CONSTRAINT fk_property_event_new FOREIGN KEY (new_relation_id) REFERENCES customer_asset_relation(id),
    CONSTRAINT fk_property_event_actor FOREIGN KEY (actor_user_id) REFERENCES sys_user(id),
    CONSTRAINT ck_property_event_type CHECK (event_type IN ('RELATION_STARTED', 'RELATION_ENDED', 'OWNERSHIP_TRANSFERRED'))
);
CREATE INDEX idx_property_event_timeline ON property_relation_event (community_id, asset_id, effective_date, created_at);

-- Minimal isolated-project archive chain used only for deterministic cross-project tests.
INSERT INTO grid_area
    (id, community_id, parent_id, code, name, manager_user_id, sort_order, status, version, created_at, updated_at)
VALUES
    ('35800000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000002', NULL,
     'ISO-GRID-01', '隔离项目合成网格', NULL, 10, 'ACTIVE', 0,
     '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000');

INSERT INTO building
    (id, community_id, grid_id, source_id, code, name, building_type, floor_count, status, version, created_at, updated_at)
VALUES
    ('35000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000002',
     '35800000-0000-0000-0000-000000000001', 'SYN-ISO-BUILDING-001', 'ISO-B01', '隔离项目合成楼栋',
     'RESIDENTIAL', 2, 'ACTIVE', 0, '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000');

INSERT INTO pms_unit
    (id, community_id, building_id, code, name, status, version, created_at, updated_at)
VALUES
    ('35100000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000002',
     '35000000-0000-0000-0000-000000000001', 'ISO-U01', '隔离项目合成单元', 'ACTIVE', 0,
     '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000');

INSERT INTO asset
    (id, community_id, grid_id, building_id, unit_id, source_id, asset_type, code, display_name, floor_no,
     building_area, usable_area, occupancy_status, operation_status, enabled, valid_from, valid_to,
     version, created_at, updated_at)
VALUES
    ('35200000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000002',
     '35800000-0000-0000-0000-000000000001', '35000000-0000-0000-0000-000000000001',
     '35100000-0000-0000-0000-000000000001', 'SYN-ISO-ROOM-001', 'ROOM', 'ISO-R001',
     '隔离项目合成房屋 001', '1', 88.00, 70.00, 'OCCUPIED', 'NORMAL', TRUE, '2026-01-01', NULL, 0,
     '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000'),
    ('35200000-0000-0000-0000-000000000002', '30000000-0000-0000-0000-000000000002',
     '35800000-0000-0000-0000-000000000001', NULL, NULL, 'SYN-ISO-PARKING-001', 'PARKING', 'ISO-P001',
     '隔离项目合成车位 001', 'B1', 12.50, 12.50, 'OCCUPIED', 'NORMAL', TRUE, '2026-01-01', NULL, 0,
     '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000');

INSERT INTO room_detail (asset_id, room_type, delivery_date)
VALUES ('35200000-0000-0000-0000-000000000001', 'RESIDENTIAL', '2025-12-01');
INSERT INTO parking_space_detail (asset_id, parking_type, ownership_type, related_room_asset_id)
VALUES ('35200000-0000-0000-0000-000000000002', 'STANDARD', 'OWNED',
        '35200000-0000-0000-0000-000000000001');

INSERT INTO customer
    (id, community_id, source_id, customer_no, display_name, customer_type, customer_class, mobile_masked,
     mobile_ciphertext, mobile_search_hash, certificate_type, certificate_masked, certificate_ciphertext,
     gender, birthday, remarks, status, version, created_at, updated_at)
VALUES
    ('35300000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000002',
     'SYN-ISO-CUSTOMER-001', 'ISO-C001', '隔离项目合成客户 001', 'PERSON', 'OWNER', 'SYN-***-ISO1',
     NULL, SHA2('synthetic-isolated-mobile-1', 256), NULL, NULL, NULL, 'UNKNOWN', NULL, '隔离边界样本',
     'ACTIVE', 0, '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000');

INSERT INTO customer_asset_relation
    (id, community_id, customer_id, asset_id, relation_type, primary_relation, start_date, end_date,
     change_reason, status, version, created_at, updated_at)
VALUES
    ('35400000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000002',
     '35300000-0000-0000-0000-000000000001', '35200000-0000-0000-0000-000000000001',
     'OWNER', TRUE, '2026-01-01', NULL, '隔离项目合成初始关系', 'ACTIVE', 0,
     '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000');

INSERT INTO vehicle
    (id, community_id, plate_no_masked, plate_no_search_hash, vehicle_type, color, status, version, created_at, updated_at)
VALUES
    ('35500000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000002',
     '合成隔离车牌*001', SHA2('synthetic-isolated-plate-1', 256), 'CAR', 'GRAY', 'ACTIVE', 0,
     '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000');

INSERT INTO vehicle_parking_relation
    (id, community_id, vehicle_id, parking_asset_id, customer_id, start_date, end_date,
     change_reason, status, version, created_at, updated_at)
VALUES
    ('35600000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000002',
     '35500000-0000-0000-0000-000000000001', '35200000-0000-0000-0000-000000000002',
     '35300000-0000-0000-0000-000000000001', '2026-01-01', NULL, '隔离项目合成初始关系',
     'ACTIVE', 0, '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000');

INSERT INTO meter
    (id, community_id, asset_id, parent_meter_id, meter_no, meter_type, meter_class, status,
     range_value, multiplier, loss_rate, correction, installed_at, version, created_at, updated_at)
VALUES
    ('35700000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000002',
     '35200000-0000-0000-0000-000000000001', NULL, 'SYN-ISO-METER-001', 'WATER', 'SUB', 'ACTIVE',
     99999.0000, 1.000000, 0.000000, 0.0000, '2026-01-01 00:00:00.000', 0,
     '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000');
