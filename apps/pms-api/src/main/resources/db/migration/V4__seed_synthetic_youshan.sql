-- Deterministic synthetic fixture. It preserves only audited object counts and relationships.
-- It contains no target-system identifiers, names, phone numbers, room numbers or financial history.

CREATE TEMPORARY TABLE seed_digit (n INT PRIMARY KEY);
INSERT INTO seed_digit (n) VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9);
CREATE TEMPORARY TABLE seed_digit_tens (n INT PRIMARY KEY);
INSERT INTO seed_digit_tens (n) VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9);
CREATE TEMPORARY TABLE seed_digit_hundreds (n INT PRIMARY KEY);
INSERT INTO seed_digit_hundreds (n) VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9);

CREATE TEMPORARY TABLE seed_sequence (n INT PRIMARY KEY);
INSERT INTO seed_sequence (n)
SELECT ones.n + tens.n * 10 + hundreds.n * 100 + 1
FROM seed_digit ones
CROSS JOIN seed_digit_tens tens
CROSS JOIN seed_digit_hundreds hundreds;

INSERT INTO community
    (id, source_system, source_id, name, managed_area, address, province_code, city_code,
     district_code, longitude, latitude, service_phone, contact_name, status, version, created_at, updated_at)
VALUES
    ('30000000-0000-0000-0000-000000000001', 'SYNTHETIC', 'SYN-COMMUNITY-001', '优山美地（合成示范项目）',
     51860.00, '合成示范路 1 号（非真实地址）', 'SYN-P', 'SYN-C', 'SYN-D', NULL, NULL,
     '仅用于本地演示', '合成项目服务中心', 'ACTIVE', 0, '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000');

INSERT INTO building
    (id, community_id, source_id, code, name, building_type, floor_count, status, version, created_at, updated_at)
SELECT CONCAT('31000000-0000-0000-0000-', LPAD(n, 12, '0')),
       '30000000-0000-0000-0000-000000000001', CONCAT('SYN-BUILDING-', LPAD(n, 3, '0')),
       CONCAT('B', LPAD(n, 2, '0')), CONCAT('合成楼栋 ', LPAD(n, 2, '0')), 'RESIDENTIAL',
       6 + MOD(n, 6), 'ACTIVE', 0, '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000'
FROM seed_sequence WHERE n <= 8;

INSERT INTO pms_unit
    (id, building_id, code, name, status, version, created_at, updated_at)
SELECT CONCAT('32000000-0000-0000-0000-', LPAD(n, 12, '0')),
       CONCAT('31000000-0000-0000-0000-', LPAD(CEIL(n / 2), 12, '0')),
       CONCAT('U', MOD(n - 1, 2) + 1), CONCAT('合成单元 ', MOD(n - 1, 2) + 1),
       'ACTIVE', 0, '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000'
FROM seed_sequence WHERE n <= 4;

INSERT INTO asset
    (id, community_id, building_id, unit_id, source_id, asset_type, code, display_name, floor_no,
     building_area, usable_area, occupancy_status, operation_status, enabled, valid_from, valid_to,
     version, created_at, updated_at)
SELECT CONCAT('40000000-0000-0000-0000-', LPAD(n, 12, '0')),
       '30000000-0000-0000-0000-000000000001',
       CONCAT('31000000-0000-0000-0000-', LPAD(MOD(n - 1, 8) + 1, 12, '0')),
       CASE WHEN MOD(n - 1, 8) < 2
            THEN CONCAT('32000000-0000-0000-0000-', LPAD(MOD(n - 1, 4) + 1, 12, '0'))
            ELSE NULL END,
       CONCAT('SYN-ROOM-', LPAD(n, 4, '0')), 'ROOM', CONCAT('R', LPAD(n, 4, '0')),
       CONCAT('合成房屋 ', LPAD(n, 4, '0')), CAST(MOD(n - 1, 12) + 1 AS CHAR),
       CAST(68 + MOD(n * 17, 93) + MOD(n, 10) / 10 AS DECIMAL(18,2)),
       CAST(52 + MOD(n * 13, 76) + MOD(n, 10) / 10 AS DECIMAL(18,2)),
       CASE WHEN MOD(n, 11) = 0 THEN 'VACANT' ELSE 'OCCUPIED' END,
       'NORMAL', TRUE, '2026-01-01', NULL, 0, '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000'
FROM seed_sequence WHERE n <= 359;

INSERT INTO room_detail (asset_id, room_type, delivery_date)
SELECT CONCAT('40000000-0000-0000-0000-', LPAD(n, 12, '0')),
       CASE WHEN MOD(n, 7) = 0 THEN 'COMMERCIAL' ELSE 'RESIDENTIAL' END, '2025-12-01'
