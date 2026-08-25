package com.propertyops.pms.common.data;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.propertyops.pms.common.api.BusinessException;
import com.propertyops.pms.common.audit.AuditService;
import com.propertyops.pms.security.SecurityContextService;

@Service
public class DataResourceService {
    private final NamedParameterJdbcTemplate jdbc;
    private final ResourceCatalog catalog;
    private final SecurityContextService security;
    private final AuditService audit;

    public DataResourceService(NamedParameterJdbcTemplate jdbc, ResourceCatalog catalog,
                               SecurityContextService security, AuditService audit) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.security = security;
        this.audit = audit;
    }

    public PageResponse<Map<String, Object>> list(String resourceName, String communityId, String keyword,
                                                  String status, String category, int page, int size, String sort) {
        ResourceDefinition definition = catalog.require(resourceName);
        security.requirePermission(definition.readPermission());
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 200);
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        List<String> predicates = scopePredicates(definition, communityId, parameters);
        if (keyword != null && !keyword.isBlank() && !definition.searchColumns().isEmpty()) {
            parameters.addValue("keyword", "%" + escapeLike(keyword.trim()) + "%");
            predicates.add(definition.searchColumns().stream()
                    .map(column -> column + " LIKE :keyword ESCAPE '\\\\'")
                    .collect(Collectors.joining(" OR ", "(", ")")));
        }
        if (status != null && !status.isBlank() && definition.statusExpression() != null) {
            predicates.add(definition.statusExpression() + " = :status");
            parameters.addValue("status", status);
        }
        if (category != null && !category.isBlank() && definition.categoryExpression() != null) {
            predicates.add(definition.categoryExpression() + " = :category");
            parameters.addValue("category", category);
        }
        String where = predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates);
        String orderBy = orderBy(definition, sort);
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM " + definition.fromSql() + where,
                parameters, Long.class);
        parameters.addValue("limit", safeSize).addValue("offset", (safePage - 1) * safeSize);
        List<Map<String, Object>> items = jdbc.queryForList(
                "SELECT " + definition.selectSql() + " FROM " + definition.fromSql() + where
                        + " ORDER BY " + orderBy + " LIMIT :limit OFFSET :offset", parameters);
        return new PageResponse<>(items, safePage, safeSize, total);
    }

    public Map<String, Object> get(String resourceName, String id, String communityId) {
        ResourceDefinition definition = catalog.require(resourceName);
        security.requirePermission(definition.readPermission());
        MapSqlParameterSource parameters = new MapSqlParameterSource("id", id);
        List<String> predicates = scopePredicates(definition, communityId, parameters);
        predicates.add("t.id = :id");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT " + definition.selectSql() + " FROM " + definition.fromSql()
                        + " WHERE " + String.join(" AND ", predicates), parameters);
        if (rows.isEmpty()) throw notFound();
        return rows.get(0);
    }

    @Transactional
    public Map<String, Object> create(String resourceName, String communityId, Map<String, Object> body) {
        ResourceDefinition definition = writable(resourceName);
        security.requirePermission(definition.writePermission());
        String id = UUID.randomUUID().toString();
        Map<String, Object> values = allowed(body, definition.createColumns());
        if (definition.communityResource()) {
            security.requireRole("PLATFORM_ADMIN");
        } else {
            requireCommunity(communityId);
            security.requireProject(communityId);
            values.put("community_id", communityId);
        }
        values.put("id", id);
        applyGeneratedDefaults(resourceName, id, values);
        validateReferences(resourceName, id, communityId, values, true);
        values.put("version", 0L);
        values.put("created_at", LocalDateTime.now(ZoneOffset.UTC));
        values.put("updated_at", LocalDateTime.now(ZoneOffset.UTC));
        String columns = String.join(", ", values.keySet());
        String bindings = values.keySet().stream().map(key -> ":" + key).collect(Collectors.joining(", "));
        jdbc.update("INSERT INTO " + definition.tableName() + " (" + columns + ") VALUES (" + bindings + ")", values);
        String effectiveCommunity = definition.communityResource() ? id : communityId;
        audit.success(effectiveCommunity, resourceName + ":create", resourceName, id, Map.of("fields", values.keySet()));
        return get(resourceName, id, effectiveCommunity);
    }

    @Transactional
    public Map<String, Object> update(String resourceName, String id, String communityId,
                                      long version, Map<String, Object> body) {
        ResourceDefinition definition = writable(resourceName);
        security.requirePermission(definition.writePermission());
        String effectiveCommunity = definition.communityResource() ? id : requireCommunity(communityId);
        security.requireProject(effectiveCommunity);
        requireWritableRow(definition, id, effectiveCommunity);
        Map<String, Object> values = allowed(body, definition.updateColumns());
        if (values.isEmpty()) throw new BusinessException("EMPTY_UPDATE", "没有可更新字段", HttpStatus.BAD_REQUEST);
        validateReferences(resourceName, id, effectiveCommunity, values, false);
        values.put("id", id);
        values.put("version", version);
        values.put("communityId", effectiveCommunity);
        values.put("updated_at", LocalDateTime.now(ZoneOffset.UTC));
        String assignments = values.keySet().stream()
                .filter(key -> !List.of("id", "version").contains(key))
                .map(key -> key + " = :" + key).collect(Collectors.joining(", "));
        int changed = jdbc.update("UPDATE " + definition.tableName() + " SET " + assignments
                + ", version = version + 1 WHERE id = :id AND version = :version"
                + (definition.communityResource() ? "" : " AND community_id = :communityId"), values);
        if (changed == 0) throw conflict();
        audit.success(effectiveCommunity, resourceName + ":update", resourceName, id, Map.of("version", version));
        return get(resourceName, id, effectiveCommunity);
    }

    @Transactional
    public Map<String, Object> archive(String resourceName, String id, String communityId, long version) {
        ResourceDefinition definition = writable(resourceName);
        security.requirePermission(definition.writePermission());
        if (definition.archiveColumn() == null) {
            throw new BusinessException("ARCHIVE_NOT_SUPPORTED", "该资源不支持停用", HttpStatus.CONFLICT);
        }
        String effectiveCommunity = definition.communityResource() ? id : requireCommunity(communityId);
        security.requireProject(effectiveCommunity);
        requireWritableRow(definition, id, effectiveCommunity);
        requireArchivable(resourceName, id, effectiveCommunity);
        Object archivedValue = "enabled".equals(definition.archiveColumn()) ? Boolean.FALSE : "INACTIVE";
        Map<String, Object> params = Map.of("id", id, "version", version, "archived", archivedValue,
                "updatedAt", LocalDateTime.now(ZoneOffset.UTC), "communityId", effectiveCommunity);
        int changed = jdbc.update("UPDATE " + definition.tableName() + " SET " + definition.archiveColumn()
                + " = :archived, updated_at = :updatedAt, version = version + 1 WHERE id = :id AND version = :version"
                + (definition.communityResource() ? "" : " AND community_id = :communityId"), params);
        if (changed == 0) throw conflict();
        audit.success(effectiveCommunity, resourceName + ":archive", resourceName, id, Map.of("version", version));
        return get(resourceName, id, effectiveCommunity);
    }

    private List<String> scopePredicates(ResourceDefinition definition, String communityId,
                                         MapSqlParameterSource parameters) {
        List<String> predicates = new ArrayList<>();
        if (definition.scopeExpression() == null) return predicates;
        if (definition.communityResource() && (communityId == null || communityId.isBlank())) {
            var principal = security.requirePrincipal();
            if (!principal.roles().contains("PLATFORM_ADMIN")) {
                if (principal.projectIds().isEmpty()) return List.of("1 = 0");
                predicates.add(definition.scopeExpression() + " IN (:allowedProjects)");
                parameters.addValue("allowedProjects", principal.projectIds());
            }
            return predicates;
        }
        requireCommunity(communityId);
        security.requireProject(communityId);
        predicates.add(definition.scopeExpression() + " = :communityId");
        parameters.addValue("communityId", communityId);
        return predicates;
    }

    private String orderBy(ResourceDefinition definition, String sort) {
        if (sort == null || sort.isBlank()) return definition.defaultSort();
        String[] parts = sort.split(",", 2);
        String column = definition.sortColumns().get(parts[0]);
        if (column == null) throw new BusinessException("INVALID_SORT", "不支持的排序字段", HttpStatus.BAD_REQUEST);
        String direction = parts.length > 1 && "desc".equalsIgnoreCase(parts[1]) ? "DESC" : "ASC";
        return column + " " + direction;
    }

    private ResourceDefinition writable(String name) {
        ResourceDefinition definition = catalog.require(name);
        if (!definition.writable()) {
            throw new BusinessException("RESOURCE_READ_ONLY", "该资源需使用专用业务接口维护", HttpStatus.CONFLICT);
        }
        return definition;
    }

    private Map<String, Object> allowed(Map<String, Object> body, java.util.Set<String> columns) {
        Map<String, Object> result = new LinkedHashMap<>();
        body.forEach((key, value) -> {
            if (columns.contains(key)) result.put(key, value);
        });
        return result;
    }

    private void applyGeneratedDefaults(String resourceName, String id, Map<String, Object> values) {
        if ("customers".equals(resourceName)
                && (values.get("customer_no") == null || String.valueOf(values.get("customer_no")).isBlank())) {
            values.put("customer_no", "CUS-" + id.replace("-", ""));
        }
    }

    private void validateReferences(String resourceName, String id, String communityId,
                                    Map<String, Object> values, boolean creating) {
        if (communityId == null) return;
        switch (resourceName) {
            case "grids" -> validateGrid(id, communityId, values);
            case "buildings" -> sameProjectIfPresent("grid_area", values.get("grid_id"), communityId, "网格");
            case "units" -> {
                Object buildingId = mergedValue("pms_unit", id, "building_id", values, creating);
                sameProjectRequired("building", buildingId, communityId, "楼栋");
            }
            case "assets" -> validateAsset(id, communityId, values, creating);
            case "meters" -> validateMeter(id, communityId, values, creating);
            default -> { }
        }
    }

    private void validateGrid(String id, String communityId, Map<String, Object> values) {
        Object parentId = values.get("parent_id");
        sameProjectIfPresent("grid_area", parentId, communityId, "上级网格");
        if (parentId == null) return;
        String cursor = String.valueOf(parentId);
        for (int depth = 0; depth < 100 && cursor != null; depth++) {
            if (id.equals(cursor)) throw invalidReference("网格层级不能形成循环");
            List<String> parents = jdbc.queryForList("""
                    SELECT parent_id FROM grid_area WHERE id=:id AND community_id=:communityId
                    """, Map.of("id", cursor, "communityId", communityId), String.class);
            cursor = parents.isEmpty() ? null : parents.get(0);
        }
    }

    private void validateAsset(String id, String communityId, Map<String, Object> values, boolean creating) {
        Object gridId = mergedValue("asset", id, "grid_id", values, creating);
        Object buildingId = mergedValue("asset", id, "building_id", values, creating);
        Object unitId = mergedValue("asset", id, "unit_id", values, creating);
        sameProjectIfPresent("grid_area", gridId, communityId, "网格");
        sameProjectIfPresent("building", buildingId, communityId, "楼栋");
        if (unitId != null && !String.valueOf(unitId).isBlank()) {
            if (buildingId == null || String.valueOf(buildingId).isBlank()) {
                throw invalidReference("选择单元时必须同时选择楼栋");
            }
            Long count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM pms_unit
                    WHERE id=:unitId AND building_id=:buildingId AND community_id=:communityId
                    """, Map.of("unitId", unitId, "buildingId", buildingId, "communityId", communityId), Long.class);
            if (count == null || count == 0) throw invalidReference("单元、楼栋与项目层级不一致");
        }
        if (gridId != null && buildingId != null && !String.valueOf(gridId).isBlank()
                && !String.valueOf(buildingId).isBlank()) {
            Long count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM building
                    WHERE id=:buildingId AND community_id=:communityId AND (grid_id IS NULL OR grid_id=:gridId)
                    """, Map.of("buildingId", buildingId, "communityId", communityId, "gridId", gridId), Long.class);
            if (count == null || count == 0) throw invalidReference("资产网格与楼栋所属网格不一致");
        }
    }

    private void validateMeter(String id, String communityId, Map<String, Object> values, boolean creating) {
        Object assetId = mergedValue("meter", id, "asset_id", values, creating);
        Object parentId = mergedValue("meter", id, "parent_meter_id", values, creating);
        sameProjectIfPresent("asset", assetId, communityId, "资产");
        sameProjectIfPresent("meter", parentId, communityId, "上级仪表");
        if (parentId != null && id.equals(String.valueOf(parentId))) throw invalidReference("仪表不能以自身作为上级表");
    }

    private Object mergedValue(String table, String id, String column, Map<String, Object> values, boolean creating) {
        if (values.containsKey(column)) return values.get(column);
        if (creating) return null;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT " + column + " FROM " + table + " WHERE id=:id", Map.of("id", id));
        return rows.isEmpty() ? null : rows.get(0).get(column);
    }

    private void sameProjectRequired(String table, Object id, String communityId, String label) {
        if (id == null || String.valueOf(id).isBlank()) throw invalidReference(label + "不能为空");
        sameProjectIfPresent(table, id, communityId, label);
    }

    private void sameProjectIfPresent(String table, Object id, String communityId, String label) {
        if (id == null || String.valueOf(id).isBlank()) return;
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table
                        + " WHERE id=:id AND community_id=:communityId",
                Map.of("id", id, "communityId", communityId), Long.class);
        if (count == null || count == 0) throw invalidReference(label + "不存在或不属于当前项目");
    }

    private void requireWritableRow(ResourceDefinition definition, String id, String communityId) {
        String sql = "SELECT COUNT(*) FROM " + definition.tableName() + " WHERE id=:id"
                + (definition.communityResource() ? "" : " AND community_id=:communityId");
        Long count = jdbc.queryForObject(sql, Map.of("id", id, "communityId", communityId), Long.class);
        if (count == null || count == 0) throw notFound();
    }

    private void requireArchivable(String resourceName, String id, String communityId) {
        long references = switch (resourceName) {
            case "communities" -> referenceCount("""
                    SELECT (SELECT COUNT(*) FROM building WHERE community_id=:id AND status='ACTIVE')
                         + (SELECT COUNT(*) FROM asset WHERE community_id=:id AND enabled=TRUE)
                         + (SELECT COUNT(*) FROM customer WHERE community_id=:id AND status='ACTIVE')
                    """, Map.of("id", id));
            case "grids" -> referenceCount("""
                    SELECT (SELECT COUNT(*) FROM grid_area WHERE parent_id=:id AND status='ACTIVE')
                         + (SELECT COUNT(*) FROM building WHERE grid_id=:id AND status='ACTIVE')
                         + (SELECT COUNT(*) FROM asset WHERE grid_id=:id AND enabled=TRUE)
                    """, Map.of("id", id));
            case "buildings" -> referenceCount("""
                    SELECT (SELECT COUNT(*) FROM pms_unit WHERE building_id=:id AND status='ACTIVE')
                         + (SELECT COUNT(*) FROM asset WHERE building_id=:id AND enabled=TRUE)
                    """, Map.of("id", id));
            case "units" -> referenceCount("SELECT COUNT(*) FROM asset WHERE unit_id=:id AND enabled=TRUE", Map.of("id", id));
            case "assets" -> referenceCount("""
                    SELECT (SELECT COUNT(*) FROM customer_asset_relation
                             WHERE asset_id=:id AND status='ACTIVE' AND end_date IS NULL)
                         + (SELECT COUNT(*) FROM vehicle_parking_relation
                             WHERE parking_asset_id=:id AND status='ACTIVE' AND end_date IS NULL)
                         + (SELECT COUNT(*) FROM meter WHERE asset_id=:id AND status='ACTIVE')
                         + (SELECT COUNT(*) FROM bill WHERE asset_id=:id AND status IN ('UNPAID','PARTIAL'))
                    """, Map.of("id", id));
            case "customers" -> referenceCount("""
                    SELECT (SELECT COUNT(*) FROM customer_asset_relation
                             WHERE customer_id=:id AND status='ACTIVE' AND end_date IS NULL)
                         + (SELECT COUNT(*) FROM vehicle_parking_relation
                             WHERE customer_id=:id AND status='ACTIVE' AND end_date IS NULL)
                         + (SELECT COUNT(*) FROM bill WHERE customer_id=:id AND status IN ('UNPAID','PARTIAL'))
                    """, Map.of("id", id));
            case "vehicles" -> referenceCount("""
                    SELECT COUNT(*) FROM vehicle_parking_relation
                    WHERE vehicle_id=:id AND status='ACTIVE' AND end_date IS NULL
                    """, Map.of("id", id));
            case "meters" -> referenceCount("""
                    SELECT (SELECT COUNT(*) FROM meter WHERE parent_meter_id=:id AND status='ACTIVE')
                         + (SELECT COUNT(*) FROM meter_reading WHERE meter_id=:id)
                    """, Map.of("id", id));
            default -> 0;
        };
        if (references > 0) {
            throw new BusinessException("PROPERTY_RESOURCE_IN_USE", "记录仍被有效业务数据引用，不能停用",
                    HttpStatus.CONFLICT);
        }
    }

    private long referenceCount(String sql, Map<String, ?> parameters) {
        Long value = jdbc.queryForObject(sql, parameters, Long.class);
        return value == null ? 0 : value;
    }

    private BusinessException invalidReference(String message) {
        return new BusinessException("PROPERTY_INVALID_REFERENCE", message, HttpStatus.UNPROCESSABLE_ENTITY);
    }

    private String requireCommunity(String communityId) {
        if (communityId == null || communityId.isBlank()) {
            throw new BusinessException("COMMUNITY_REQUIRED", "必须选择项目", HttpStatus.BAD_REQUEST);
        }
        return communityId;
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private BusinessException notFound() {
        return new BusinessException("DATA_NOT_FOUND", "数据不存在或无权访问", HttpStatus.NOT_FOUND);
    }

    private BusinessException conflict() {
        return new BusinessException("OPTIMISTIC_LOCK_CONFLICT", "数据已被其他操作更新，请刷新后重试", HttpStatus.CONFLICT);
    }
}
