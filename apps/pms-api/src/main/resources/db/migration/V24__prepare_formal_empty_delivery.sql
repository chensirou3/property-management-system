-- Formal deliveries must start without any seeded project or business records.
-- Historical migrations remain immutable so existing installations and regression fixtures keep
-- their checksums. This cleanup runs only for a never-initialized database with no users, and only
-- when the deployment explicitly enables the formal empty baseline placeholder.

SET @pms_prepare_formal_empty = CASE
    WHEN LOWER('${formalEmptyBaseline}') = 'true'
     AND EXISTS (
         SELECT 1 FROM system_setup
          WHERE singleton_id = 1 AND initialized = FALSE
     )
     AND NOT EXISTS (SELECT 1 FROM sys_user)
     AND (SELECT COUNT(*) FROM enterprise) = 1
     AND EXISTS (
         SELECT 1 FROM enterprise
          WHERE id = '31000000-0000-0000-0000-000000000001'
            AND code = 'SYNTHETIC_PROPERTY'
     )
     AND (SELECT COUNT(*) FROM community) = 2
     AND NOT EXISTS (
         SELECT 1 FROM community
          WHERE id NOT IN (
              '30000000-0000-0000-0000-000000000001',
              '30000000-0000-0000-0000-000000000002'
          )
     )
     AND (SELECT COUNT(*) FROM asset) = 611
     AND (SELECT COUNT(*) FROM customer) = 404
     AND (SELECT COUNT(*) FROM fee_definition) = 23
     AND (SELECT COUNT(*) FROM fee_allocation) = 773
     AND (SELECT COUNT(*) FROM bill) = 20
     AND (SELECT COUNT(*) FROM visitor_record) = 3
    THEN 1 ELSE 0 END;

SET @pms_previous_foreign_key_checks = @@FOREIGN_KEY_CHECKS;
SET FOREIGN_KEY_CHECKS = 0;