FROM seed_sequence WHERE n <= 359;

INSERT INTO asset
    (id, community_id, building_id, unit_id, source_id, asset_type, code, display_name, floor_no,
     building_area, usable_area, occupancy_status, operation_status, enabled, valid_from, valid_to,
     version, created_at, updated_at)
SELECT CONCAT('41000000-0000-0000-0000-', LPAD(n, 12, '0')),
       '30000000-0000-0000-0000-000000000001', NULL, NULL,
       CONCAT('SYN-PARKING-', LPAD(n, 4, '0')), 'PARKING', CONCAT('P', LPAD(n, 4, '0')),
       CONCAT('合成车位 ', LPAD(n, 4, '0')), 'B1', 12.50, 12.50,
       CASE WHEN n <= 194 THEN 'OCCUPIED' ELSE 'VACANT' END,
       'NORMAL', TRUE, '2026-01-01', NULL, 0, '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000'
FROM seed_sequence WHERE n <= 250;

INSERT INTO parking_space_detail (asset_id, parking_type, ownership_type, related_room_asset_id)
SELECT CONCAT('41000000-0000-0000-0000-', LPAD(n, 12, '0')),
       CASE WHEN MOD(n, 5) = 0 THEN 'MECHANICAL' ELSE 'STANDARD' END,
       CASE WHEN MOD(n, 3) = 0 THEN 'LEASE' ELSE 'OWNED' END,
       CASE WHEN n <= 194 THEN CONCAT('40000000-0000-0000-0000-', LPAD(MOD(n - 1, 359) + 1, 12, '0')) ELSE NULL END
FROM seed_sequence WHERE n <= 250;

INSERT INTO customer
    (id, community_id, source_id, display_name, customer_type, customer_class, mobile_masked,
     mobile_ciphertext, mobile_search_hash, certificate_type, certificate_masked, certificate_ciphertext,
     gender, status, version, created_at, updated_at)
SELECT CONCAT('50000000-0000-0000-0000-', LPAD(n, 12, '0')),
       '30000000-0000-0000-0000-000000000001', CONCAT('SYN-CUSTOMER-', LPAD(n, 4, '0')),
       CONCAT('合成客户 ', LPAD(n, 4, '0')), CASE WHEN MOD(n, 29) = 0 THEN 'ORGANIZATION' ELSE 'PERSON' END,
       CASE WHEN MOD(n, 10) = 0 THEN 'TENANT' ELSE 'OWNER' END,
       CONCAT('SYN-***-', LPAD(n, 4, '0')), NULL, SHA2(CONCAT('synthetic-mobile-', n), 256),
       NULL, NULL, NULL, CASE WHEN MOD(n, 2) = 0 THEN 'FEMALE' ELSE 'MALE' END,
       'ACTIVE', 0, '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000'
FROM seed_sequence WHERE n <= 403;

INSERT INTO customer_asset_relation
    (id, customer_id, asset_id, relation_type, primary_relation, start_date, end_date, status,
     version, created_at, updated_at)
SELECT CONCAT('51000000-0000-0000-0000-', LPAD(n, 12, '0')),
       CONCAT('50000000-0000-0000-0000-', LPAD(n, 12, '0')),
       CONCAT('40000000-0000-0000-0000-', LPAD(MOD(n - 1, 359) + 1, 12, '0')),
       CASE WHEN n > 359 THEN 'CO_OWNER' WHEN MOD(n, 10) = 0 THEN 'TENANT' ELSE 'OWNER' END,
       n <= 359, '2026-01-01', NULL, 'ACTIVE', 0,
       '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000'
FROM seed_sequence WHERE n <= 403;

INSERT INTO vehicle
    (id, community_id, plate_no_masked, plate_no_search_hash, vehicle_type, color, status, version, created_at, updated_at)
SELECT CONCAT('52000000-0000-0000-0000-', LPAD(n, 12, '0')),
       '30000000-0000-0000-0000-000000000001', CONCAT('合成车牌*', LPAD(n, 3, '0')),
       SHA2(CONCAT('synthetic-plate-', n), 256), CASE WHEN MOD(n, 5) = 0 THEN 'NEW_ENERGY' ELSE 'CAR' END,
       CASE MOD(n, 4) WHEN 0 THEN 'WHITE' WHEN 1 THEN 'BLACK' WHEN 2 THEN 'BLUE' ELSE 'GRAY' END,
       'ACTIVE', 0, '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000'
FROM seed_sequence WHERE n <= 30;

INSERT INTO vehicle_parking_relation
    (id, vehicle_id, parking_asset_id, customer_id, start_date, end_date, status, created_at, updated_at)
