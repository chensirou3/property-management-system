package com.propertyops.pms.migration;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
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
public class MigrationService {
    private static final Set<String> BATCH_STATUSES = Set.of(
            "UPLOADED", "VALIDATING", "READY", "PARTIAL_FAILED", "APPROVED", "EXECUTING",
            "COMPLETED", "RECONCILED", "ROLLED_BACK", "FAILED");
    private static final List<String> RESOURCE_ORDER = List.of("PROJECT", "BUILDING", "ASSET", "CUSTOMER", "RELATION");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final NamedParameterJdbcTemplate jdbc;
    private final SecurityContextService security;
    private final AuditService audit;
    private final ObjectMapper objectMapper;

    public MigrationService(NamedParameterJdbcTemplate jdbc, SecurityContextService security,
                            AuditService audit, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.security = security;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    public PageResponse<MigrationModels.BatchSummary> batches(String communityId, String status,
                                                               String keyword, int page, int size) {
        requireRead(communityId);
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        var parameters = new MapSqlParameterSource("communityId", communityId);
        List<String> predicates = new ArrayList<>(List.of("community_id=:communityId"));
        if (hasText(status)) {
            String normalized = status.trim().toUpperCase(Locale.ROOT);
            if (!BATCH_STATUSES.contains(normalized)) throw invalid("迁移批次状态无效");
            predicates.add("status=:status");
            parameters.addValue("status", normalized);
        }
        if (hasText(keyword)) {
            predicates.add("(batch_no LIKE :keyword ESCAPE '\\\\' OR source_name LIKE :keyword ESCAPE '\\\\')");
            parameters.addValue("keyword", like(keyword));
        }
        String where = " WHERE " + String.join(" AND ", predicates);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM migration_batch" + where, parameters, Long.class);
        parameters.addValue("limit", safeSize).addValue("offset", (safePage - 1) * safeSize);
        List<MigrationModels.BatchSummary> items = jdbc.query(
                "SELECT * FROM migration_batch" + where + " ORDER BY created_at DESC LIMIT :limit OFFSET :offset",
                parameters, this::summary);
        return new PageResponse<>(items, safePage, safeSize, total == null ? 0 : total);
    }

    public MigrationModels.BatchDetail batch(String batchId, String communityId, boolean replayed) {
        requireRead(communityId);
        BatchRow row = requireBatch(batchId, communityId);
        List<MigrationModels.QuarantineItem> quarantine = jdbc.query("""
                SELECT r.row_no, r.resource_type, r.source_id, q.error_code, q.field_name, q.error_message
                FROM migration_quarantine_record q
                JOIN migration_raw_record r ON r.id=q.raw_record_id
                WHERE q.batch_id=:batchId ORDER BY r.row_no, q.created_at
                """, Map.of("batchId", batchId), (rs, index) -> new MigrationModels.QuarantineItem(
                rs.getInt("row_no"), rs.getString("resource_type"), rs.getString("source_id"),
                rs.getString("error_code"), rs.getString("field_name"), rs.getString("error_message")));
        List<MigrationModels.ObjectMapping> mappings = jdbc.query("""
                SELECT resource_type, source_id, target_id, target_code, active
                FROM migration_object_map WHERE batch_id=:batchId
                ORDER BY FIELD(resource_type, 'PROJECT','BUILDING','ASSET','CUSTOMER','RELATION'), source_id
                """, Map.of("batchId", batchId), (rs, index) -> new MigrationModels.ObjectMapping(
                rs.getString("resource_type"), rs.getString("source_id"), rs.getString("target_id"),
                rs.getString("target_code"), rs.getBoolean("active")));
        List<MigrationModels.ReconciliationItem> reconciliation = jdbc.query("""
                SELECT metric_name, source_value, target_value, difference_value, status, detail_json
                FROM migration_reconciliation WHERE batch_id=:batchId ORDER BY metric_name
                """, Map.of("batchId", batchId), (rs, index) -> new MigrationModels.ReconciliationItem(
                rs.getString("metric_name"), rs.getBigDecimal("source_value"), rs.getBigDecimal("target_value"),
                rs.getBigDecimal("difference_value"), rs.getString("status"), jsonMap(rs.getString("detail_json"))));
        List<MigrationModels.BatchEvent> events = jdbc.query("""
                SELECT event_type, from_status, to_status, detail_json, created_at
                FROM migration_batch_event WHERE batch_id=:batchId ORDER BY created_at, id
                """, Map.of("batchId", batchId), (rs, index) -> new MigrationModels.BatchEvent(
                rs.getString("event_type"), rs.getString("from_status"), rs.getString("to_status"),
                jsonMap(rs.getString("detail_json")), localDateTime(rs, "created_at")));
        String token = security.requirePrincipal().permissions().contains("migration:write") ? row.rollbackToken() : null;
        return new MigrationModels.BatchDetail(row.summary(), token, quarantine, mappings, reconciliation, events, replayed);
    }

    @Transactional
    public MigrationModels.BatchDetail create(MigrationModels.CreateBatchRequest request) {
        requireImport(request.communityId());
        List<Map<String, Object>> fingerprintRows = request.rows().stream()
                .map(item -> Map.<String, Object>of(
                        "resourceType", item.resourceType().trim().toUpperCase(Locale.ROOT),
                        "sourceId", item.sourceId().trim(), "data", item.data()))
                .sorted(Comparator.comparing(item -> resourceRank(item.get("resourceType").toString())
                        + "|" + item.get("sourceId")))
                .toList();
        String sourceHash = sha256(canonicalJson(fingerprintRows));
        List<String> replayIds = jdbc.queryForList("""
                SELECT id FROM migration_batch
                WHERE community_id=:communityId AND source_sha256=:sourceHash AND mapping_version=:mappingVersion
                """, Map.of("communityId", request.communityId(), "sourceHash", sourceHash,
                "mappingVersion", request.mappingVersion().trim()), String.class);
        if (!replayIds.isEmpty()) return batch(replayIds.get(0), request.communityId(), true);

        String batchId = UUID.randomUUID().toString();
        String batchNo = "MIG-" + LocalDate.now(ZoneOffset.UTC).toString().replace("-", "")
                + "-" + batchId.substring(0, 8).toUpperCase(Locale.ROOT);
        String rollbackToken = UUID.randomUUID().toString();
        LocalDateTime now = now();
        String actor = security.requirePrincipal().userId();
        var batchParameters = new MapSqlParameterSource()
                .addValue("id", batchId).addValue("communityId", request.communityId())
                .addValue("batchNo", batchNo).addValue("sourceName", request.sourceName().trim())
                .addValue("sourceHash", sourceHash).addValue("mappingVersion", request.mappingVersion().trim())
                .addValue("actor", actor).addValue("total", request.rows().size())
                .addValue("rollbackToken", rollbackToken).addValue("now", now);
        jdbc.update("""
                INSERT INTO migration_batch
                    (id, community_id, batch_no, source_type, source_name, source_sha256, mapping_version,
                     status, review_status, requested_by, total_count, rollback_token, version, created_at, updated_at)
                VALUES (:id, :communityId, :batchNo, 'JSON', :sourceName, :sourceHash, :mappingVersion,
                        'UPLOADED', 'PENDING', :actor, :total, :rollbackToken, 0, :now, :now)
                """, batchParameters);
        int rowNo = 0;
        for (MigrationModels.SourceRow sourceRow : request.rows()) {
            rowNo++;
            String rawJson = canonicalJson(sourceRow.data());
            jdbc.update("""
                    INSERT INTO migration_raw_record
                        (id, batch_id, row_no, resource_type, source_id, raw_json, record_sha256, created_at)
                    VALUES (:id, :batchId, :rowNo, :resourceType, :sourceId, :rawJson, :recordHash, :now)
                    """, new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID().toString()).addValue("batchId", batchId)
                    .addValue("rowNo", rowNo)
                    .addValue("resourceType", sourceRow.resourceType().trim().toUpperCase(Locale.ROOT))
                    .addValue("sourceId", sourceRow.sourceId().trim())
                    .addValue("rawJson", rawJson).addValue("recordHash", sha256(rawJson)).addValue("now", now));
        }
        event(batchId, "BATCH_UPLOADED", null, "UPLOADED", Map.of("rowCount", request.rows().size()), now);
        audit.success(request.communityId(), "migration-batch:upload", "migration-batch", batchId,
                Map.of("batchNo", batchNo, "rowCount", request.rows().size(), "mappingVersion", request.mappingVersion()));
        return batch(batchId, request.communityId(), false);
    }