DELETE FROM asset WHERE @pms_prepare_formal_empty = 1;
DELETE FROM audit_event WHERE @pms_prepare_formal_empty = 1;
DELETE FROM auth_login_guard WHERE @pms_prepare_formal_empty = 1;
DELETE FROM bill WHERE @pms_prepare_formal_empty = 1;
DELETE FROM bill_adjustment WHERE @pms_prepare_formal_empty = 1;
DELETE FROM bill_item WHERE @pms_prepare_formal_empty = 1;
DELETE FROM building WHERE @pms_prepare_formal_empty = 1;
DELETE FROM cashier_shift WHERE @pms_prepare_formal_empty = 1;
DELETE FROM community WHERE @pms_prepare_formal_empty = 1;
DELETE FROM customer WHERE @pms_prepare_formal_empty = 1;
DELETE FROM customer_asset_relation WHERE @pms_prepare_formal_empty = 1;
DELETE FROM daily_settlement WHERE @pms_prepare_formal_empty = 1;
DELETE FROM dashboard_widget_configuration WHERE @pms_prepare_formal_empty = 1;
DELETE FROM data_transfer_job WHERE @pms_prepare_formal_empty = 1;
DELETE FROM deposit_account WHERE @pms_prepare_formal_empty = 1;
DELETE FROM deposit_transaction WHERE @pms_prepare_formal_empty = 1;
DELETE FROM discount_policy WHERE @pms_prepare_formal_empty = 1;
DELETE FROM employee WHERE @pms_prepare_formal_empty = 1;
DELETE FROM enterprise WHERE @pms_prepare_formal_empty = 1;
DELETE FROM fee_allocation WHERE @pms_prepare_formal_empty = 1;
DELETE FROM fee_configuration_event WHERE @pms_prepare_formal_empty = 1;
DELETE FROM fee_definition WHERE @pms_prepare_formal_empty = 1;
DELETE FROM fee_standard WHERE @pms_prepare_formal_empty = 1;
DELETE FROM fee_standard_version WHERE @pms_prepare_formal_empty = 1;
DELETE FROM financial_event WHERE @pms_prepare_formal_empty = 1;
DELETE FROM grid_area WHERE @pms_prepare_formal_empty = 1;
DELETE FROM idempotency_record WHERE @pms_prepare_formal_empty = 1;
DELETE FROM integration_callback_inbox WHERE @pms_prepare_formal_empty = 1;
DELETE FROM integration_dead_letter WHERE @pms_prepare_formal_empty = 1;
DELETE FROM integration_delivery_attempt WHERE @pms_prepare_formal_empty = 1;
DELETE FROM invoice_request WHERE @pms_prepare_formal_empty = 1;
DELETE FROM iot_reading_inbox WHERE @pms_prepare_formal_empty = 1;
DELETE FROM meter WHERE @pms_prepare_formal_empty = 1;
DELETE FROM meter_charge_reconciliation WHERE @pms_prepare_formal_empty = 1;
DELETE FROM meter_event WHERE @pms_prepare_formal_empty = 1;
DELETE FROM meter_import_job WHERE @pms_prepare_formal_empty = 1;
DELETE FROM meter_reading WHERE @pms_prepare_formal_empty = 1;
DELETE FROM meter_reading_batch WHERE @pms_prepare_formal_empty = 1;
DELETE FROM meter_replacement WHERE @pms_prepare_formal_empty = 1;
DELETE FROM meter_share_result WHERE @pms_prepare_formal_empty = 1;
DELETE FROM meter_share_rule WHERE @pms_prepare_formal_empty = 1;
DELETE FROM meter_share_rule_version WHERE @pms_prepare_formal_empty = 1;
DELETE FROM migration_batch WHERE @pms_prepare_formal_empty = 1;
DELETE FROM migration_batch_event WHERE @pms_prepare_formal_empty = 1;
DELETE FROM migration_canonical_record WHERE @pms_prepare_formal_empty = 1;
DELETE FROM migration_change_log WHERE @pms_prepare_formal_empty = 1;
DELETE FROM migration_object_map WHERE @pms_prepare_formal_empty = 1;
DELETE FROM migration_quarantine_record WHERE @pms_prepare_formal_empty = 1;
DELETE FROM migration_raw_record WHERE @pms_prepare_formal_empty = 1;
DELETE FROM migration_reconciliation WHERE @pms_prepare_formal_empty = 1;
DELETE FROM migration_staging_record WHERE @pms_prepare_formal_empty = 1;
DELETE FROM notification_batch WHERE @pms_prepare_formal_empty = 1;
DELETE FROM notification_message WHERE @pms_prepare_formal_empty = 1;
DELETE FROM org_position WHERE @pms_prepare_formal_empty = 1;
DELETE FROM organization_unit WHERE @pms_prepare_formal_empty = 1;
DELETE FROM outbox_event WHERE @pms_prepare_formal_empty = 1;
DELETE FROM parking_space_detail WHERE @pms_prepare_formal_empty = 1;
DELETE FROM payment_allocation WHERE @pms_prepare_formal_empty = 1;
DELETE FROM payment_order WHERE @pms_prepare_formal_empty = 1;
DELETE FROM payment_order_intent WHERE @pms_prepare_formal_empty = 1;
DELETE FROM payment_transaction WHERE @pms_prepare_formal_empty = 1;
DELETE FROM pms_unit WHERE @pms_prepare_formal_empty = 1;
DELETE FROM prepayment_account WHERE @pms_prepare_formal_empty = 1;
DELETE FROM prepayment_transaction WHERE @pms_prepare_formal_empty = 1;
DELETE FROM property_relation_event WHERE @pms_prepare_formal_empty = 1;
DELETE FROM receipt WHERE @pms_prepare_formal_empty = 1;
DELETE FROM receipt_number_segment WHERE @pms_prepare_formal_empty = 1;
DELETE FROM receipt_print_item WHERE @pms_prepare_formal_empty = 1;
DELETE FROM receipt_print_job WHERE @pms_prepare_formal_empty = 1;
DELETE FROM receivable_generation_error WHERE @pms_prepare_formal_empty = 1;
DELETE FROM receivable_generation_item WHERE @pms_prepare_formal_empty = 1;
DELETE FROM receivable_generation_job WHERE @pms_prepare_formal_empty = 1;
DELETE FROM receivable_generation_reconciliation WHERE @pms_prepare_formal_empty = 1;
DELETE FROM report_export_event WHERE @pms_prepare_formal_empty = 1;
DELETE FROM report_export_job WHERE @pms_prepare_formal_empty = 1;
DELETE FROM reversal WHERE @pms_prepare_formal_empty = 1;
DELETE FROM room_detail WHERE @pms_prepare_formal_empty = 1;
DELETE FROM sys_user WHERE @pms_prepare_formal_empty = 1;
DELETE FROM sys_user_project_scope WHERE @pms_prepare_formal_empty = 1;
DELETE FROM sys_user_role WHERE @pms_prepare_formal_empty = 1;
DELETE FROM vehicle WHERE @pms_prepare_formal_empty = 1;
DELETE FROM vehicle_parking_relation WHERE @pms_prepare_formal_empty = 1;
DELETE FROM visitor_record WHERE @pms_prepare_formal_empty = 1;

UPDATE system_setup
   SET initialized = FALSE,
       deployment_mode = 'SINGLE_PROJECT',
       enterprise_id = NULL,
       community_id = NULL,
       initialized_by = NULL,
       initialized_at = NULL,
       version = version + 1,
       updated_at = CURRENT_TIMESTAMP(3)
 WHERE singleton_id = 1
   AND @pms_prepare_formal_empty = 1;

SET FOREIGN_KEY_CHECKS = @pms_previous_foreign_key_checks;