SELECT CONCAT('53000000-0000-0000-0000-', LPAD(n, 12, '0')),
       CONCAT('52000000-0000-0000-0000-', LPAD(n, 12, '0')),
       CONCAT('41000000-0000-0000-0000-', LPAD(n, 12, '0')),
       CONCAT('50000000-0000-0000-0000-', LPAD(n, 12, '0')),
       '2026-01-01', NULL, 'ACTIVE', '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000'
FROM seed_sequence WHERE n <= 30;

INSERT INTO meter
    (id, community_id, asset_id, parent_meter_id, meter_no, meter_type, meter_class, status,
     range_value, multiplier, loss_rate, correction, installed_at, version, created_at, updated_at)
VALUES
    ('60000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001', NULL, NULL,
     'SYN-MASTER-001', 'WATER', 'MASTER', 'ACTIVE', 999999.0000, 1.000000, 0.000000, 0.0000,
     '2026-01-01 00:00:00.000', 0, '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000');

INSERT INTO meter
    (id, community_id, asset_id, parent_meter_id, meter_no, meter_type, meter_class, status,
     range_value, multiplier, loss_rate, correction, installed_at, version, created_at, updated_at)
SELECT CONCAT('61000000-0000-0000-0000-', LPAD(n, 12, '0')),
       '30000000-0000-0000-0000-000000000001',
       CONCAT('40000000-0000-0000-0000-', LPAD(n, 12, '0')),
       '60000000-0000-0000-0000-000000000001', CONCAT('SYN-SUB-', LPAD(n, 3, '0')),
       CASE MOD(n, 3) WHEN 0 THEN 'ELECTRICITY' WHEN 1 THEN 'WATER' ELSE 'GAS' END,
       'SUB', 'ACTIVE', 99999.0000, CASE WHEN MOD(n, 7) = 0 THEN 10.000000 ELSE 1.000000 END,
       0.000000, 0.0000, '2026-01-01 00:00:00.000', 0,
       '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000'
FROM seed_sequence WHERE n <= 30;

INSERT INTO fee_definition
    (id, community_id, source_id, code, name, fee_type, fee_class, unit_code, decimal_scale,
     late_fee_enabled, enabled, accounting_subject_code, prepayment_subject_code, version, created_at, updated_at)
SELECT CONCAT('70000000-0000-0000-0000-', LPAD(n, 12, '0')),
       '30000000-0000-0000-0000-000000000001', CONCAT('SYN-FEE-', LPAD(n, 3, '0')),
       CONCAT('FEE-', LPAD(n, 3, '0')), CONCAT('合成费用 ', LPAD(n, 3, '0')),
       CASE WHEN n <= 12 THEN 'PROPERTY' WHEN n <= 18 THEN 'PARKING' ELSE 'METER' END,
       CASE WHEN n <= 18 THEN 'PERIODIC' ELSE 'USAGE' END,
       CASE WHEN n <= 12 THEN 'M2_MONTH' WHEN n <= 18 THEN 'SPACE_MONTH' ELSE 'UNIT' END,
       2, MOD(n, 6) = 0, TRUE, CONCAT('SYN-ACCT-', LPAD(n, 3, '0')), NULL, 0,
       '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000'
FROM seed_sequence WHERE n <= 22;

INSERT INTO fee_standard
    (id, fee_definition_id, code, name, asset_type, billing_cycle, status, version, created_at, updated_at)
SELECT CONCAT('71000000-0000-0000-0000-', LPAD(n, 12, '0')),
       CONCAT('70000000-0000-0000-0000-', LPAD(n, 12, '0')),
       CONCAT('STD-', LPAD(n, 3, '0')), CONCAT('合成计费标准 ', LPAD(n, 3, '0')),
       CASE WHEN n <= 12 THEN 'ROOM' ELSE 'PARKING' END, 'MONTHLY', 'ACTIVE', 0,
       '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000'
FROM seed_sequence WHERE n <= 16;

INSERT INTO fee_standard_version
    (id, fee_standard_id, version_no, unit_price, formula_code, formula_expression,
     effective_from, effective_to, status, created_at)
SELECT CONCAT('72000000-0000-0000-0000-', LPAD(n, 12, '0')),
       CONCAT('71000000-0000-0000-0000-', LPAD(n, 12, '0')), 1,
       CAST(0.80 + n * 0.15 AS DECIMAL(18,6)),
       CASE WHEN n <= 12 THEN 'AREA_PRICE' ELSE 'FIXED_PRICE' END,
       CASE WHEN n <= 12 THEN 'building_area * unit_price * coefficient' ELSE 'unit_price * coefficient' END,
       '2026-01-01', NULL, 'ACTIVE', '2026-01-01 00:00:00.000'