    @Transactional
    public MigrationModels.CommandResult validate(String batchId, MigrationModels.BatchCommandRequest request) {
        requireImport(request.communityId());
        BatchRow batch = requireBatch(batchId, request.communityId());
        requireVersion(batch, request.expectedVersion());
        if (!Set.of("UPLOADED", "READY", "PARTIAL_FAILED", "FAILED").contains(batch.status())) {
            throw conflict("当前批次状态不能重新校验");
        }
        updateStatus(batch, "VALIDATING", null);
        jdbc.update("DELETE FROM migration_staging_record WHERE batch_id=:batchId", Map.of("batchId", batchId));
        jdbc.update("DELETE FROM migration_canonical_record WHERE batch_id=:batchId", Map.of("batchId", batchId));
        jdbc.update("DELETE FROM migration_quarantine_record WHERE batch_id=:batchId", Map.of("batchId", batchId));

        List<RawRow> rows = rawRows(batchId);
        Map<String, Long> sourceFrequency = new HashMap<>();
        for (RawRow row : rows) sourceFrequency.merge(key(row.resourceType(), row.sourceId()), 1L, Long::sum);
        Map<String, List<String>> errors = new LinkedHashMap<>();
        Map<String, Map<String, Object>> canonical = new LinkedHashMap<>();
        for (RawRow row : rows) {
            List<String> rowErrors = new ArrayList<>();
            if (sourceFrequency.getOrDefault(key(row.resourceType(), row.sourceId()), 0L) > 1) {
                rowErrors.add("同一资源类型的 sourceId 在批次内重复");
            }
            Map<String, Object> normalized = normalize(row, rowErrors, request.communityId());
            canonical.put(row.id(), normalized);
            if (!rowErrors.isEmpty()) errors.put(row.id(), rowErrors);
        }
        validateReferences(rows, canonical, errors);
        validateRelationDuplicates(rows, canonical, errors);

        int valid = 0;
        int invalid = 0;
        LocalDateTime now = now();
        for (RawRow row : rows) {
            List<String> rowErrors = errors.get(row.id());
            if (rowErrors != null && !rowErrors.isEmpty()) {
                invalid++;
                jdbc.update("""
                        INSERT INTO migration_quarantine_record
                            (id, batch_id, raw_record_id, error_code, field_name, error_message, detail_json, created_at)
                        VALUES (:id, :batchId, :rawId, 'VALIDATION_FAILED', NULL, :message, :detail, :now)
                        """, new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID().toString()).addValue("batchId", batchId)
                        .addValue("rawId", row.id()).addValue("message", String.join("；", rowErrors))
                        .addValue("detail", json(Map.of("errors", rowErrors))).addValue("now", now));
                continue;
            }
            valid++;
            String canonicalId = UUID.randomUUID().toString();
            String canonicalJson = canonicalJson(canonical.get(row.id()));
            jdbc.update("""
                    INSERT INTO migration_canonical_record
                        (id, batch_id, raw_record_id, resource_type, source_id,
                         canonical_json, canonical_sha256, created_at)
                    VALUES (:id, :batchId, :rawId, :resourceType, :sourceId, :payload, :hash, :now)
                    """, new MapSqlParameterSource()
                    .addValue("id", canonicalId).addValue("batchId", batchId).addValue("rawId", row.id())
                    .addValue("resourceType", row.resourceType()).addValue("sourceId", row.sourceId())
                    .addValue("payload", canonicalJson).addValue("hash", sha256(canonicalJson)).addValue("now", now));
            jdbc.update("""
                    INSERT INTO migration_staging_record
                        (id, batch_id, canonical_record_id, validation_status, target_action, staged_json, created_at)
                    VALUES (:id, :batchId, :canonicalId, 'VALID', :action, :payload, :now)
                    """, new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID().toString()).addValue("batchId", batchId)
                    .addValue("canonicalId", canonicalId)
                    .addValue("action", row.resourceType().equals("PROJECT") ? "MAP" : "INSERT")
                    .addValue("payload", canonicalJson).addValue("now", now));
        }
        String nextStatus = invalid == 0 ? "READY" : valid == 0 ? "FAILED" : "PARTIAL_FAILED";
        jdbc.update("""
                UPDATE migration_batch
                SET status=:status, review_status='PENDING', review_comment=NULL, reviewed_by=NULL, reviewed_at=NULL,
                    quarantine_count=:invalid, canonical_count=:valid, staged_count=:valid,
                    imported_count=0, skipped_count=0, error_count=:invalid,
                    version=version+1, updated_at=:now
                WHERE id=:batchId
                """, Map.of("status", nextStatus, "invalid", invalid, "valid", valid, "now", now, "batchId", batchId));
        event(batchId, "BATCH_VALIDATED", "VALIDATING", nextStatus,
                Map.of("validRows", valid, "invalidRows", invalid), now);
        audit.success(request.communityId(), "migration-batch:validate", "migration-batch", batchId,
                Map.of("status", nextStatus, "validRows", valid, "invalidRows", invalid));
        return command(requireBatch(batchId, request.communityId()), false);
    }

    @Transactional
    public MigrationModels.CommandResult approve(String batchId, MigrationModels.ApprovalRequest request) {
        requireWrite(request.communityId());
        security.requireRole("PLATFORM_ADMIN");
        BatchRow batch = requireBatch(batchId, request.communityId());
        requireVersion(batch, request.expectedVersion());
        if (!Set.of("READY", "PARTIAL_FAILED").contains(batch.status())) throw conflict("只有已校验批次可以审批");
        if (batch.status().equals("PARTIAL_FAILED") && !request.confirmPartial()) {
            throw conflict("批次包含隔离错误，需明确确认仅执行合格记录");
        }
        LocalDateTime now = now();
        jdbc.update("""
                UPDATE migration_batch
                SET status='APPROVED', review_status='APPROVED', review_comment=:comment,
                    reviewed_by=:reviewer, reviewed_at=:now, version=version+1, updated_at=:now
                WHERE id=:batchId
                """, new MapSqlParameterSource("comment", request.comment())
                .addValue("reviewer", security.requirePrincipal().userId()).addValue("now", now).addValue("batchId", batchId));
        event(batchId, "BATCH_APPROVED", batch.status(), "APPROVED",
                Map.of("partial", batch.status().equals("PARTIAL_FAILED")), now);
        audit.success(request.communityId(), "migration-batch:approve", "migration-batch", batchId,
                Map.of("partial", batch.status().equals("PARTIAL_FAILED"), "comment", safe(request.comment())));
        return command(requireBatch(batchId, request.communityId()), false);
    }

    @Transactional
    public MigrationModels.CommandResult execute(String batchId, MigrationModels.BatchCommandRequest request) {
        requireWrite(request.communityId());
        BatchRow batch = requireBatch(batchId, request.communityId());
        if (Set.of("COMPLETED", "RECONCILED").contains(batch.status())) return command(batch, true);
        requireVersion(batch, request.expectedVersion());
        if (!batch.status().equals("APPROVED") || !batch.reviewStatus().equals("APPROVED")) {
            throw conflict("批次尚未审批，不能写入生产层");
        }
        LocalDateTime now = now();
        updateStatus(batch, "EXECUTING", null);
        event(batchId, "EXECUTION_STARTED", "APPROVED", "EXECUTING", Map.of(), now);
        List<StagedRow> rows = stagedRows(batchId);
        Map<String, String> targets = new HashMap<>();
        int imported = 0;
        int skipped = 0;
        int order = 0;
        for (StagedRow row : rows) {
            if (row.resourceType().equals("PROJECT")) {
                targets.put(key("PROJECT", row.sourceId()), request.communityId());
                insertMapping(batch, row, request.communityId(), request.communityId(), "PROJECT", now);
                skipped++;
                continue;
            }
            String targetId = UUID.randomUUID().toString();
            switch (row.resourceType()) {
                case "BUILDING" -> {
                    requireTarget(targets, "PROJECT", text(row.data(), "projectSourceId"));
                    jdbc.update("""
                            INSERT INTO building
                                (id, community_id, grid_id, source_id, code, name, building_type,
                                 floor_count, status, version, created_at, updated_at)
                            VALUES (:id, :communityId, NULL, NULL, :code, :name, :type,
                                    :floorCount, 'ACTIVE', 0, :now, :now)
                            """, new MapSqlParameterSource("id", targetId).addValue("communityId", request.communityId())
                            .addValue("code", text(row.data(), "code")).addValue("name", text(row.data(), "name"))
                            .addValue("type", text(row.data(), "buildingType"))
                            .addValue("floorCount", row.data().get("floorCount")).addValue("now", now));
                    order = change(batchId, ++order, "BUILDING", "building", targetId, row.data(), now);
                    insertMapping(batch, row, targetId, text(row.data(), "code"), "BUILDING", now);
                }
                case "ASSET" -> {
                    String buildingId = requireTarget(targets, "BUILDING", text(row.data(), "buildingSourceId"));
                    jdbc.update("""
                            INSERT INTO asset
                                (id, community_id, grid_id, building_id, unit_id, source_id, asset_type,
                                 code, display_name, floor_no, building_area, usable_area,
                                 occupancy_status, operation_status, enabled, valid_from, valid_to,
                                 version, created_at, updated_at)
                            SELECT :id, :communityId, b.grid_id, :buildingId, NULL, NULL, 'ROOM',
                                   :code, :displayName, :floorNo, :buildingArea, :usableArea,
                                   'VACANT', 'NORMAL', TRUE, :validFrom, NULL, 0, :now, :now
                            FROM building b WHERE b.id=:buildingId AND b.community_id=:communityId
                            """, new MapSqlParameterSource("id", targetId).addValue("communityId", request.communityId())
                            .addValue("buildingId", buildingId).addValue("code", text(row.data(), "code"))
                            .addValue("displayName", text(row.data(), "displayName"))
                            .addValue("floorNo", nullableText(row.data(), "floorNo"))
                            .addValue("buildingArea", decimal(row.data(), "buildingArea"))
                            .addValue("usableArea", decimal(row.data(), "usableArea"))
                            .addValue("validFrom", date(row.data(), "validFrom")).addValue("now", now));
                    order = change(batchId, ++order, "ASSET", "asset", targetId, row.data(), now);
                    jdbc.update("INSERT INTO room_detail (asset_id, room_type, delivery_date) VALUES (:id, 'RESIDENTIAL', NULL)",
                            Map.of("id", targetId));
                    order = change(batchId, ++order, "ASSET_DETAIL", "room_detail", targetId,
                            Map.of("roomType", "RESIDENTIAL"), now);
                    insertMapping(batch, row, targetId, text(row.data(), "code"), "ASSET", now);
                }
                case "CUSTOMER" -> {
                    jdbc.update("""
                            INSERT INTO customer
                                (id, community_id, source_id, customer_no, display_name, customer_type,
                                 customer_class, mobile_masked, mobile_ciphertext, mobile_search_hash,
                                 certificate_type, certificate_masked, certificate_ciphertext, gender,
                                 birthday, remarks, status, version, created_at, updated_at)
                            VALUES (:id, :communityId, NULL, :customerNo, :displayName, :customerType,
                                    :customerClass, :mobileMasked, NULL, NULL, NULL, NULL, NULL, :gender,
                                    NULL, :remarks, 'ACTIVE', 0, :now, :now)
                            """, new MapSqlParameterSource("id", targetId).addValue("communityId", request.communityId())
                            .addValue("customerNo", text(row.data(), "customerNo"))
                            .addValue("displayName", text(row.data(), "displayName"))
                            .addValue("customerType", text(row.data(), "customerType"))
                            .addValue("customerClass", nullableText(row.data(), "customerClass"))
                            .addValue("mobileMasked", nullableText(row.data(), "mobileMasked"))
                            .addValue("gender", text(row.data(), "gender"))
                            .addValue("remarks", nullableText(row.data(), "remarks")).addValue("now", now));
                    order = change(batchId, ++order, "CUSTOMER", "customer", targetId, row.data(), now);
                    insertMapping(batch, row, targetId, text(row.data(), "customerNo"), "CUSTOMER", now);
                }
                case "RELATION" -> {
                    String customerId = requireTarget(targets, "CUSTOMER", text(row.data(), "customerSourceId"));
                    String assetId = requireTarget(targets, "ASSET", text(row.data(), "assetSourceId"));
                    jdbc.update("""
                            INSERT INTO customer_asset_relation
                                (id, community_id, customer_id, asset_id, relation_type, primary_relation,
                                 start_date, end_date, change_reason, status, version, created_at, updated_at)
                            VALUES (:id, :communityId, :customerId, :assetId, :relationType, :primary,
                                    :startDate, NULL, '数据迁移', 'ACTIVE', 0, :now, :now)
                            """, new MapSqlParameterSource("id", targetId).addValue("communityId", request.communityId())
                            .addValue("customerId", customerId).addValue("assetId", assetId)
                            .addValue("relationType", text(row.data(), "relationType"))
                            .addValue("primary", Boolean.TRUE.equals(row.data().get("primaryRelation")))
                            .addValue("startDate", date(row.data(), "startDate")).addValue("now", now));
                    order = change(batchId, ++order, "RELATION", "customer_asset_relation", targetId, row.data(), now);
                    insertMapping(batch, row, targetId, text(row.data(), "relationType"), "RELATION", now);
                }
                default -> throw conflict("批次包含不支持的资源类型");
            }
            targets.put(key(row.resourceType(), row.sourceId()), targetId);
            imported++;
        }
        jdbc.update("""
                UPDATE migration_batch SET status='COMPLETED', imported_count=:imported, skipped_count=:skipped,
                    executed_at=:now, version=version+1, updated_at=:now WHERE id=:batchId
                """, Map.of("imported", imported, "skipped", skipped, "now", now, "batchId", batchId));
        event(batchId, "EXECUTION_COMPLETED", "EXECUTING", "COMPLETED",
                Map.of("importedRows", imported, "skippedRows", skipped), now);
        audit.success(request.communityId(), "migration-batch:execute", "migration-batch", batchId,
                Map.of("importedRows", imported, "skippedRows", skipped));
        return command(requireBatch(batchId, request.communityId()), false);
    }

    @Transactional
    public MigrationModels.CommandResult reconcile(String batchId, MigrationModels.BatchCommandRequest request) {
        requireWrite(request.communityId());
        BatchRow batch = requireBatch(batchId, request.communityId());
        requireVersion(batch, request.expectedVersion());
        if (!Set.of("COMPLETED", "RECONCILED").contains(batch.status())) throw conflict("只有已执行批次可以对账");
        jdbc.update("DELETE FROM migration_reconciliation WHERE batch_id=:batchId", Map.of("batchId", batchId));
        List<Metric> metrics = new ArrayList<>();
        for (String resource : List.of("PROJECT", "BUILDING", "ASSET", "CUSTOMER", "RELATION")) {
            BigDecimal source = scalar("""
                    SELECT COUNT(*) FROM migration_staging_record s
                    JOIN migration_canonical_record c ON c.id=s.canonical_record_id
                    WHERE s.batch_id=:batchId AND c.resource_type=:resource
                    """, Map.of("batchId", batchId, "resource", resource));
            BigDecimal target = mappedTargetCount(batchId, resource);
            metrics.add(new Metric(resource + "_COUNT", source, target,
                    Map.of("scope", "batch mappings", "resourceType", resource)));
        }
        BigDecimal sourceBuildingArea = jsonDecimalSum(batchId, "buildingArea");
        BigDecimal targetBuildingArea = scalar("""
                SELECT COALESCE(SUM(a.building_area), 0) FROM migration_object_map m
                JOIN asset a ON a.id=m.target_id AND a.community_id=m.community_id
                WHERE m.batch_id=:batchId AND m.resource_type='ASSET' AND m.active=TRUE
                """, Map.of("batchId", batchId));
        metrics.add(new Metric("BUILDING_AREA_SUM", sourceBuildingArea, targetBuildingArea, Map.of("unit", "sqm")));
        BigDecimal sourceUsableArea = jsonDecimalSum(batchId, "usableArea");
        BigDecimal targetUsableArea = scalar("""
                SELECT COALESCE(SUM(a.usable_area), 0) FROM migration_object_map m
                JOIN asset a ON a.id=m.target_id AND a.community_id=m.community_id
                WHERE m.batch_id=:batchId AND m.resource_type='ASSET' AND m.active=TRUE
                """, Map.of("batchId", batchId));
        metrics.add(new Metric("USABLE_AREA_SUM", sourceUsableArea, targetUsableArea, Map.of("unit", "sqm")));
        BigDecimal orphan = scalar("""
                SELECT COUNT(*) FROM migration_object_map m
                JOIN customer_asset_relation r ON r.id=m.target_id
                LEFT JOIN customer c ON c.id=r.customer_id
                LEFT JOIN asset a ON a.id=r.asset_id
                WHERE m.batch_id=:batchId AND m.resource_type='RELATION' AND m.active=TRUE
                  AND (c.id IS NULL OR a.id IS NULL OR c.community_id<>r.community_id OR a.community_id<>r.community_id)
                """, Map.of("batchId", batchId));
        metrics.add(new Metric("ORPHAN_RELATION_COUNT", BigDecimal.ZERO, orphan, Map.of("expected", 0)));
        BigDecimal duplicate = scalar("""
                SELECT COUNT(*) FROM (
                    SELECT r.customer_id, r.asset_id, r.relation_type
                    FROM migration_object_map m JOIN customer_asset_relation r ON r.id=m.target_id
                    WHERE m.batch_id=:batchId AND m.resource_type='RELATION' AND m.active=TRUE
                      AND r.status='ACTIVE' AND r.end_date IS NULL
                    GROUP BY r.customer_id, r.asset_id, r.relation_type HAVING COUNT(*) > 1
                ) d
                """, Map.of("batchId", batchId));
        metrics.add(new Metric("DUPLICATE_ACTIVE_RELATION_COUNT", BigDecimal.ZERO, duplicate, Map.of("expected", 0)));
        LocalDateTime now = now();
        boolean matched = true;
        for (Metric metric : metrics) matched &= persistMetric(batchId, metric, now);
        String nextStatus = matched ? "RECONCILED" : "COMPLETED";
        jdbc.update("""
                UPDATE migration_batch SET status=:status, reconciled_at=:reconciledAt,
                    version=version+1, updated_at=:now WHERE id=:batchId
                """, new MapSqlParameterSource("status", nextStatus)
                .addValue("reconciledAt", matched ? now : null).addValue("now", now).addValue("batchId", batchId));
        event(batchId, matched ? "RECONCILIATION_MATCHED" : "RECONCILIATION_MISMATCHED",
                batch.status(), nextStatus, Map.of("metricCount", metrics.size()), now);
        audit.success(request.communityId(), "migration-batch:reconcile", "migration-batch", batchId,
                Map.of("matched", matched, "metricCount", metrics.size()));
        return command(requireBatch(batchId, request.communityId()), false);
    }

    @Transactional
    public MigrationModels.CommandResult rollback(String batchId, MigrationModels.RollbackRequest request) {
        requireWrite(request.communityId());
        security.requireRole("PLATFORM_ADMIN");
        BatchRow batch = requireBatch(batchId, request.communityId());
        if (batch.status().equals("ROLLED_BACK")) return command(batch, true);
        requireVersion(batch, request.expectedVersion());
        if (!Set.of("COMPLETED", "RECONCILED").contains(batch.status())) throw conflict("只有已执行批次可以回滚");
        if (!MessageDigest.isEqual(batch.rollbackToken().getBytes(StandardCharsets.UTF_8),
                request.rollbackToken().getBytes(StandardCharsets.UTF_8))) throw conflict("回滚凭证无效");
        List<ChangeRow> changes = jdbc.query("""
                SELECT id, target_table, target_id FROM migration_change_log
                WHERE batch_id=:batchId AND rolled_back_at IS NULL ORDER BY execution_order DESC
                """, Map.of("batchId", batchId), (rs, index) -> new ChangeRow(
                rs.getString("id"), rs.getString("target_table"), rs.getString("target_id")));
        LocalDateTime now = now();
        for (ChangeRow change : changes) {
            String table = switch (change.targetTable()) {
                case "customer_asset_relation" -> "customer_asset_relation";
                case "room_detail" -> "room_detail";
                case "asset" -> "asset";
                case "customer" -> "customer";
                case "building" -> "building";
                default -> throw conflict("变更日志包含不允许回滚的目标表");
            };
            String idColumn = table.equals("room_detail") ? "asset_id" : "id";
            jdbc.update("DELETE FROM " + table + " WHERE " + idColumn + "=:id", Map.of("id", change.targetId()));
            jdbc.update("UPDATE migration_change_log SET rolled_back_at=:now WHERE id=:id",
                    Map.of("now", now, "id", change.id()));
        }
        jdbc.update("UPDATE migration_object_map SET active=FALSE, rolled_back_at=:now WHERE batch_id=:batchId",
                Map.of("now", now, "batchId", batchId));
        jdbc.update("DELETE FROM migration_reconciliation WHERE batch_id=:batchId", Map.of("batchId", batchId));
        persistMetric(batchId, new Metric("ROLLBACK_REMAINING_TARGET_COUNT", BigDecimal.ZERO,
                remainingTargets(batchId), Map.of("reason", request.reason())), now);
        jdbc.update("""
                UPDATE migration_batch SET status='ROLLED_BACK', rolled_back_at=:now,
                    version=version+1, updated_at=:now WHERE id=:batchId
                """, Map.of("now", now, "batchId", batchId));
        event(batchId, "BATCH_ROLLED_BACK", batch.status(), "ROLLED_BACK",
                Map.of("changeCount", changes.size(), "reason", request.reason()), now);
        audit.success(request.communityId(), "migration-batch:rollback", "migration-batch", batchId,
                Map.of("changeCount", changes.size(), "reason", request.reason()));
        return command(requireBatch(batchId, request.communityId()), false);
    }

    public String template() {
        security.requirePermission("migration:export");
        return "resourceType,sourceId,data(JSON)\r\n"
                + "PROJECT,SRC-PROJECT-001,{\"name\":\"迁移项目\"}\r\n"
                + "BUILDING,SRC-BUILDING-001,{\"projectSourceId\":\"SRC-PROJECT-001\",\"code\":\"MIG-B01\",\"name\":\"迁移楼栋\"}\r\n"
                + "ASSET,SRC-ROOM-001,{\"buildingSourceId\":\"SRC-BUILDING-001\",\"assetType\":\"ROOM\",\"code\":\"MIG-R001\",\"displayName\":\"迁移房屋\",\"buildingArea\":88,\"usableArea\":70,\"validFrom\":\"2026-01-01\"}\r\n";
    }

    private Map<String, Object> normalize(RawRow row, List<String> errors, String communityId) {
        Map<String, Object> data = row.data();
        var result = new LinkedHashMap<String, Object>();
        switch (row.resourceType()) {
            case "PROJECT" -> {
                result.put("targetCommunityId", communityId);
                result.put("name", optional(data, "name", "迁移项目"));
            }
            case "BUILDING" -> {
                result.put("projectSourceId", required(data, "projectSourceId", errors));
                String code = required(data, "code", errors);
                result.put("code", code);
                result.put("name", required(data, "name", errors));
                String type = optional(data, "buildingType", "RESIDENTIAL").toUpperCase(Locale.ROOT);
                if (!Set.of("RESIDENTIAL", "COMMERCIAL", "MIXED").contains(type)) errors.add("buildingType 无效");
                result.put("buildingType", type);
                result.put("floorCount", integer(data.get("floorCount"), "floorCount", errors));
                if (hasText(code) && exists("SELECT COUNT(*) FROM building WHERE community_id=:communityId AND code=:value",
                        communityId, code)) errors.add("楼栋编码已存在于生产层");
            }
            case "ASSET" -> {
                result.put("buildingSourceId", required(data, "buildingSourceId", errors));
                String type = optional(data, "assetType", "ROOM").toUpperCase(Locale.ROOT);
                if (!type.equals("ROOM")) errors.add("首批迁移仅支持 ROOM 资产");
                result.put("assetType", type);
                String code = required(data, "code", errors);
                result.put("code", code);
                result.put("displayName", required(data, "displayName", errors));
                result.put("floorNo", nullable(data, "floorNo"));
                BigDecimal buildingArea = number(data.get("buildingArea"), "buildingArea", errors);
                BigDecimal usableArea = number(data.get("usableArea"), "usableArea", errors);
                if (buildingArea != null && buildingArea.signum() < 0) errors.add("buildingArea 不能小于 0");
                if (usableArea != null && usableArea.signum() < 0) errors.add("usableArea 不能小于 0");
                if (buildingArea != null && usableArea != null && usableArea.compareTo(buildingArea) > 0) {
                    errors.add("usableArea 不能大于 buildingArea");
                }
                result.put("buildingArea", buildingArea);
                result.put("usableArea", usableArea);
                result.put("validFrom", requiredDate(data, "validFrom", errors));
                if (hasText(code) && exists("""
                        SELECT COUNT(*) FROM asset WHERE community_id=:communityId AND asset_type='ROOM' AND code=:value
                        """, communityId, code)) errors.add("房屋编码已存在于生产层");
            }
            case "CUSTOMER" -> {
                String customerNo = required(data, "customerNo", errors);
                result.put("customerNo", customerNo);
                result.put("displayName", required(data, "displayName", errors));
                String type = optional(data, "customerType", "PERSON").toUpperCase(Locale.ROOT);
                if (!Set.of("PERSON", "ORGANIZATION").contains(type)) errors.add("customerType 无效");
                result.put("customerType", type);
                result.put("customerClass", nullable(data, "customerClass"));
                result.put("mobileMasked", nullable(data, "mobileMasked"));
                result.put("gender", optional(data, "gender", "UNKNOWN").toUpperCase(Locale.ROOT));
                result.put("remarks", nullable(data, "remarks"));
                if (hasText(customerNo) && exists("""
                        SELECT COUNT(*) FROM customer WHERE community_id=:communityId AND customer_no=:value
                        """, communityId, customerNo)) errors.add("客户编号已存在于生产层");
            }
            case "RELATION" -> {
                result.put("customerSourceId", required(data, "customerSourceId", errors));
                result.put("assetSourceId", required(data, "assetSourceId", errors));
                String relationType = optional(data, "relationType", "OWNER").toUpperCase(Locale.ROOT);
                if (!Set.of("OWNER", "CO_OWNER", "TENANT", "OCCUPANT").contains(relationType)) {
                    errors.add("relationType 无效");
                }
                result.put("relationType", relationType);
                result.put("primaryRelation", booleanValue(data.get("primaryRelation"), true));
                result.put("startDate", requiredDate(data, "startDate", errors));
            }
            default -> errors.add("资源类型不受支持");
        }
        return result;
    }

    private void validateReferences(List<RawRow> rows, Map<String, Map<String, Object>> canonical,
                                    Map<String, List<String>> errors) {
        Map<String, RawRow> unique = new HashMap<>();
        Set<String> duplicated = new HashSet<>();
        for (RawRow row : rows) {
            String key = key(row.resourceType(), row.sourceId());
            if (unique.putIfAbsent(key, row) != null) duplicated.add(key);
        }
        for (RawRow row : rows) {
            Map<String, Object> data = canonical.get(row.id());
            String referenceType = null;
            String reference = null;
            if (row.resourceType().equals("BUILDING")) {
                referenceType = "PROJECT";
                reference = text(data, "projectSourceId");
            } else if (row.resourceType().equals("ASSET")) {
                referenceType = "BUILDING";
                reference = text(data, "buildingSourceId");
            }
            if (referenceType != null && hasText(reference)) {
                String referenceKey = key(referenceType, reference);
                RawRow target = unique.get(referenceKey);
                if (target == null || duplicated.contains(referenceKey) || errors.containsKey(target.id())) {
                    addError(errors, row.id(), referenceType + " 引用不存在、重复或未通过校验");
                }
            }
            if (row.resourceType().equals("RELATION")) {
                for (String[] ref : List.of(
                        new String[]{"CUSTOMER", text(data, "customerSourceId")},
                        new String[]{"ASSET", text(data, "assetSourceId")})) {
                    if (!hasText(ref[1])) continue;
                    String referenceKey = key(ref[0], ref[1]);
                    RawRow target = unique.get(referenceKey);
                    if (target == null || duplicated.contains(referenceKey) || errors.containsKey(target.id())) {
                        addError(errors, row.id(), ref[0] + " 引用不存在、重复或未通过校验");
                    }
                }
            }
        }
    }

    private void validateRelationDuplicates(List<RawRow> rows, Map<String, Map<String, Object>> canonical,
                                            Map<String, List<String>> errors) {
        Map<String, List<RawRow>> relationKeys = new HashMap<>();
        for (RawRow row : rows) {
            if (!row.resourceType().equals("RELATION")) continue;
            Map<String, Object> data = canonical.get(row.id());
            String composite = text(data, "customerSourceId") + "|" + text(data, "assetSourceId")
                    + "|" + text(data, "relationType");
            relationKeys.computeIfAbsent(composite, ignored -> new ArrayList<>()).add(row);
        }
        relationKeys.values().stream().filter(group -> group.size() > 1).flatMap(Collection::stream)
                .forEach(row -> addError(errors, row.id(), "同一客户、资产和关系类型在批次内重复"));
    }

    private List<RawRow> rawRows(String batchId) {
        return jdbc.query("""
                SELECT id, row_no, resource_type, source_id, raw_json
                FROM migration_raw_record WHERE batch_id=:batchId ORDER BY row_no
                """, Map.of("batchId", batchId), (rs, index) -> new RawRow(
                rs.getString("id"), rs.getInt("row_no"), rs.getString("resource_type"),
                rs.getString("source_id"), jsonMap(rs.getString("raw_json"))));
    }

    private List<StagedRow> stagedRows(String batchId) {
        return jdbc.query("""
                SELECT c.resource_type, c.source_id, s.target_action, s.staged_json
                FROM migration_staging_record s JOIN migration_canonical_record c ON c.id=s.canonical_record_id
                JOIN migration_raw_record r ON r.id=c.raw_record_id
                WHERE s.batch_id=:batchId AND s.validation_status='VALID'
                ORDER BY FIELD(c.resource_type, 'PROJECT','BUILDING','ASSET','CUSTOMER','RELATION'), r.row_no
                """, Map.of("batchId", batchId), (rs, index) -> new StagedRow(
                rs.getString("resource_type"), rs.getString("source_id"),
                rs.getString("target_action"), jsonMap(rs.getString("staged_json"))));
    }

    private void insertMapping(BatchRow batch, StagedRow row, String targetId, String targetCode,
                               String resourceType, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO migration_object_map
                    (id, batch_id, community_id, resource_type, source_system, source_id,
                     target_id, target_code, active, created_at)
                VALUES (:id, :batchId, :communityId, :resourceType, 'MIGRATION_CENTER', :sourceId,
                        :targetId, :targetCode, TRUE, :now)
                """, new MapSqlParameterSource("id", UUID.randomUUID().toString()).addValue("batchId", batch.id())
                .addValue("communityId", batch.communityId()).addValue("resourceType", resourceType)
                .addValue("sourceId", row.sourceId()).addValue("targetId", targetId)
                .addValue("targetCode", targetCode).addValue("now", now));
    }

    private int change(String batchId, int order, String resourceType, String table, String targetId,
                       Map<String, Object> snapshot, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO migration_change_log
                    (id, batch_id, execution_order, resource_type, target_table, target_id,
                     action_type, snapshot_json, created_at)
                VALUES (:id, :batchId, :executionOrder, :resourceType, :targetTable, :targetId,
                        'INSERT', :snapshot, :now)
                """, new MapSqlParameterSource("id", UUID.randomUUID().toString()).addValue("batchId", batchId)
                .addValue("executionOrder", order).addValue("resourceType", resourceType)
                .addValue("targetTable", table).addValue("targetId", targetId)
                .addValue("snapshot", json(snapshot)).addValue("now", now));
        return order;
    }

    private BigDecimal mappedTargetCount(String batchId, String resource) {
        String table = switch (resource) {
            case "PROJECT" -> "community";
            case "BUILDING" -> "building";
            case "ASSET" -> "asset";
            case "CUSTOMER" -> "customer";
            case "RELATION" -> "customer_asset_relation";
            default -> throw new IllegalArgumentException("Unsupported resource " + resource);
        };
        return scalar("SELECT COUNT(*) FROM migration_object_map m JOIN " + table
                        + " t ON t.id=m.target_id WHERE m.batch_id=:batchId AND m.resource_type=:resource AND m.active=TRUE",
                Map.of("batchId", batchId, "resource", resource));
    }

    private BigDecimal jsonDecimalSum(String batchId, String field) {
        return scalar("""
                SELECT COALESCE(SUM(CAST(JSON_UNQUOTE(JSON_EXTRACT(s.staged_json, :path)) AS DECIMAL(20,4))), 0)
                FROM migration_staging_record s JOIN migration_canonical_record c ON c.id=s.canonical_record_id
                WHERE s.batch_id=:batchId AND c.resource_type='ASSET'
                """, Map.of("batchId", batchId, "path", "$." + field));
    }

    private boolean persistMetric(String batchId, Metric metric, LocalDateTime now) {
        BigDecimal difference = metric.target().subtract(metric.source());
        boolean matched = difference.compareTo(BigDecimal.ZERO) == 0;
        jdbc.update("""
                INSERT INTO migration_reconciliation
                    (id, batch_id, metric_name, source_value, target_value, difference_value,
                     status, detail_json, created_at)
                VALUES (:id, :batchId, :name, :source, :target, :difference, :status, :detail, :now)
                """, new MapSqlParameterSource("id", UUID.randomUUID().toString()).addValue("batchId", batchId)
                .addValue("name", metric.name()).addValue("source", metric.source()).addValue("target", metric.target())
                .addValue("difference", difference).addValue("status", matched ? "MATCHED" : "MISMATCH")
                .addValue("detail", json(metric.detail())).addValue("now", now));
        return matched;
    }

    private BigDecimal remainingTargets(String batchId) {
        BigDecimal result = BigDecimal.ZERO;
        for (String resource : List.of("BUILDING", "ASSET", "CUSTOMER", "RELATION")) {
            result = result.add(mappedTargetCountIncludingInactive(batchId, resource));
        }
        return result;
    }

    private BigDecimal mappedTargetCountIncludingInactive(String batchId, String resource) {
        String table = switch (resource) {
            case "BUILDING" -> "building";
            case "ASSET" -> "asset";
            case "CUSTOMER" -> "customer";
            case "RELATION" -> "customer_asset_relation";
            default -> throw new IllegalArgumentException("Unsupported resource " + resource);
        };
        return scalar("SELECT COUNT(*) FROM migration_object_map m JOIN " + table
                        + " t ON t.id=m.target_id WHERE m.batch_id=:batchId AND m.resource_type=:resource",
                Map.of("batchId", batchId, "resource", resource));
    }

    private BatchRow requireBatch(String batchId, String communityId) {
        List<BatchRow> rows = jdbc.query("SELECT * FROM migration_batch WHERE id=:id AND community_id=:communityId",
                Map.of("id", batchId, "communityId", communityId), this::batchRow);
        if (rows.isEmpty()) throw new BusinessException("MIGRATION_BATCH_NOT_FOUND", "迁移批次不存在或无权访问", HttpStatus.NOT_FOUND);
        return rows.get(0);
    }

    private BatchRow batchRow(ResultSet rs, int row) throws SQLException {
        return new BatchRow(rs.getString("id"), rs.getString("community_id"), rs.getString("rollback_token"),
                rs.getString("status"), rs.getString("review_status"), rs.getLong("version"), summary(rs, row));
    }

    private MigrationModels.BatchSummary summary(ResultSet rs, int row) throws SQLException {
        return new MigrationModels.BatchSummary(rs.getString("id"), rs.getString("community_id"),
                rs.getString("batch_no"), rs.getString("source_name"), rs.getString("mapping_version"),
                rs.getString("source_sha256"), rs.getString("status"), rs.getString("review_status"),
                rs.getInt("total_count"), rs.getInt("quarantine_count"), rs.getInt("canonical_count"),
                rs.getInt("staged_count"), rs.getInt("imported_count"), rs.getInt("skipped_count"),
                rs.getInt("error_count"), rs.getLong("version"), localDateTime(rs, "created_at"),
                localDateTime(rs, "updated_at"));
    }

    private void updateStatus(BatchRow batch, String status, String reviewStatus) {
        var parameters = new MapSqlParameterSource("status", status).addValue("now", now()).addValue("id", batch.id());
        String review = reviewStatus == null ? "" : ", review_status=:reviewStatus";
        if (reviewStatus != null) parameters.addValue("reviewStatus", reviewStatus);
        int changed = jdbc.update("UPDATE migration_batch SET status=:status" + review
                + ", version=version+1, updated_at=:now WHERE id=:id AND version=:version",
                parameters.addValue("version", batch.version()));
        if (changed != 1) throw conflict("迁移批次已被其他操作更新，请刷新后重试");
    }

    private void event(String batchId, String eventType, String fromStatus, String toStatus,
                       Map<String, Object> detail, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO migration_batch_event
                    (id, batch_id, event_type, from_status, to_status, detail_json, actor_user_id, created_at)
                VALUES (:id, :batchId, :eventType, :fromStatus, :toStatus, :detail, :actor, :now)
                """, new MapSqlParameterSource("id", UUID.randomUUID().toString()).addValue("batchId", batchId)
                .addValue("eventType", eventType).addValue("fromStatus", fromStatus).addValue("toStatus", toStatus)
                .addValue("detail", json(detail)).addValue("actor", security.requirePrincipal().userId()).addValue("now", now));
    }

    private MigrationModels.CommandResult command(BatchRow row, boolean replayed) {
        MigrationModels.BatchSummary summary = row.summary();
        return new MigrationModels.CommandResult(row.id(), summary.batchNo(), row.status(), summary.importedCount(),
                summary.skippedCount(), summary.errorCount(), row.version(), replayed);
    }

    private void requireRead(String communityId) {
        security.requireProject(communityId);
        security.requirePermission("migration:read");
    }

    private void requireImport(String communityId) {
        security.requireProject(communityId);
        security.requirePermission("migration:import");
    }

    private void requireWrite(String communityId) {
        security.requireProject(communityId);
        security.requirePermission("migration:write");
    }

    private void requireVersion(BatchRow row, long expectedVersion) {
        if (expectedVersion != row.version()) throw conflict("迁移批次版本已变化，请刷新后重试");
    }

    private boolean exists(String sql, String communityId, String value) {
        Long count = jdbc.queryForObject(sql, Map.of("communityId", communityId, "value", value), Long.class);
        return count != null && count > 0;
    }

    private String requireTarget(Map<String, String> targets, String type, String sourceId) {
        String result = targets.get(key(type, sourceId));
        if (result == null) throw conflict(type + " 来源映射缺失");
        return result;
    }

    private BigDecimal scalar(String sql, Map<String, ?> parameters) {
        BigDecimal result = jdbc.queryForObject(sql, parameters, BigDecimal.class);
        return result == null ? BigDecimal.ZERO : result;
    }

    private String required(Map<String, Object> data, String field, List<String> errors) {
        String value = nullable(data, field);
        if (!hasText(value)) errors.add(field + " 不能为空");
        return value;
    }

    private String optional(Map<String, Object> data, String field, String fallback) {
        String value = nullable(data, field);
        return hasText(value) ? value : fallback;
    }

    private String nullable(Map<String, Object> data, String field) {
        Object value = data.get(field);
        return value == null ? null : value.toString().trim();
    }

    private Integer integer(Object value, String field, List<String> errors) {
        if (value == null || !hasText(value.toString())) return null;
        try {
            int result = Integer.parseInt(value.toString());
            if (result < 0) errors.add(field + " 不能小于 0");
            return result;
        } catch (NumberFormatException exception) {
            errors.add(field + " 必须是整数");
            return null;
        }
    }

    private BigDecimal number(Object value, String field, List<String> errors) {
        if (value == null || !hasText(value.toString())) {
            errors.add(field + " 不能为空");
            return null;
        }
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException exception) {
            errors.add(field + " 必须是数字");
            return null;
        }
    }

    private String requiredDate(Map<String, Object> data, String field, List<String> errors) {
        String value = required(data, field, errors);
        if (hasText(value)) {
            try {
                LocalDate.parse(value);
            } catch (DateTimeParseException exception) {
                errors.add(field + " 必须是 yyyy-MM-dd 日期");
            }
        }
        return value;
    }

    private LocalDate date(Map<String, Object> data, String field) {
        return LocalDate.parse(text(data, field));
    }

    private BigDecimal decimal(Map<String, Object> data, String field) {
        Object value = data.get(field);
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString());
    }

    private boolean booleanValue(Object value, boolean fallback) {
        return value == null ? fallback : Boolean.parseBoolean(value.toString());
    }

    private String text(Map<String, Object> data, String field) {
        Object value = data.get(field);
        return value == null ? null : value.toString();
    }

    private String nullableText(Map<String, Object> data, String field) {
        String value = text(data, field);
        return hasText(value) ? value : null;
    }

    private void addError(Map<String, List<String>> errors, String id, String error) {
        errors.computeIfAbsent(id, ignored -> new ArrayList<>()).add(error);
    }

    private String canonicalJson(Object value) {
        return json(canonicalValue(value));
    }

    private Object canonicalValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            var sorted = new TreeMap<String, Object>();
            map.forEach((key, child) -> sorted.put(String.valueOf(key), canonicalValue(child)));
            return sorted;
        }
        if (value instanceof Collection<?> collection) return collection.stream().map(this::canonicalValue).toList();
        return value;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize migration evidence", exception);
        }
    }

    private Map<String, Object> jsonMap(String value) {
        if (!hasText(value)) return Map.of();
        try {
            return objectMapper.readValue(value, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to read migration evidence", exception);
        }
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static LocalDateTime localDateTime(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private static String key(String resourceType, String sourceId) {
        return resourceType + "|" + sourceId;
    }

    private static int resourceRank(String resource) {
        int rank = RESOURCE_ORDER.indexOf(resource);
        return rank < 0 ? 99 : rank;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String like(String value) {
        return "%" + value.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private static BusinessException invalid(String message) {
        return new BusinessException("MIGRATION_INVALID", message, HttpStatus.BAD_REQUEST);
    }

    private static BusinessException conflict(String message) {
        return new BusinessException("MIGRATION_STATE_CONFLICT", message, HttpStatus.CONFLICT);
    }

    private record BatchRow(String id, String communityId, String rollbackToken, String status,
                            String reviewStatus, long version, MigrationModels.BatchSummary summary) {}
    private record RawRow(String id, int rowNo, String resourceType, String sourceId, Map<String, Object> data) {}
    private record StagedRow(String resourceType, String sourceId, String targetAction, Map<String, Object> data) {}
    private record ChangeRow(String id, String targetTable, String targetId) {}
    private record Metric(String name, BigDecimal source, BigDecimal target, Map<String, Object> detail) {}
}
