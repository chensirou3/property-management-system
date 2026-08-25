-- G5 local demonstration fixture: an explicitly synthetic definition that makes
-- the temporary-receivable workflow usable without altering any reference data.
INSERT INTO fee_definition
    (id, community_id, source_id, code, name, fee_type, fee_class, unit_code,
     decimal_scale, rounding_mode, currency_code, late_fee_enabled, temporary_allowed,
     enabled, accounting_subject_code, prepayment_subject_code, tax_category_code,
     tax_rate, version, created_at, updated_at)
VALUES
    ('70000000-0000-0000-0000-000000000023',
     '30000000-0000-0000-0000-000000000001',
     'SYN-FEE-TEMP-001', 'FEE-TEMP-001', '临时服务费（合成示范）',
     'TEMPORARY', 'TEMPORARY', 'ITEM', 2, 'HALF_UP', 'CNY', FALSE, TRUE,
     TRUE, 'SYN-ACCT-TEMP', NULL, 'SYN-TAX-TEMP', 0.000000, 0,
     '2026-08-25 00:00:00.000', '2026-08-25 00:00:00.000');
