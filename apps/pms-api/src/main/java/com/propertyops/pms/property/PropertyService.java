package com.propertyops.pms.property;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.propertyops.pms.common.api.BusinessException;
import com.propertyops.pms.common.audit.AuditService;
import com.propertyops.pms.common.data.PageResponse;
import com.propertyops.pms.security.SecurityContextService;

@Service
public class PropertyService {
    private static final Set<String> ASSET_TYPES = Set.of("ROOM", "PARKING", "PUBLIC_AREA");
    private static final Set<String> IMPORT_RESOURCES = Set.of("GRID", "BUILDING", "UNIT", "ASSET", "CUSTOMER", "RELATION");

    private final NamedParameterJdbcTemplate jdbc;
    private final SecurityContextService security;
    private final AuditService audit;
    private final ObjectMapper objectMapper;

    public PropertyService(NamedParameterJdbcTemplate jdbc, SecurityContextService security,
                           AuditService audit, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.security = security;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    public PropertyModels.AssetTreeResponse tree(String communityId, String assetType, String keyword) {
        requireRead(communityId);
        String safeType = optionalAssetType(assetType);
        var params = new MapSqlParameterSource("communityId", communityId);
        String assetFilter = "";
        if (safeType != null) {
            assetFilter += " AND asset_type=:assetType";
            params.addValue("assetType", safeType);
        }
        if (hasText(keyword)) {
            assetFilter += " AND (code LIKE :keyword ESCAPE '\\\\' OR display_name LIKE :keyword ESCAPE '\\\\')";
            params.addValue("keyword", like(keyword));
        }
        List<PropertyModels.GridNode> grids = jdbc.query("""
                SELECT id, parent_id, code, name, status
                FROM grid_area WHERE community_id=:communityId
                ORDER BY sort_order, code
                """, params, (rs, row) -> new PropertyModels.GridNode(
                rs.getString("id"), rs.getString("parent_id"), rs.getString("code"),
                rs.getString("name"), rs.getString("status")));
        List<PropertyModels.BuildingNode> buildings = jdbc.query("""
                SELECT id, grid_id, code, name, status
                FROM building WHERE community_id=:communityId
                ORDER BY code
                """, params, (rs, row) -> new PropertyModels.BuildingNode(
                rs.getString("id"), rs.getString("grid_id"), rs.getString("code"),
                rs.getString("name"), rs.getString("status")));
        List<PropertyModels.UnitNode> units = jdbc.query("""
                SELECT id, building_id, code, name, status
                FROM pms_unit WHERE community_id=:communityId
                ORDER BY code
                """, params, (rs, row) -> new PropertyModels.UnitNode(
                rs.getString("id"), rs.getString("building_id"), rs.getString("code"),
                rs.getString("name"), rs.getString("status")));
        List<PropertyModels.AssetNode> assets = jdbc.query("""
                SELECT id, grid_id, building_id, unit_id, asset_type, code, display_name,
                       occupancy_status, operation_status, enabled
                FROM asset WHERE community_id=:communityId
                """ + assetFilter + " ORDER BY asset_type, code", params, (rs, row) -> new PropertyModels.AssetNode(
                rs.getString("id"), rs.getString("grid_id"), rs.getString("building_id"),
                rs.getString("unit_id"), rs.getString("asset_type"), rs.getString("code"),
                rs.getString("display_name"), rs.getString("occupancy_status"),
                rs.getString("operation_status"), rs.getBoolean("enabled")));
        return new PropertyModels.AssetTreeResponse(communityId, grids, buildings, units, assets, assets.size());
    }

    public PageResponse<PropertyModels.AssetListItem> assets(String communityId, String assetType,
                                                              String keyword, int page, int size) {
        requireRead(communityId);
        int safePage = Math.max(1, page);
        int safeSize = Math.min(200, Math.max(1, size));
        var params = new MapSqlParameterSource("communityId", communityId);
        List<String> predicates = new ArrayList<>(List.of("a.community_id=:communityId"));
        String safeType = optionalAssetType(assetType);
        if (safeType != null) {
            predicates.add("a.asset_type=:assetType");
            params.addValue("assetType", safeType);
        }
        if (hasText(keyword)) {
            predicates.add("(a.code LIKE :keyword ESCAPE '\\\\' OR a.display_name LIKE :keyword ESCAPE '\\\\' "
                    + "OR b.name LIKE :keyword ESCAPE '\\\\' OR u.name LIKE :keyword ESCAPE '\\\\')");
            params.addValue("keyword", like(keyword));
        }
        String where = " WHERE " + String.join(" AND ", predicates);
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM asset a
                LEFT JOIN building b ON b.id=a.building_id
                LEFT JOIN pms_unit u ON u.id=a.unit_id
                """ + where, params, Long.class);
        params.addValue("limit", safeSize).addValue("offset", (safePage - 1) * safeSize);
        List<PropertyModels.AssetListItem> items = jdbc.query("""
                SELECT a.id, a.asset_type, a.code, a.display_name, g.name grid_name,
                       b.name building_name, u.name unit_name, a.floor_no, a.building_area,
                       a.usable_area, a.occupancy_status, a.operation_status, a.enabled, a.version,
                       (SELECT c.display_name
                          FROM customer_asset_relation r JOIN customer c ON c.id=r.customer_id
                         WHERE r.asset_id=a.id AND r.status='ACTIVE' AND r.end_date IS NULL
                         ORDER BY r.primary_relation DESC,
                                  CASE r.relation_type WHEN 'OWNER' THEN 1 WHEN 'CO_OWNER' THEN 2 ELSE 3 END,
                                  r.created_at LIMIT 1) primary_customer_name,
                       (SELECT COUNT(*) FROM customer_asset_relation r
                         WHERE r.asset_id=a.id AND r.status='ACTIVE' AND r.end_date IS NULL) active_relation_count
                FROM asset a
                LEFT JOIN grid_area g ON g.id=a.grid_id
                LEFT JOIN building b ON b.id=a.building_id
                LEFT JOIN pms_unit u ON u.id=a.unit_id
                """ + where + " ORDER BY a.asset_type, a.code LIMIT :limit OFFSET :offset", params,
                (rs, row) -> new PropertyModels.AssetListItem(
                        rs.getString("id"), rs.getString("asset_type"), rs.getString("code"),
                        rs.getString("display_name"), rs.getString("grid_name"), rs.getString("building_name"),
                        rs.getString("unit_name"), rs.getString("floor_no"), rs.getBigDecimal("building_area"),
                        rs.getBigDecimal("usable_area"), rs.getString("occupancy_status"),
                        rs.getString("operation_status"), rs.getBoolean("enabled"),
                        rs.getString("primary_customer_name"), rs.getInt("active_relation_count"),
                        rs.getLong("version")));
        return new PageResponse<>(items, safePage, safeSize, total == null ? 0 : total);
    }

    public PropertyModels.AssetProfile assetProfile(String assetId, String communityId) {
        requireRead(communityId);
        PropertyModels.AssetDetail asset = single(jdbc.query("""
                SELECT a.*, g.name grid_name, b.name building_name, u.name unit_name
                FROM asset a
                LEFT JOIN grid_area g ON g.id=a.grid_id
                LEFT JOIN building b ON b.id=a.building_id
                LEFT JOIN pms_unit u ON u.id=a.unit_id
                WHERE a.id=:id AND a.community_id=:communityId
                """, Map.of("id", assetId, "communityId", communityId), this::assetDetail), "资产不存在或无权访问");
        Map<String, Object> typeDetail = typeDetail(asset);
        return new PropertyModels.AssetProfile(asset, typeDetail, relationsForAsset(assetId, communityId),
                vehiclesForAsset(assetId, communityId), metersForAsset(assetId, communityId),
                eventsForAsset(assetId, communityId));
    }

    public PageResponse<PropertyModels.CustomerListItem> customers(String communityId, String keyword,
                                                                    String customerType, int page, int size) {
        requireRead(communityId);
        int safePage = Math.max(1, page);
        int safeSize = Math.min(200, Math.max(1, size));
        var params = new MapSqlParameterSource("communityId", communityId);
        List<String> predicates = new ArrayList<>(List.of("c.community_id=:communityId"));
        if (hasText(keyword)) {
            predicates.add("(c.customer_no LIKE :keyword ESCAPE '\\\\' OR c.display_name LIKE :keyword ESCAPE '\\\\' "
                    + "OR c.mobile_masked LIKE :keyword ESCAPE '\\\\')");
            params.addValue("keyword", like(keyword));
        }
        if (hasText(customerType)) {
            String type = customerType.trim().toUpperCase();
            if (!Set.of("PERSON", "ORGANIZATION").contains(type)) throw invalid("客户类型无效");
            predicates.add("c.customer_type=:customerType");
            params.addValue("customerType", type);
        }
        String where = " WHERE " + String.join(" AND ", predicates);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM customer c" + where, params, Long.class);
        params.addValue("limit", safeSize).addValue("offset", (safePage - 1) * safeSize);
        List<PropertyModels.CustomerListItem> items = jdbc.query("""
                SELECT c.id, c.customer_no, c.display_name, c.customer_type, c.customer_class,
                       c.mobile_masked, c.status, c.version,
                       (SELECT COUNT(DISTINCT r.asset_id) FROM customer_asset_relation r
                         WHERE r.customer_id=c.id AND r.status='ACTIVE' AND r.end_date IS NULL) active_asset_count,
                       (SELECT COUNT(DISTINCT vpr.vehicle_id) FROM vehicle_parking_relation vpr
                         WHERE vpr.customer_id=c.id AND vpr.status='ACTIVE' AND vpr.end_date IS NULL) active_vehicle_count
                FROM customer c
                """ + where + " ORDER BY c.display_name, c.customer_no LIMIT :limit OFFSET :offset", params,
                (rs, row) -> new PropertyModels.CustomerListItem(
                        rs.getString("id"), rs.getString("customer_no"), rs.getString("display_name"),
                        rs.getString("customer_type"), rs.getString("customer_class"),
                        rs.getString("mobile_masked"), rs.getString("status"),
                        rs.getInt("active_asset_count"), rs.getInt("active_vehicle_count"), rs.getLong("version")));
        return new PageResponse<>(items, safePage, safeSize, total == null ? 0 : total);
    }

    public PropertyModels.CustomerProfile customerProfile(String customerId, String communityId) {
        requireRead(communityId);
        PropertyModels.CustomerDetail customer = single(jdbc.query("""
                SELECT * FROM customer WHERE id=:id AND community_id=:communityId
                """, Map.of("id", customerId, "communityId", communityId), this::customerDetail), "客户不存在或无权访问");
        return new PropertyModels.CustomerProfile(customer, relationsForCustomer(customerId, communityId),
                vehiclesForCustomer(customerId, communityId), eventsForCustomer(customerId, communityId));
    }

    @Transactional
    public PropertyModels.RelationCommandResult startRelation(PropertyModels.StartRelationRequest request,
                                                               String idempotencyKey) {
        requireWrite(request.communityId());
        String requestKey = requireIdempotencyKey(idempotencyKey);
        PropertyModels.RelationCommandResult replay = replay(request.communityId(), requestKey);
        if (replay != null) return replay;
        requireActiveAsset(request.assetId(), request.communityId(), true);
        requireActiveCustomer(request.customerId(), request.communityId());
        assertNoOpenOverlap(request.communityId(), request.customerId(), request.assetId(),
                request.relationType(), request.startDate());
        if (request.primaryRelation()) assertNoPrimaryRelation(request.communityId(), request.assetId());

        String relationId = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();
        LocalDateTime now = now();
        var params = new MapSqlParameterSource()
                .addValue("id", relationId).addValue("communityId", request.communityId())
                .addValue("customerId", request.customerId()).addValue("assetId", request.assetId())
                .addValue("relationType", request.relationType()).addValue("primary", request.primaryRelation())
                .addValue("startDate", request.startDate()).addValue("reason", request.reason()).addValue("now", now);
        jdbc.update("""
                INSERT INTO customer_asset_relation
                    (id, community_id, customer_id, asset_id, relation_type, primary_relation,
                     start_date, end_date, change_reason, status, version, created_at, updated_at)
                VALUES (:id, :communityId, :customerId, :assetId, :relationType, :primary,
                        :startDate, NULL, :reason, 'ACTIVE', 0, :now, :now)
                """, params);
        insertEvent(eventId, request.communityId(), request.assetId(), request.customerId(),
                "RELATION_STARTED", request.relationType(), request.startDate(), null, relationId,
                request.reason(), requestKey, Map.of("relationId", relationId, "primary", request.primaryRelation()), now);
        audit.success(request.communityId(), "property-relation:start", "customer-asset-relation", relationId,
                Map.of("assetId", request.assetId(), "customerId", request.customerId(),
                        "relationType", request.relationType()));
        return new PropertyModels.RelationCommandResult(eventId, relationId, request.assetId(),
                request.customerId(), "ACTIVE", false);
    }

    @Transactional
    public PropertyModels.RelationCommandResult endRelation(String relationId,
                                                             PropertyModels.EndRelationRequest request,
                                                             String idempotencyKey) {
        requireWrite(request.communityId());
        String requestKey = requireIdempotencyKey(idempotencyKey);
        PropertyModels.RelationCommandResult replay = replay(request.communityId(), requestKey);
        if (replay != null) return replay;
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT * FROM customer_asset_relation
                WHERE id=:id AND community_id=:communityId FOR UPDATE
                """, Map.of("id", relationId, "communityId", request.communityId()));
        if (rows.isEmpty()) throw notFound("客户资产关系不存在或无权访问");
        Map<String, Object> relation = rows.get(0);
        if (!"ACTIVE".equals(String.valueOf(relation.get("status"))) || relation.get("end_date") != null) {
            throw conflict("RELATION_ALREADY_ENDED", "该客户资产关系已经结束");
        }
        long version = ((Number) relation.get("version")).longValue();
        if (version != request.expectedVersion()) throw optimisticConflict();
        LocalDate start = toDate(relation.get("start_date"));
        if (request.effectiveDate().isBefore(start)) throw invalid("关系结束日期不能早于开始日期");
        LocalDateTime now = now();
        int changed = jdbc.update("""
                UPDATE customer_asset_relation
                SET end_date=:endDate, change_reason=:reason, status='ENDED', version=version+1, updated_at=:now
                WHERE id=:id AND community_id=:communityId AND version=:version AND status='ACTIVE'
                """, new MapSqlParameterSource("endDate", request.effectiveDate())
                .addValue("reason", request.reason()).addValue("now", now).addValue("id", relationId)
                .addValue("communityId", request.communityId()).addValue("version", request.expectedVersion()));
        if (changed != 1) throw optimisticConflict();
        String eventId = UUID.randomUUID().toString();
        String assetId = String.valueOf(relation.get("asset_id"));
        String customerId = String.valueOf(relation.get("customer_id"));
        String relationType = String.valueOf(relation.get("relation_type"));
        insertEvent(eventId, request.communityId(), assetId, customerId, "RELATION_ENDED", relationType,
                request.effectiveDate(), relationId, null, request.reason(), requestKey,
                Map.of("relationId", relationId, "endedVersion", version + 1), now);
        audit.success(request.communityId(), "property-relation:end", "customer-asset-relation", relationId,
                Map.of("effectiveDate", request.effectiveDate(), "reason", request.reason()));
        return new PropertyModels.RelationCommandResult(eventId, relationId, assetId, customerId, "ENDED", false);
    }