FROM seed_sequence WHERE n <= 16;

INSERT INTO fee_allocation
    (id, fee_standard_id, asset_id, coefficient, effective_from, effective_to, status, version, created_at, updated_at)
SELECT CONCAT('73000001-0000-0000-0000-', LPAD(n, 12, '0')),
       '71000000-0000-0000-0000-000000000001',
       CONCAT('40000000-0000-0000-0000-', LPAD(n, 12, '0')), 1.000000,
       '2026-01-01', NULL, 'ACTIVE', 0, '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000'
FROM seed_sequence WHERE n <= 359;

INSERT INTO fee_allocation
    (id, fee_standard_id, asset_id, coefficient, effective_from, effective_to, status, version, created_at, updated_at)
SELECT CONCAT('73000002-0000-0000-0000-', LPAD(n, 12, '0')),
       '71000000-0000-0000-0000-000000000002',
       CONCAT('40000000-0000-0000-0000-', LPAD(n, 12, '0')),
       CASE WHEN MOD(n, 9) = 0 THEN 0.850000 ELSE 1.000000 END,
       '2026-01-01', NULL, 'ACTIVE', 0, '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000'
FROM seed_sequence WHERE n <= 190;

INSERT INTO fee_allocation
    (id, fee_standard_id, asset_id, coefficient, effective_from, effective_to, status, version, created_at, updated_at)
SELECT CONCAT('73000003-0000-0000-0000-', LPAD(n, 12, '0')),
       '71000000-0000-0000-0000-000000000013',
       CONCAT('41000000-0000-0000-0000-', LPAD(n, 12, '0')), 1.000000,
       '2026-01-01', NULL, 'ACTIVE', 0, '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000'
FROM seed_sequence WHERE n <= 194;

INSERT INTO bill
    (id, community_id, asset_id, customer_id, bill_no, billing_period, status, total_amount,
     paid_amount, outstanding_amount, due_date, version, created_at, updated_at)
SELECT CONCAT('80000000-0000-0000-0000-', LPAD(n, 12, '0')),
       '30000000-0000-0000-0000-000000000001',
       CONCAT('40000000-0000-0000-0000-', LPAD(n, 12, '0')),
       CONCAT('50000000-0000-0000-0000-', LPAD(n, 12, '0')),
       CONCAT('SYN-BILL-202607-', LPAD(n, 4, '0')), '2026-07',
       CASE WHEN MOD(n, 5) = 0 THEN 'PARTIAL' ELSE 'UNPAID' END,
       CAST(80 + n * 3.25 AS DECIMAL(18,2)),
       CASE WHEN MOD(n, 5) = 0 THEN CAST((80 + n * 3.25) / 2 AS DECIMAL(18,2)) ELSE 0.00 END,
       CASE WHEN MOD(n, 5) = 0 THEN CAST((80 + n * 3.25) / 2 AS DECIMAL(18,2))
            ELSE CAST(80 + n * 3.25 AS DECIMAL(18,2)) END,
       '2026-07-31', 0, '2026-07-01 00:00:00.000', '2026-07-01 00:00:00.000'
FROM seed_sequence WHERE n <= 20;

INSERT INTO bill_item
    (id, bill_id, fee_definition_id, fee_standard_version_id, item_name_snapshot, quantity,
     unit_price, coefficient, amount, calculation_snapshot, created_at)
SELECT CONCAT('81000000-0000-0000-0000-', LPAD(n, 12, '0')),
       CONCAT('80000000-0000-0000-0000-', LPAD(n, 12, '0')),
       '70000000-0000-0000-0000-000000000001',
       '72000000-0000-0000-0000-000000000001', '合成物业费', 1.000000,
       CAST(80 + n * 3.25 AS DECIMAL(18,6)), 1.000000,
       CAST(80 + n * 3.25 AS DECIMAL(18,2)),
       JSON_OBJECT('source', 'synthetic-seed', 'assumption', '演示账单，不代表目标系统历史口径'),
       '2026-07-01 00:00:00.000'
FROM seed_sequence WHERE n <= 20;

INSERT INTO meter_share_rule
    (id, community_id, code, name, strategy_code, config_json, status, version, created_at, updated_at)
VALUES
    ('62000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001',
     'SYN-SHARE-AREA', '合成按面积公摊规则', 'AREA_RATIO',
     '{"assumption":"演示策略，待真实业务口径确认","roundingScale":4}',
     'ACTIVE', 0, '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000');

DROP TEMPORARY TABLE seed_sequence;
DROP TEMPORARY TABLE seed_digit_hundreds;
DROP TEMPORARY TABLE seed_digit_tens;
DROP TEMPORARY TABLE seed_digit;
