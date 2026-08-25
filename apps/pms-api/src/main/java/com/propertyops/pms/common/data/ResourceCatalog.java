package com.propertyops.pms.common.data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.propertyops.pms.common.api.BusinessException;

@Component
public class ResourceCatalog {
    private final Map<String, ResourceDefinition> resources = new LinkedHashMap<>();

    public ResourceCatalog() {
        add(direct("communities", "community", "t.id", "property", search("t.name", "t.address", "t.contact_name"),
                "t.status", null, sorts("name", "t.name", "managedArea", "t.managed_area", "updatedAt", "t.updated_at"),
                "t.name ASC",
                cols("enterprise_id", "source_system", "source_id", "name", "managed_area", "address", "province_code", "city_code",
                        "district_code", "longitude", "latitude", "service_phone", "contact_name", "status"),
                cols("name", "managed_area", "address", "province_code", "city_code", "district_code", "longitude",
                        "latitude", "service_phone", "contact_name", "status"), "status", true));
        add(direct("grids", "grid_area", "t.community_id", "property", search("t.code", "t.name"), "t.status",
                null, sorts("code", "t.code", "name", "t.name", "sortOrder", "t.sort_order", "updatedAt", "t.updated_at"),
                "t.sort_order, t.code", cols("parent_id", "code", "name", "manager_user_id", "sort_order", "status"),
                cols("parent_id", "code", "name", "manager_user_id", "sort_order", "status"), "status", false));
        add(direct("buildings", "building", "t.community_id", "property", search("t.code", "t.name"), "t.status",
                "t.building_type", sorts("code", "t.code", "name", "t.name", "floorCount", "t.floor_count", "updatedAt", "t.updated_at"),
                "t.code ASC", cols("grid_id", "source_id", "code", "name", "building_type", "floor_count", "status"),
                cols("grid_id", "code", "name", "building_type", "floor_count", "status"), "status", false));
        add(direct("units", "pms_unit", "t.community_id", "property", search("t.code", "t.name"), "t.status", null,
                sorts("code", "t.code", "name", "t.name", "updatedAt", "t.updated_at"), "t.code ASC",
                cols("building_id", "code", "name", "status"), cols("building_id", "code", "name", "status"),
                "status", false));
        add(direct("assets", "asset", "t.community_id", "property", search("t.code", "t.display_name", "t.source_id"),
                "t.operation_status", "t.asset_type",
                sorts("code", "t.code", "displayName", "t.display_name", "buildingArea", "t.building_area", "updatedAt", "t.updated_at"),
                "t.code ASC",
                cols("grid_id", "building_id", "unit_id", "source_id", "asset_type", "code", "display_name", "floor_no",
                        "building_area", "usable_area", "occupancy_status", "operation_status", "enabled", "valid_from", "valid_to"),
                cols("grid_id", "building_id", "unit_id", "code", "display_name", "floor_no", "building_area", "usable_area",
                        "occupancy_status", "operation_status", "enabled", "valid_from", "valid_to"), "enabled", false));
        add(direct("customers", "customer", "t.community_id", "property", search("t.display_name", "t.mobile_masked", "t.source_id"),
                "t.status", "t.customer_type",
                sorts("displayName", "t.display_name", "customerType", "t.customer_type", "updatedAt", "t.updated_at"),
                "t.display_name ASC",
                cols("source_id", "customer_no", "display_name", "customer_type", "customer_class", "mobile_masked", "mobile_search_hash",
                        "certificate_type", "certificate_masked", "gender", "birthday", "remarks", "status"),
                cols("customer_no", "display_name", "customer_type", "customer_class", "mobile_masked", "mobile_search_hash",
                        "certificate_type", "certificate_masked", "gender", "birthday", "remarks", "status"), "status", false));
        add(joined("customer-asset-relations", "customer_asset_relation", "t.*",
                "customer_asset_relation t JOIN customer c ON c.id=t.customer_id", "c.community_id", "property",
                search("t.customer_id", "t.asset_id", "t.relation_type"), "t.status", "t.relation_type",
                sorts("startDate", "t.start_date", "updatedAt", "t.updated_at"), "t.created_at DESC"));
        add(direct("vehicles", "vehicle", "t.community_id", "property", search("t.plate_no_masked"), "t.status", "t.vehicle_type",
                sorts("plate", "t.plate_no_masked", "updatedAt", "t.updated_at"), "t.created_at DESC",
                cols("plate_no_masked", "plate_no_search_hash", "vehicle_type", "color", "status"),
                cols("plate_no_masked", "plate_no_search_hash", "vehicle_type", "color", "status"), "status", false));
        add(direct("meters", "meter", "t.community_id", "meter", search("t.meter_no"), "t.status", "t.meter_type",
                sorts("meterNo", "t.meter_no", "meterType", "t.meter_type", "updatedAt", "t.updated_at"), "t.meter_no ASC",
                cols("asset_id", "parent_meter_id", "meter_no", "meter_type", "meter_class", "status", "range_value",
                        "multiplier", "loss_rate", "correction", "installed_at"),
                cols("asset_id", "parent_meter_id", "meter_no", "meter_type", "meter_class", "status", "range_value",
                        "multiplier", "loss_rate", "correction", "installed_at"), "status", false));
        add(direct("fee-definitions", "fee_definition", "t.community_id", "fee", search("t.code", "t.name"),
                "CASE WHEN t.enabled THEN 'ACTIVE' ELSE 'INACTIVE' END", "t.fee_type",
                sorts("code", "t.code", "name", "t.name", "updatedAt", "t.updated_at"), "t.code ASC",
                cols("source_id", "code", "name", "fee_type", "fee_class", "unit_code", "decimal_scale", "late_fee_enabled",
                        "enabled", "accounting_subject_code", "prepayment_subject_code"),
                cols("code", "name", "fee_type", "fee_class", "unit_code", "decimal_scale", "late_fee_enabled", "enabled",
                        "accounting_subject_code", "prepayment_subject_code"), "enabled", false));
        add(joined("fee-standards", "fee_standard", "t.*",
                "fee_standard t JOIN fee_definition fd ON fd.id=t.fee_definition_id", "fd.community_id", "fee",
                search("t.code", "t.name", "fd.name"), "t.status", "t.asset_type",
                sorts("code", "t.code", "name", "t.name", "updatedAt", "t.updated_at"), "t.code ASC"));
        add(joined("fee-standard-versions", "fee_standard_version", "t.*",
                "fee_standard_version t JOIN fee_standard fs ON fs.id=t.fee_standard_id JOIN fee_definition fd ON fd.id=fs.fee_definition_id",
                "fd.community_id", "fee", search("fs.code", "fs.name", "t.formula_code"), "t.status", "t.formula_code",
                sorts("effectiveFrom", "t.effective_from", "versionNo", "t.version_no"), "t.effective_from DESC"));
        add(joined("fee-allocations", "fee_allocation", "t.*",
                "fee_allocation t JOIN asset a ON a.id=t.asset_id", "a.community_id", "fee",
                search("t.asset_id", "t.fee_standard_id"), "t.status", null,
                sorts("effectiveFrom", "t.effective_from", "updatedAt", "t.updated_at"), "t.created_at DESC"));
        add(directRead("receivable-jobs", "receivable_generation_job", "t.community_id", "fee",
                search("t.billing_period", "t.request_key"), "t.status", null, "t.created_at DESC"));
        add(directRead("bills", "bill", "t.community_id", "cashier", search("t.bill_no", "t.billing_period", "t.asset_id"),
                "t.status", null, "t.due_date DESC"));
        add(directRead("payment-orders", "payment_order", "t.community_id", "cashier", search("t.order_no"),
                "t.status", "t.payment_method", "t.created_at DESC"));
        add(joined("payment-transactions", "payment_transaction", "t.*",
                "payment_transaction t JOIN payment_order po ON po.id=t.payment_order_id", "po.community_id", "cashier",
                search("t.transaction_no", "t.external_reference"), "t.status", "t.transaction_type",
                sorts("occurredAt", "t.occurred_at", "amount", "t.amount"), "t.occurred_at DESC"));
        add(directRead("prepayment-accounts", "prepayment_account", "t.community_id", "cashier", search("t.customer_id"),
                null, null, "t.updated_at DESC"));
        add(directRead("deposits", "deposit_account", "t.community_id", "cashier", search("t.customer_id", "t.asset_id"),
                "t.status", "t.deposit_type", "t.updated_at DESC"));
        add(directRead("receipts", "receipt", "t.community_id", "cashier", search("t.receipt_no"), "t.status", null,
                "t.issued_at DESC"));
        add(directRead("meter-reading-batches", "meter_reading_batch", "t.community_id", "meter",
                search("t.batch_no", "t.reading_period"), "t.status", "t.source_type", "t.created_at DESC"));
        add(joined("meter-readings", "meter_reading", "t.*",
                "meter_reading t JOIN meter m ON m.id=t.meter_id", "m.community_id", "meter",
                search("m.meter_no", "t.batch_id"), "t.status", null,
                sorts("readingAt", "t.reading_at", "usage", "t.billable_usage"), "t.reading_at DESC"));
        add(directRead("meter-share-rules", "meter_share_rule", "t.community_id", "meter", search("t.code", "t.name"),
                "t.status", "t.strategy_code", "t.created_at DESC"));
        add(joined("meter-replacements", "meter_replacement", "t.*",
                "meter_replacement t JOIN meter m ON m.id=t.old_meter_id", "m.community_id", "meter",
                search("m.meter_no", "t.reason"), null, null,
                sorts("replacedAt", "t.replaced_at"), "t.replaced_at DESC"));
        add(new ResourceDefinition("users", "sys_user", "t.id, t.username, t.display_name, t.enabled, t.version, t.created_at, t.updated_at",
                "sys_user t", null, "system:audit", null, search("t.username", "t.display_name"),
                "CASE WHEN t.enabled THEN 'ACTIVE' ELSE 'INACTIVE' END", null,
                sorts("username", "t.username", "displayName", "t.display_name", "updatedAt", "t.updated_at"),
                "t.username ASC", Set.of(), Set.of(), null, false));
        add(new ResourceDefinition("audit-events", "audit_event", "t.*", "audit_event t", "t.community_id",
                "system:audit", null, search("t.action_code", "t.resource_type", "t.resource_id"),
                "t.result_status", null, sorts("occurredAt", "t.occurred_at"), "t.occurred_at DESC",
                Set.of(), Set.of(), null, false));
        add(new ResourceDefinition("dictionaries", "system_dictionary", "t.*", "system_dictionary t", null,
                "dashboard:read", null, search("t.dictionary_type", "t.code", "t.display_name"),
                "CASE WHEN t.enabled THEN 'ACTIVE' ELSE 'INACTIVE' END", "t.dictionary_type",
                sorts("sortOrder", "t.sort_order", "displayName", "t.display_name"), "t.dictionary_type, t.sort_order",
                Set.of(), Set.of(), null, false));
    }