    @Transactional
    public PropertyModels.RelationCommandResult transferOwnership(String assetId,
                                                                   PropertyModels.TransferOwnershipRequest request,
                                                                   String idempotencyKey) {
        requireWrite(request.communityId());
        String requestKey = requireIdempotencyKey(idempotencyKey);
        PropertyModels.RelationCommandResult replay = replay(request.communityId(), requestKey);
        if (replay != null) return replay;
        Map<String, Object> asset = requireActiveAsset(assetId, request.communityId(), true);
        long assetVersion = ((Number) asset.get("version")).longValue();
        if (assetVersion != request.expectedAssetVersion()) throw optimisticConflict();
        requireActiveCustomer(request.newOwnerCustomerId(), request.communityId());
        List<Map<String, Object>> previous = jdbc.queryForList("""
                SELECT id, customer_id, relation_type, start_date, version
                FROM customer_asset_relation
                WHERE community_id=:communityId AND asset_id=:assetId
                  AND relation_type IN ('OWNER', 'CO_OWNER') AND status='ACTIVE' AND end_date IS NULL
                ORDER BY primary_relation DESC, created_at FOR UPDATE
                """, Map.of("communityId", request.communityId(), "assetId", assetId));
        if (previous.isEmpty()) throw conflict("ACTIVE_OWNER_REQUIRED", "资产没有可转移的有效产权关系");
        LocalDate previousEnd = request.effectiveDate().minusDays(1);
        for (Map<String, Object> row : previous) {
            if (request.effectiveDate().compareTo(toDate(row.get("start_date"))) <= 0) {
                throw invalid("产权变更生效日必须晚于现有产权关系开始日");
            }
        }
        LocalDateTime now = now();
        jdbc.update("""
                UPDATE customer_asset_relation
                SET end_date=:endDate, change_reason=:reason, status='ENDED', version=version+1, updated_at=:now
                WHERE community_id=:communityId AND asset_id=:assetId
                  AND relation_type IN ('OWNER', 'CO_OWNER') AND status='ACTIVE' AND end_date IS NULL
                """, new MapSqlParameterSource("endDate", previousEnd).addValue("reason", request.reason())
                .addValue("now", now).addValue("communityId", request.communityId()).addValue("assetId", assetId));
        assertNoOpenOverlap(request.communityId(), request.newOwnerCustomerId(), assetId, "OWNER", request.effectiveDate());
        String relationId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO customer_asset_relation
                    (id, community_id, customer_id, asset_id, relation_type, primary_relation,
                     start_date, end_date, change_reason, status, version, created_at, updated_at)
                VALUES (:id, :communityId, :customerId, :assetId, 'OWNER', TRUE,
                        :startDate, NULL, :reason, 'ACTIVE', 0, :now, :now)
                """, new MapSqlParameterSource("id", relationId).addValue("communityId", request.communityId())
                .addValue("customerId", request.newOwnerCustomerId()).addValue("assetId", assetId)
                .addValue("startDate", request.effectiveDate()).addValue("reason", request.reason()).addValue("now", now));
        int changed = jdbc.update("""
                UPDATE asset SET version=version+1, updated_at=:now
                WHERE id=:id AND community_id=:communityId AND version=:version
                """, Map.of("now", now, "id", assetId, "communityId", request.communityId(), "version", assetVersion));
        if (changed != 1) throw optimisticConflict();
        String eventId = UUID.randomUUID().toString();
        List<String> previousIds = previous.stream().map(row -> String.valueOf(row.get("id"))).toList();
        insertEvent(eventId, request.communityId(), assetId, request.newOwnerCustomerId(),
                "OWNERSHIP_TRANSFERRED", "OWNER", request.effectiveDate(), previousIds.get(0), relationId,
                request.reason(), requestKey, Map.of("previousRelationIds", previousIds,
                        "newRelationId", relationId, "previousEndDate", previousEnd), now);
        audit.success(request.communityId(), "property-ownership:transfer", "asset", assetId,
                Map.of("previousRelationIds", previousIds, "newRelationId", relationId,
                        "newOwnerCustomerId", request.newOwnerCustomerId(), "effectiveDate", request.effectiveDate()));
        return new PropertyModels.RelationCommandResult(eventId, relationId, assetId,
                request.newOwnerCustomerId(), "ACTIVE", false);
    }

    public String normalizedResource(String resource) {
        String normalized = resource == null ? "" : resource.trim().toUpperCase();
        if (!IMPORT_RESOURCES.contains(normalized)) throw invalid("不支持的导入资源类型");
        return normalized;
    }

    public String importTemplate(String resource) {
        security.requirePermission("property:write");
        return switch (normalizedResource(resource)) {
            case "GRID" -> csv("code,name,parentId,managerUserId,sortOrder,status", "GRID-001,示例网格,,,10,ACTIVE");
            case "BUILDING" -> csv("code,name,gridId,buildingType,floorCount,status", "B001,示例楼栋,,RESIDENTIAL,18,ACTIVE");
            case "UNIT" -> csv("code,name,buildingId,status", "U01,示例单元,请填写同项目楼栋ID,ACTIVE");
            case "ASSET" -> csv("assetType,code,displayName,gridId,buildingId,unitId,floorNo,buildingArea,usableArea,occupancyStatus,operationStatus",
                    "ROOM,R001,示例房屋,,请填写同项目楼栋ID,请填写同项目单元ID,1,88.00,70.00,VACANT,NORMAL");
            case "CUSTOMER" -> csv("customerNo,displayName,customerType,customerClass,mobileMasked,gender,status",
                    "C001,示例客户,PERSON,OWNER,138****0000,UNKNOWN,ACTIVE");
            case "RELATION" -> csv("customerId,assetId,relationType,primaryRelation,startDate,reason",
                    "请填写同项目客户ID,请填写同项目资产ID,OWNER,true,2026-01-01,初始关系");
            default -> throw invalid("不支持的导入资源类型");
        };
    }

    public PropertyModels.ImportValidationReport validateImport(PropertyModels.ImportValidationRequest request) {
        requireWrite(request.communityId());
        String resource = normalizedResource(request.resource());
        List<PropertyModels.ImportRowResult> results = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        int valid = 0;
        for (int index = 0; index < request.rows().size(); index++) {
            Map<String, Object> row = request.rows().get(index);
            List<String> errors = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            validateRow(resource, request.communityId(), row, errors, warnings);
            String uniqueKey = importKey(resource, row);
            if (hasText(uniqueKey) && !keys.add(uniqueKey.toUpperCase())) errors.add("文件内业务键重复: " + uniqueKey);
            boolean rowValid = errors.isEmpty();
            if (rowValid) valid++;
            results.add(new PropertyModels.ImportRowResult(index + 2, rowValid, List.copyOf(errors), List.copyOf(warnings)));
        }
        int invalidRows = results.size() - valid;
        return new PropertyModels.ImportValidationReport(resource, results.size(), valid, invalidRows,
                invalidRows == 0, List.copyOf(results));
    }

    private void validateRow(String resource, String communityId, Map<String, Object> row,
                             List<String> errors, List<String> warnings) {
        switch (resource) {
            case "GRID" -> {
                required(row, errors, "code", "name");
                status(row, errors);
                reference("grid_area", text(row, "parentId"), communityId, "parentId", errors);
                duplicate("grid_area", "code", text(row, "code"), communityId, errors);
                integerAtLeast(row, "sortOrder", 0, errors);
            }
            case "BUILDING" -> {
                required(row, errors, "code", "name");
                status(row, errors);
                reference("grid_area", text(row, "gridId"), communityId, "gridId", errors);
                duplicate("building", "code", text(row, "code"), communityId, errors);
                integerAtLeast(row, "floorCount", 0, errors);
            }
            case "UNIT" -> {
                required(row, errors, "code", "name", "buildingId");
                status(row, errors);
                reference("building", text(row, "buildingId"), communityId, "buildingId", errors);
                if (hasText(text(row, "code")) && hasText(text(row, "buildingId")) && exists("""
                        SELECT COUNT(*) FROM pms_unit
                        WHERE community_id=:communityId AND building_id=:parentId AND code=:value
                        """, Map.of("communityId", communityId, "parentId", text(row, "buildingId"),
                        "value", text(row, "code")))) errors.add("同一楼栋下单元编码已存在");
            }
            case "ASSET" -> validateAssetRow(communityId, row, errors, warnings);
            case "CUSTOMER" -> {
                required(row, errors, "customerNo", "displayName", "customerType");
                status(row, errors);
                if (hasText(text(row, "customerType"))
                        && !Set.of("PERSON", "ORGANIZATION").contains(text(row, "customerType").toUpperCase())) {
                    errors.add("customerType 必须为 PERSON 或 ORGANIZATION");
                }
                duplicate("customer", "customer_no", text(row, "customerNo"), communityId, errors);
            }
            case "RELATION" -> {
                required(row, errors, "customerId", "assetId", "relationType", "startDate");
                reference("customer", text(row, "customerId"), communityId, "customerId", errors);
                reference("asset", text(row, "assetId"), communityId, "assetId", errors);
                String relationType = text(row, "relationType");
                if (hasText(relationType) && !Set.of("OWNER", "CO_OWNER", "TENANT", "OCCUPANT")
                        .contains(relationType.toUpperCase())) errors.add("relationType 无效");
                LocalDate start = date(row, "startDate", errors);
                if (start != null && hasText(text(row, "customerId")) && hasText(text(row, "assetId"))
                        && hasText(relationType) && exists("""
                        SELECT COUNT(*) FROM customer_asset_relation
                        WHERE community_id=:communityId AND customer_id=:customerId AND asset_id=:assetId
                          AND relation_type=:relationType AND (end_date IS NULL OR end_date>=:startDate)
                        """, Map.of("communityId", communityId, "customerId", text(row, "customerId"),
                        "assetId", text(row, "assetId"), "relationType", relationType.toUpperCase(), "startDate", start))) {
                    errors.add("关系有效期与现有记录重叠");
                }
            }
            default -> errors.add("不支持的导入资源类型");
        }
    }

    private void validateAssetRow(String communityId, Map<String, Object> row,
                                  List<String> errors, List<String> warnings) {
        required(row, errors, "assetType", "code", "displayName", "buildingArea", "usableArea");
        String type = text(row, "assetType");
        if (hasText(type) && !ASSET_TYPES.contains(type.toUpperCase())) errors.add("assetType 无效");
        String buildingId = text(row, "buildingId");
        String unitId = text(row, "unitId");
        reference("grid_area", text(row, "gridId"), communityId, "gridId", errors);
        reference("building", buildingId, communityId, "buildingId", errors);
        if (hasText(unitId)) {
            if (!hasText(buildingId)) errors.add("填写 unitId 时必须同时填写 buildingId");
            else if (!exists("""
                    SELECT COUNT(*) FROM pms_unit
                    WHERE id=:id AND building_id=:buildingId AND community_id=:communityId
                    """, Map.of("id", unitId, "buildingId", buildingId, "communityId", communityId))) {
                errors.add("unitId 不存在、楼栋不匹配或不属于当前项目");
            }
        }
        if ("ROOM".equalsIgnoreCase(type) && !hasText(buildingId)) errors.add("房屋资产必须填写 buildingId");
        BigDecimal buildingArea = decimal(row, "buildingArea", errors);
        BigDecimal usableArea = decimal(row, "usableArea", errors);
        if (buildingArea != null && buildingArea.signum() < 0) errors.add("buildingArea 不能小于 0");
        if (usableArea != null && usableArea.signum() < 0) errors.add("usableArea 不能小于 0");
        if (buildingArea != null && usableArea != null && usableArea.compareTo(buildingArea) > 0) {
            errors.add("usableArea 不能大于 buildingArea");
        }
        duplicate("asset", "code", text(row, "code"), communityId, errors);
        if (!hasText(text(row, "occupancyStatus"))) warnings.add("occupancyStatus 为空，将使用导入默认值");
    }

    private PropertyModels.AssetDetail assetDetail(ResultSet rs, int row) throws SQLException {
        return new PropertyModels.AssetDetail(rs.getString("id"), rs.getString("community_id"),
                rs.getString("grid_id"), rs.getString("building_id"), rs.getString("unit_id"),
                rs.getString("asset_type"), rs.getString("code"), rs.getString("display_name"),
                rs.getString("floor_no"), rs.getBigDecimal("building_area"), rs.getBigDecimal("usable_area"),
                rs.getString("occupancy_status"), rs.getString("operation_status"), rs.getBoolean("enabled"),
                date(rs, "valid_from"), date(rs, "valid_to"), rs.getString("grid_name"),
                rs.getString("building_name"), rs.getString("unit_name"), rs.getLong("version"),
                rs.getObject("created_at", LocalDateTime.class), rs.getObject("updated_at", LocalDateTime.class));
    }

    private PropertyModels.CustomerDetail customerDetail(ResultSet rs, int row) throws SQLException {
        return new PropertyModels.CustomerDetail(rs.getString("id"), rs.getString("community_id"),
                rs.getString("customer_no"), rs.getString("display_name"), rs.getString("customer_type"),
                rs.getString("customer_class"), rs.getString("mobile_masked"), rs.getString("certificate_type"),
                rs.getString("certificate_masked"), rs.getString("gender"), date(rs, "birthday"),
                rs.getString("remarks"), rs.getString("status"), rs.getLong("version"),
                rs.getObject("created_at", LocalDateTime.class), rs.getObject("updated_at", LocalDateTime.class));
    }

    private List<PropertyModels.RelationItem> relationsForAsset(String assetId, String communityId) {
        return jdbc.query(relationSql() + " WHERE r.community_id=:communityId AND r.asset_id=:id "
                        + "ORDER BY r.start_date DESC, r.created_at DESC",
                Map.of("communityId", communityId, "id", assetId), this::relationItem);
    }

    private List<PropertyModels.RelationItem> relationsForCustomer(String customerId, String communityId) {
        return jdbc.query(relationSql() + " WHERE r.community_id=:communityId AND r.customer_id=:id "
                        + "ORDER BY r.start_date DESC, r.created_at DESC",
                Map.of("communityId", communityId, "id", customerId), this::relationItem);
    }

    private String relationSql() {
        return """
                SELECT r.*, c.display_name customer_name, a.display_name asset_name
                FROM customer_asset_relation r
                JOIN customer c ON c.id=r.customer_id
                JOIN asset a ON a.id=r.asset_id
                """;
    }

    private PropertyModels.RelationItem relationItem(ResultSet rs, int row) throws SQLException {
        return new PropertyModels.RelationItem(rs.getString("id"), rs.getString("customer_id"),
                rs.getString("customer_name"), rs.getString("asset_id"), rs.getString("asset_name"),
                rs.getString("relation_type"), rs.getBoolean("primary_relation"), date(rs, "start_date"),
                date(rs, "end_date"), rs.getString("change_reason"), rs.getString("status"), rs.getLong("version"));
    }

    private List<PropertyModels.VehicleItem> vehiclesForAsset(String assetId, String communityId) {
        return jdbc.query("""
                SELECT v.id, v.plate_no_masked, v.vehicle_type, v.color, v.status,
                       p.id parking_asset_id, p.display_name parking_name, r.start_date, r.end_date
                FROM vehicle_parking_relation r
                JOIN vehicle v ON v.id=r.vehicle_id
                JOIN asset p ON p.id=r.parking_asset_id
                LEFT JOIN parking_space_detail ps ON ps.asset_id=p.id
                WHERE r.community_id=:communityId AND (p.id=:id OR ps.related_room_asset_id=:id)
                ORDER BY r.status='ACTIVE' DESC, r.start_date DESC
                """, Map.of("communityId", communityId, "id", assetId), this::vehicleItem);
    }

    private List<PropertyModels.VehicleItem> vehiclesForCustomer(String customerId, String communityId) {
        return jdbc.query("""
                SELECT v.id, v.plate_no_masked, v.vehicle_type, v.color, v.status,
                       p.id parking_asset_id, p.display_name parking_name, r.start_date, r.end_date
                FROM vehicle_parking_relation r
                JOIN vehicle v ON v.id=r.vehicle_id
                JOIN asset p ON p.id=r.parking_asset_id
                WHERE r.community_id=:communityId AND r.customer_id=:id
                ORDER BY r.status='ACTIVE' DESC, r.start_date DESC
                """, Map.of("communityId", communityId, "id", customerId), this::vehicleItem);
    }

    private PropertyModels.VehicleItem vehicleItem(ResultSet rs, int row) throws SQLException {
        return new PropertyModels.VehicleItem(rs.getString("id"), rs.getString("plate_no_masked"),
                rs.getString("vehicle_type"), rs.getString("color"), rs.getString("status"),
                rs.getString("parking_asset_id"), rs.getString("parking_name"),
                date(rs, "start_date"), date(rs, "end_date"));
    }

    private List<PropertyModels.MeterItem> metersForAsset(String assetId, String communityId) {
        return jdbc.query("""
                SELECT id, meter_no, meter_type, meter_class, status
                FROM meter WHERE community_id=:communityId AND asset_id=:id ORDER BY meter_no
                """, Map.of("communityId", communityId, "id", assetId),
                (rs, row) -> new PropertyModels.MeterItem(rs.getString("id"), rs.getString("meter_no"),
                        rs.getString("meter_type"), rs.getString("meter_class"), rs.getString("status")));
    }

    private List<PropertyModels.PropertyEventItem> eventsForAsset(String assetId, String communityId) {
        return events("e.asset_id=:id", assetId, communityId);
    }

    private List<PropertyModels.PropertyEventItem> eventsForCustomer(String customerId, String communityId) {
        return events("e.customer_id=:id", customerId, communityId);
    }

    private List<PropertyModels.PropertyEventItem> events(String predicate, String id, String communityId) {
        return jdbc.query("""
                SELECT e.*, c.display_name customer_name
                FROM property_relation_event e
                LEFT JOIN customer c ON c.id=e.customer_id
                WHERE e.community_id=:communityId AND
                """ + predicate + " ORDER BY e.effective_date DESC, e.created_at DESC",
                Map.of("communityId", communityId, "id", id),
                (rs, row) -> new PropertyModels.PropertyEventItem(
                        rs.getString("id"), rs.getString("event_type"), rs.getString("relation_type"),
                        date(rs, "effective_date"), rs.getString("customer_id"), rs.getString("customer_name"),
                        rs.getString("reason"), rs.getString("previous_relation_id"), rs.getString("new_relation_id"),
                        rs.getObject("created_at", LocalDateTime.class)));
    }

    private Map<String, Object> typeDetail(PropertyModels.AssetDetail asset) {
        String sql = switch (asset.assetType()) {
            case "ROOM" -> "SELECT room_type, delivery_date FROM room_detail WHERE asset_id=:id";
            case "PARKING" -> "SELECT parking_type, ownership_type, related_room_asset_id FROM parking_space_detail WHERE asset_id=:id";
            default -> null;
        };
        if (sql == null) return Map.of();
        List<Map<String, Object>> rows = jdbc.queryForList(sql, Map.of("id", asset.id()));
        return rows.isEmpty() ? Map.of() : new LinkedHashMap<>(rows.get(0));
    }

    private Map<String, Object> requireActiveAsset(String assetId, String communityId, boolean lock) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, asset_type, version FROM asset
                WHERE id=:id AND community_id=:communityId AND enabled=TRUE
                """ + (lock ? " FOR UPDATE" : ""), Map.of("id", assetId, "communityId", communityId));
        if (rows.isEmpty()) throw notFound("资产不存在、已停用或不属于当前项目");
        return rows.get(0);
    }

    private void requireActiveCustomer(String customerId, String communityId) {
        if (!exists("SELECT COUNT(*) FROM customer WHERE id=:id AND community_id=:communityId AND status='ACTIVE'",
                Map.of("id", customerId, "communityId", communityId))) {
            throw notFound("客户不存在、已停用或不属于当前项目");
        }
    }

    private void assertNoOpenOverlap(String communityId, String customerId, String assetId,
                                     String relationType, LocalDate startDate) {
        if (exists("""
                SELECT COUNT(*) FROM customer_asset_relation
                WHERE community_id=:communityId AND customer_id=:customerId AND asset_id=:assetId
                  AND relation_type=:relationType AND (end_date IS NULL OR end_date>=:startDate)
                """, Map.of("communityId", communityId, "customerId", customerId, "assetId", assetId,
                "relationType", relationType, "startDate", startDate))) {
            throw conflict("RELATION_PERIOD_OVERLAP", "客户资产关系有效期与现有记录重叠");
        }
    }

    private void assertNoPrimaryRelation(String communityId, String assetId) {
        if (exists("""
                SELECT COUNT(*) FROM customer_asset_relation
                WHERE community_id=:communityId AND asset_id=:assetId AND primary_relation=TRUE
                  AND status='ACTIVE' AND end_date IS NULL
                """, Map.of("communityId", communityId, "assetId", assetId))) {
            throw conflict("PRIMARY_RELATION_EXISTS", "资产已经存在有效主关系");
        }
    }

    private void insertEvent(String eventId, String communityId, String assetId, String customerId,
                             String eventType, String relationType, LocalDate effectiveDate,
                             String previousRelationId, String newRelationId, String reason,
                             String requestKey, Object snapshot, LocalDateTime now) {
        var params = new MapSqlParameterSource()
                .addValue("id", eventId).addValue("communityId", communityId).addValue("assetId", assetId)
                .addValue("customerId", customerId).addValue("eventType", eventType)
                .addValue("relationType", relationType).addValue("effectiveDate", effectiveDate)
                .addValue("previousRelationId", previousRelationId).addValue("newRelationId", newRelationId)
                .addValue("reason", reason).addValue("snapshot", json(snapshot)).addValue("requestKey", requestKey)
                .addValue("actor", security.requirePrincipal().userId()).addValue("now", now);
        jdbc.update("""
                INSERT INTO property_relation_event
                    (id, community_id, asset_id, customer_id, event_type, relation_type, effective_date,
                     previous_relation_id, new_relation_id, reason, snapshot_json, request_key, actor_user_id, created_at)
                VALUES (:id, :communityId, :assetId, :customerId, :eventType, :relationType, :effectiveDate,
                        :previousRelationId, :newRelationId, :reason, :snapshot, :requestKey, :actor, :now)
                """, params);
    }

    private PropertyModels.RelationCommandResult replay(String communityId, String requestKey) {
        List<Map<String, Object>> events = jdbc.queryForList("""
                SELECT id, asset_id, customer_id, event_type, previous_relation_id, new_relation_id
                FROM property_relation_event WHERE community_id=:communityId AND request_key=:requestKey
                """, Map.of("communityId", communityId, "requestKey", requestKey));
        if (events.isEmpty()) return null;
        Map<String, Object> event = events.get(0);
        String newId = nullable(event.get("new_relation_id"));
        String previousId = nullable(event.get("previous_relation_id"));
        String status = "RELATION_ENDED".equals(String.valueOf(event.get("event_type"))) ? "ENDED" : "ACTIVE";
        return new PropertyModels.RelationCommandResult(String.valueOf(event.get("id")),
                newId == null ? previousId : newId, String.valueOf(event.get("asset_id")),
                nullable(event.get("customer_id")), status, true);
    }

    private void reference(String table, String id, String communityId, String field, List<String> errors) {
        if (!hasText(id)) return;
        if (!exists("SELECT COUNT(*) FROM " + table + " WHERE id=:id AND community_id=:communityId",
                Map.of("id", id, "communityId", communityId))) {
            errors.add(field + " 不存在或不属于当前项目");
        }
    }

    private void duplicate(String table, String column, String value, String communityId, List<String> errors) {
        if (!hasText(value)) return;
        if (exists("SELECT COUNT(*) FROM " + table + " WHERE community_id=:communityId AND " + column + "=:value",
                Map.of("communityId", communityId, "value", value))) {
            errors.add(column + " 已存在");
        }
    }

    private void required(Map<String, Object> row, List<String> errors, String... fields) {
        for (String field : fields) if (!hasText(text(row, field))) errors.add(field + " 为必填项");
    }

    private void status(Map<String, Object> row, List<String> errors) {
        String status = text(row, "status");
        if (hasText(status) && !Set.of("ACTIVE", "INACTIVE").contains(status.toUpperCase())) {
            errors.add("status 必须为 ACTIVE 或 INACTIVE");
        }
    }

    private void integerAtLeast(Map<String, Object> row, String field, int minimum, List<String> errors) {
        if (!hasText(text(row, field))) return;
        try {
            if (Integer.parseInt(text(row, field)) < minimum) errors.add(field + " 不能小于 " + minimum);
        } catch (NumberFormatException exception) {
            errors.add(field + " 必须为整数");
        }
    }

    private BigDecimal decimal(Map<String, Object> row, String field, List<String> errors) {
        if (!hasText(text(row, field))) return null;
        try {
            return new BigDecimal(text(row, field));
        } catch (NumberFormatException exception) {
            errors.add(field + " 必须为数字");
            return null;
        }
    }

    private LocalDate date(Map<String, Object> row, String field, List<String> errors) {
        if (!hasText(text(row, field))) return null;
        try {
            return LocalDate.parse(text(row, field));
        } catch (RuntimeException exception) {
            errors.add(field + " 必须使用 YYYY-MM-DD 格式");
            return null;
        }
    }

    private String importKey(String resource, Map<String, Object> row) {
        return switch (resource) {
            case "GRID", "BUILDING", "ASSET" -> text(row, "code");
            case "UNIT" -> text(row, "buildingId") + "|" + text(row, "code");
            case "CUSTOMER" -> text(row, "customerNo");
            case "RELATION" -> text(row, "customerId") + "|" + text(row, "assetId") + "|"
                    + text(row, "relationType") + "|" + text(row, "startDate");
            default -> null;
        };
    }

    private boolean exists(String sql, Map<String, ?> params) {
        Long count = jdbc.queryForObject(sql, params, Long.class);
        return count != null && count > 0;
    }

    private void requireRead(String communityId) {
        requireCommunity(communityId);
        security.requirePermission("property:read");
        security.requireProject(communityId);
    }

    private void requireWrite(String communityId) {
        requireCommunity(communityId);
        security.requirePermission("property:write");
        security.requireProject(communityId);
    }

    private void requireCommunity(String communityId) {
        if (!hasText(communityId)) throw invalid("必须选择项目");
    }

    private String optionalAssetType(String assetType) {
        if (!hasText(assetType)) return null;
        String normalized = assetType.trim().toUpperCase();
        if (!ASSET_TYPES.contains(normalized)) throw invalid("资产类型无效");
        return normalized;
    }

    private String requireIdempotencyKey(String value) {
        if (!hasText(value)) {
            throw new BusinessException("IDEMPOTENCY_KEY_REQUIRED", "关系变更必须提供 Idempotency-Key", HttpStatus.BAD_REQUEST);
        }
        String key = value.trim();
        if (key.length() > 120) throw invalid("Idempotency-Key 不能超过 120 个字符");
        return key;
    }

    private String text(Map<String, Object> row, String field) {
        Object value = row.get(field);
        return value == null ? null : String.valueOf(value).trim();
    }

    private String like(String value) {
        return "%" + value.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String csv(String header, String example) {
        return "\uFEFF" + header + "\r\n" + example + "\r\n";
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize property event snapshot", exception);
        }
    }

    private String nullable(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private LocalDate toDate(Object value) {
        if (value instanceof LocalDate date) return date;
        if (value instanceof java.sql.Date date) return date.toLocalDate();
        return LocalDate.parse(String.valueOf(value));
    }

    private LocalDate date(ResultSet rs, String column) throws SQLException {
        java.sql.Date value = rs.getDate(column);
        return value == null ? null : value.toLocalDate();
    }

    private <T> T single(List<T> rows, String message) {
        if (rows.isEmpty()) throw notFound(message);
        return rows.get(0);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private BusinessException invalid(String message) {
        return new BusinessException("INVALID_PROPERTY_REQUEST", message, HttpStatus.BAD_REQUEST);
    }

    private BusinessException notFound(String message) {
        return new BusinessException("PROPERTY_DATA_NOT_FOUND", message, HttpStatus.NOT_FOUND);
    }

    private BusinessException conflict(String code, String message) {
        return new BusinessException(code, message, HttpStatus.CONFLICT);
    }

    private BusinessException optimisticConflict() {
        return conflict("OPTIMISTIC_LOCK_CONFLICT", "数据已被其他操作更新，请刷新后重试");
    }
}
