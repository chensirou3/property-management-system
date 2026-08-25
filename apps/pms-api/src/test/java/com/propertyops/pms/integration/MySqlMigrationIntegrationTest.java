package com.propertyops.pms.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class MySqlMigrationIntegrationTest {
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("pms_migration_test")
            .withUsername("pms_test")
            .withPassword("pms_test_password");

    @Test
    void appliesAllMigrationsAgainstRealMySqlAndLoadsSyntheticBaseline() throws Exception {
        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load();

        var result = flyway.migrate();

        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isEqualTo(20);
        assertThat(result.targetSchemaVersion).isEqualTo("20");
        try (var connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.createStatement()) {
            assertCount(statement, "SELECT COUNT(*) FROM community", 2);
            assertCount(statement, "SELECT COUNT(*) FROM organization_unit", 3);
            assertCount(statement, "SELECT COUNT(*) FROM sys_permission WHERE code LIKE 'iam:%'", 3);
            assertCount(statement, "SELECT COUNT(*) FROM sys_permission", 65);
            assertCount(statement, """
                    SELECT COUNT(*)
                      FROM sys_role_permission
                     WHERE role_id = '10000000-0000-0000-0000-000000000001'
                    """, 65);

            assertCount(statement, "SELECT COUNT(*) FROM grid_area", 1);
            assertCount(statement, "SELECT COUNT(*) FROM building", 9);
            assertCount(statement, "SELECT COUNT(*) FROM pms_unit", 5);
            assertCount(statement, "SELECT COUNT(*) FROM room_detail", 360);
            assertCount(statement, "SELECT COUNT(*) FROM parking_space_detail", 251);
            assertCount(statement, "SELECT COUNT(*) FROM customer", 404);
            assertCount(statement, "SELECT COUNT(*) FROM customer_asset_relation", 404);
            assertCount(statement, "SELECT COUNT(*) FROM vehicle", 31);
            assertCount(statement, "SELECT COUNT(*) FROM vehicle_parking_relation", 31);
            assertCount(statement, "SELECT COUNT(*) FROM meter", 32);
            assertCount(statement, "SELECT COUNT(*) FROM fee_definition", 23);
            assertCount(statement, "SELECT COUNT(*) FROM fee_definition WHERE temporary_allowed=TRUE", 1);
            assertCount(statement, "SELECT COUNT(*) FROM receipt_number_segment", 2);
            assertCount(statement, "SELECT COUNT(*) FROM discount_policy", 1);
            assertCount(statement, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()", 90);
            assertCount(statement, "SELECT COUNT(*) FROM report_definition WHERE status='ACTIVE'", 22);
            assertCount(statement, """
                    SELECT COUNT(*) FROM report_definition
                    WHERE report_code='RECEIPT_BATCH_PRINT'
                      AND JSON_CONTAINS(columns_json, JSON_QUOTE('receiptId'))=1
                    """, 1);

            var primaryProject = "30000000-0000-0000-0000-000000000001";
            var isolatedProject = "30000000-0000-0000-0000-000000000002";
            assertCount(statement, projectCount("grid_area", primaryProject), 0);
            assertCount(statement, projectCount("building", primaryProject), 8);
            assertCount(statement, projectCount("asset", primaryProject) + " AND asset_type = 'ROOM'", 359);
            assertCount(statement, projectCount("asset", primaryProject) + " AND asset_type = 'PARKING'", 250);
            assertCount(statement, projectCount("customer", primaryProject), 403);
            assertCount(statement, projectCount("grid_area", isolatedProject), 1);
            assertCount(statement, projectCount("building", isolatedProject), 1);
            assertCount(statement, projectCount("pms_unit", isolatedProject), 1);
            assertCount(statement, projectCount("asset", isolatedProject) + " AND asset_type = 'ROOM'", 1);
            assertCount(statement, projectCount("asset", isolatedProject) + " AND asset_type = 'PARKING'", 1);
            assertCount(statement, projectCount("customer", isolatedProject), 1);
            assertCount(statement, projectCount("customer_asset_relation", isolatedProject), 1);
            assertCount(statement, projectCount("vehicle", isolatedProject), 1);
            assertCount(statement, projectCount("vehicle_parking_relation", isolatedProject), 1);
            assertCount(statement, projectCount("meter", isolatedProject), 1);

            assertCount(statement, """
                    SELECT COUNT(*) FROM pms_unit u
                    JOIN building b ON b.id = u.building_id
                    WHERE u.community_id <> b.community_id
                    """, 0);
            assertCount(statement, """
                    SELECT COUNT(*) FROM asset a
                    LEFT JOIN building b ON b.id = a.building_id
                    LEFT JOIN pms_unit u ON u.id = a.unit_id
                    WHERE (a.building_id IS NOT NULL AND (b.id IS NULL OR b.community_id <> a.community_id))
                       OR (a.unit_id IS NOT NULL AND
                           (u.id IS NULL OR u.community_id <> a.community_id OR u.building_id <> a.building_id))
                    """, 0);
            assertCount(statement, """
                    SELECT COUNT(*) FROM customer_asset_relation r
                    LEFT JOIN customer c ON c.id = r.customer_id
                    LEFT JOIN asset a ON a.id = r.asset_id
                    WHERE c.id IS NULL OR a.id IS NULL
                       OR c.community_id <> r.community_id OR a.community_id <> r.community_id
                    """, 0);
            assertCount(statement, """
                    SELECT COUNT(*) FROM vehicle_parking_relation r
                    LEFT JOIN vehicle v ON v.id = r.vehicle_id
                    LEFT JOIN asset a ON a.id = r.parking_asset_id
                    LEFT JOIN customer c ON c.id = r.customer_id
                    WHERE v.id IS NULL OR a.id IS NULL OR c.id IS NULL
                       OR v.community_id <> r.community_id OR a.community_id <> r.community_id
                       OR c.community_id <> r.community_id
                    """, 0);
            assertCount(statement, """
                    SELECT COUNT(*) FROM meter m
                    LEFT JOIN asset a ON a.id = m.asset_id
                    LEFT JOIN meter p ON p.id = m.parent_meter_id
                    WHERE (m.asset_id IS NOT NULL AND (a.id IS NULL OR a.community_id <> m.community_id))
                       OR (m.parent_meter_id IS NOT NULL AND (p.id IS NULL OR p.community_id <> m.community_id))
                    """, 0);
            assertCount(statement, """
                    SELECT COUNT(*) FROM (
                        SELECT customer_id, asset_id, relation_type
                        FROM customer_asset_relation
                        WHERE status = 'ACTIVE' AND end_date IS NULL
                        GROUP BY customer_id, asset_id, relation_type
                        HAVING COUNT(*) > 1
                    ) duplicates
                    """, 0);
            assertCount(statement, "SELECT COUNT(*) FROM asset WHERE usable_area > building_area", 0);
            assertCount(statement, """
                    SELECT COUNT(*) FROM information_schema.tables
                    WHERE table_schema = DATABASE()
                      AND table_name IN (
                        'migration_batch', 'migration_raw_record', 'migration_quarantine_record',
                        'migration_canonical_record', 'migration_staging_record', 'migration_object_map',
                        'migration_reconciliation', 'migration_change_log', 'migration_batch_event'
                      )
                    """, 9);
            assertCount(statement, """
                    SELECT COUNT(*) FROM information_schema.tables
                    WHERE table_schema = DATABASE()
                      AND table_name IN (
                        'fee_configuration_event', 'receivable_generation_item',
                        'receivable_generation_reconciliation'
                      )
                    """, 3);
            assertCount(statement, """
                    SELECT COUNT(*) FROM information_schema.table_constraints
                    WHERE constraint_schema = DATABASE()
                      AND constraint_name IN (
                        'ck_fee_definition_rounding', 'fk_fee_standard_definition_scope',
                        'ck_standard_version_dates', 'ck_allocation_target',
                        'uk_bill_periodic', 'ck_receivable_job_status'
                      )
                    """, 6);
            assertCount(statement, """
                    SELECT COUNT(*) FROM information_schema.tables
                    WHERE table_schema = DATABASE()
                      AND table_name IN (
                        'meter_share_rule_version', 'iot_reading_inbox',
                        'meter_charge_reconciliation', 'meter_event'
                      )
                    """, 4);
            assertCount(statement, """
                    SELECT COUNT(*) FROM information_schema.table_constraints
                    WHERE constraint_schema = DATABASE()
                      AND constraint_name IN (
                        'uk_meter_batch_request', 'uk_meter_reading_source',
                        'uk_meter_share_rule_version', 'uk_meter_replacement_request',
                        'uk_iot_reading_source', 'uk_meter_charge_reconciliation'
                      )
                    """, 6);
            assertCount(statement, "SELECT COUNT(*) FROM meter_share_rule_version", 1);
            assertCount(statement, "SELECT COUNT(*) FROM fee_standard WHERE asset_type='METER'", 1);
            assertCount(statement, "SELECT COUNT(*) FROM fee_allocation WHERE target_type='METER'", 30);
            assertCount(statement, """
                    SELECT COUNT(*) FROM information_schema.tables
                    WHERE table_schema = DATABASE()
                      AND table_name IN (
                        'report_definition', 'report_export_job', 'report_export_event',
                        'receipt_print_job', 'receipt_print_item',
                        'notification_batch', 'notification_message'
                      )
                    """, 7);
            assertCount(statement, """
                    SELECT COUNT(*) FROM information_schema.table_constraints
                    WHERE constraint_schema = DATABASE()
                      AND constraint_name IN (
                        'uk_report_definition_code', 'uk_report_export_request',
                        'uk_receipt_print_request', 'uk_receipt_print_item',
                        'uk_notification_request', 'uk_notification_message_bill',
                        'ck_notification_simulated', 'ck_receipt_print_counter'
                      )
                    """, 8);
            assertCount(statement, """
                    SELECT COUNT(*) FROM information_schema.tables
                    WHERE table_schema = DATABASE()
                      AND table_name IN (
                        'integration_adapter_policy', 'integration_callback_inbox',
                        'integration_delivery_attempt', 'integration_dead_letter'
                      )
                    """, 4);
            assertCount(statement, "SELECT COUNT(*) FROM integration_adapter_policy", 5);
            assertCount(statement, """
                    SELECT COUNT(*) FROM integration_adapter_policy
                    WHERE production_ready=TRUE OR mode NOT IN ('SIMULATOR','DISABLED')
                    """, 0);
            assertCount(statement, """
                    SELECT COUNT(*) FROM integration_adapter_policy
                    WHERE adapter_code='JAVA110_DISABLED' AND mode='DISABLED' AND enabled=FALSE
                    """, 1);
            assertCount(statement, """
                    SELECT COUNT(*) FROM information_schema.table_constraints
                    WHERE constraint_schema = DATABASE()
                      AND constraint_name IN (
                        'uk_integration_callback', 'uk_integration_attempt',
                        'uk_integration_dead_letter', 'ck_outbox_delivery_status'
                      )
                    """, 4);
            assertCount(statement, """
                    SELECT COUNT(*) FROM information_schema.table_constraints
                    WHERE constraint_schema = DATABASE()
                      AND constraint_name IN (
                        'fk_asset_unit_scope', 'uk_customer_no', 'uk_relation_active',
                        'fk_relation_customer_scope', 'fk_relation_asset_scope',
                        'uk_vehicle_parking_active', 'fk_meter_asset_scope', 'ck_asset_area'
                      )
                    """, 8);
            assertCount(statement, """
                    SELECT COUNT(*) FROM information_schema.tables
                    WHERE table_schema = DATABASE()
                      AND table_name IN (
                        'cashier_shift', 'receipt_number_segment', 'discount_policy',
                        'bill_adjustment', 'financial_event'
                      )
                    """, 5);
            assertCount(statement, """
                    SELECT COUNT(*) FROM information_schema.table_constraints
                    WHERE constraint_schema = DATABASE()
                      AND constraint_name IN (
                        'uk_cashier_open_shift', 'uk_successful_payment_order',
                        'ck_payment_transaction_amount', 'uk_deposit_account_identity',
                        'uk_receipt_segment_sequence', 'uk_bill_adjustment_request'
                      )
                    """, 6);
            assertCount(statement, "SELECT COUNT(*) FROM dashboard_widget_configuration WHERE status='PUBLISHED'", 4);
            assertCount(statement, "SELECT COUNT(*) FROM visitor_record", 3);
            assertCount(statement, """
                    SELECT COUNT(*) FROM visitor_record
                    WHERE source_mode<>'IOT_SIMULATOR' OR production_connected=TRUE
                    """, 0);
            assertCount(statement, """
                    SELECT COUNT(*) FROM information_schema.table_constraints
                    WHERE constraint_schema = DATABASE()
                      AND constraint_name IN (
                        'uk_dashboard_widget_scope', 'ck_dashboard_widget_status',
                        'uk_visitor_record_no', 'ck_visitor_record_status',
                        'ck_visitor_record_source', 'ck_visitor_record_timeline'
                      )
                    """, 6);
        }
    }

    private static String projectCount(String table, String communityId) {
        return "SELECT COUNT(*) FROM " + table + " WHERE community_id = '" + communityId + "'";
    }

    private static void assertCount(Statement statement, String sql, int expected) throws Exception {
        try (var resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).as(sql).isTrue();
            assertThat(resultSet.getInt(1)).as(sql).isEqualTo(expected);
        }
    }
}