    public ResourceDefinition require(String name) {
        ResourceDefinition definition = resources.get(name);
        if (definition == null) {
            throw new BusinessException("RESOURCE_NOT_FOUND", "未找到数据资源", HttpStatus.NOT_FOUND);
        }
        return definition;
    }

    public List<String> names() {
        return List.copyOf(resources.keySet());
    }

    private void add(ResourceDefinition definition) {
        resources.put(definition.apiName(), definition);
    }

    private static ResourceDefinition direct(String name, String table, String scope, String permission,
                                             Set<String> search, String status, String category,
                                             Map<String, String> sorts, String defaultSort,
                                             Set<String> creates, Set<String> updates, String archiveColumn,
                                             boolean community) {
        return new ResourceDefinition(name, table, "t.*", table + " t", scope, permission + ":read", permission + ":write",
                search, status, category, sorts, defaultSort, creates, updates, archiveColumn, community);
    }

    private static ResourceDefinition directRead(String name, String table, String scope, String permission,
                                                 Set<String> search, String status, String category, String defaultSort) {
        return new ResourceDefinition(name, table, "t.*", table + " t", scope, permission + ":read", null,
                search, status, category, sorts("createdAt", "t.created_at"), defaultSort,
                Set.of(), Set.of(), null, false);
    }

    private static ResourceDefinition joined(String name, String table, String select, String from, String scope,
                                             String permission, Set<String> search, String status, String category,
                                             Map<String, String> sorts, String defaultSort) {
        return new ResourceDefinition(name, table, select, from, scope, permission + ":read", null,
                search, status, category, sorts, defaultSort, Set.of(), Set.of(), null, false);
    }

    private static Set<String> search(String... columns) {
        return Set.of(columns);
    }

    private static Set<String> cols(String... columns) {
        return Set.of(columns);
    }

    private static Map<String, String> sorts(String... entries) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) result.put(entries[i], entries[i + 1]);
        return result;
    }
}
