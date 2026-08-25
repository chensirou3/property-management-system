package com.propertyops.pms.dashboard;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.propertyops.pms.common.api.BusinessException;
import com.propertyops.pms.common.audit.AuditService;
import com.propertyops.pms.security.SecurityContextService;

@Service
public class DashboardConfigurationService {
    private static final Set<String> METRICS = Set.of(
            "ASSET_COUNTS", "COLLECTION_RATE", "DATA_QUALITY", "INTEGRATION_STATUS",
            "ARREARS_SUMMARY", "METER_PROGRESS");

    private final NamedParameterJdbcTemplate jdbc;
    private final SecurityContextService security;
    private final AuditService audit;

    public DashboardConfigurationService(NamedParameterJdbcTemplate jdbc,
                                         SecurityContextService security, AuditService audit) {
        this.jdbc = jdbc;
        this.security = security;
        this.audit = audit;
    }

    public Map<String, Object> configurations(String communityId, String roleCode) {
        read(communityId);
        String role = normalizeRole(roleCode);
        List<Map<String, Object>> items = jdbc.queryForList("""
                SELECT id,community_id,role_code,widget_code,widget_name,metric_code,position_code,
                       visible,refresh_interval_seconds,display_order,status,published_at,version,
                       created_at,updated_at
                FROM dashboard_widget_configuration
                WHERE community_id=:communityId AND role_code=:roleCode
                ORDER BY display_order,widget_code
                """, Map.of("communityId", communityId, "roleCode", role));
        long published = items.stream().filter(item -> "PUBLISHED".equals(item.get("status"))).count();
        return Map.of("communityId", communityId, "roleCode", role, "items", items,
                "total", items.size(), "publishedCount", published,
                "availableMetrics", METRICS.stream().sorted().toList());
    }

    @Transactional
    public Map<String, Object> create(DashboardConfigurationModels.CreateWidgetRequest request) {
        write(request.communityId());
        requireMetric(request.metricCode());
        String id = UUID.randomUUID().toString();
        LocalDateTime now = now();
        jdbc.update("""
                INSERT INTO dashboard_widget_configuration
                    (id,community_id,role_code,widget_code,widget_name,metric_code,position_code,
                     visible,refresh_interval_seconds,display_order,status,published_at,version,created_at,updated_at)
                VALUES (:id,:communityId,:roleCode,:widgetCode,:widgetName,:metricCode,:positionCode,
                        :visible,:refreshInterval,:displayOrder,'DRAFT',NULL,0,:now,:now)
                """, new MapSqlParameterSource("id", id)
                .addValue("communityId", request.communityId()).addValue("roleCode", normalizeRole(request.roleCode()))
                .addValue("widgetCode", request.widgetCode().trim()).addValue("widgetName", request.widgetName().trim())
                .addValue("metricCode", request.metricCode()).addValue("positionCode", request.positionCode())
                .addValue("visible", request.visible()).addValue("refreshInterval", request.refreshIntervalSeconds())
                .addValue("displayOrder", request.displayOrder()).addValue("now", now));
        audit.success(request.communityId(), "dashboard-widget:create", "dashboard-widget", id,
                Map.of("widgetCode", request.widgetCode(), "metricCode", request.metricCode(), "roleCode", request.roleCode()));
        return required(id, request.communityId());
    }

    @Transactional
    public Map<String, Object> update(String id, String communityId,
                                     DashboardConfigurationModels.UpdateWidgetRequest request) {
        write(communityId);
        requireMetric(request.metricCode());
        int changed = jdbc.update("""
                UPDATE dashboard_widget_configuration
                SET widget_name=:widgetName,metric_code=:metricCode,position_code=:positionCode,
                    visible=:visible,refresh_interval_seconds=:refreshInterval,status='DRAFT',
                    published_at=NULL,version=version+1,updated_at=:now
                WHERE id=:id AND community_id=:communityId AND version=:version
                """, new MapSqlParameterSource("widgetName", request.widgetName().trim())
                .addValue("metricCode", request.metricCode()).addValue("positionCode", request.positionCode())
                .addValue("visible", request.visible()).addValue("refreshInterval", request.refreshIntervalSeconds())
                .addValue("now", now()).addValue("id", id).addValue("communityId", communityId)
                .addValue("version", request.expectedVersion()));
        if (changed != 1) throw conflict("看板组件已被其他操作更新，请刷新后重试");
        audit.success(communityId, "dashboard-widget:update", "dashboard-widget", id,
                Map.of("metricCode", request.metricCode(), "expectedVersion", request.expectedVersion()));
        return required(id, communityId);
    }

    @Transactional
    public Map<String, Object> reorder(DashboardConfigurationModels.ScopeCommand request) {
        write(request.communityId());
        String role = normalizeRole(request.roleCode());
        LocalDateTime now = now();
        int order = 1;
        for (DashboardConfigurationModels.VersionReference widget : request.widgets()) {
            int changed = jdbc.update("""
                    UPDATE dashboard_widget_configuration
                    SET display_order=:displayOrder,status='DRAFT',published_at=NULL,
                        version=version+1,updated_at=:now
                    WHERE id=:id AND community_id=:communityId AND role_code=:roleCode AND version=:version
                    """, Map.of("displayOrder", order++, "now", now, "id", widget.id(),
                    "communityId", request.communityId(), "roleCode", role, "version", widget.expectedVersion()));
            if (changed != 1) throw conflict("看板排序版本冲突，请刷新后重试");
        }
        requireCompleteScope(request.communityId(), role, request.widgets().size());
        audit.success(request.communityId(), "dashboard-widget:reorder", "dashboard-configuration", role,
                Map.of("widgetCount", request.widgets().size()));
        return configurations(request.communityId(), role);
    }

    @Transactional
    public Map<String, Object> publish(DashboardConfigurationModels.ScopeCommand request) {
        write(request.communityId());
        String role = normalizeRole(request.roleCode());
        LocalDateTime now = now();
        for (DashboardConfigurationModels.VersionReference widget : request.widgets()) {
            int changed = jdbc.update("""
                    UPDATE dashboard_widget_configuration
                    SET status='PUBLISHED',published_at=:now,version=version+1,updated_at=:now
                    WHERE id=:id AND community_id=:communityId AND role_code=:roleCode AND version=:version
                    """, Map.of("now", now, "id", widget.id(), "communityId", request.communityId(),
                    "roleCode", role, "version", widget.expectedVersion()));
            if (changed != 1) throw conflict("看板发布版本冲突，请刷新后重试");
        }
        requireCompleteScope(request.communityId(), role, request.widgets().size());
        audit.success(request.communityId(), "dashboard-widget:publish", "dashboard-configuration", role,
                Map.of("widgetCount", request.widgets().size(), "publishedAt", now));
        return configurations(request.communityId(), role);
    }

    private void requireCompleteScope(String communityId, String roleCode, int requested) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM dashboard_widget_configuration
                WHERE community_id=:communityId AND role_code=:roleCode
                """, Map.of("communityId", communityId, "roleCode", roleCode), Integer.class);
        if (count == null || count != requested) throw conflict("请求必须包含当前角色范围内的全部看板组件");
    }

    private Map<String, Object> required(String id, String communityId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id,community_id,role_code,widget_code,widget_name,metric_code,position_code,
                       visible,refresh_interval_seconds,display_order,status,published_at,version,
                       created_at,updated_at
                FROM dashboard_widget_configuration WHERE id=:id AND community_id=:communityId
                """, Map.of("id", id, "communityId", communityId));
        if (rows.isEmpty()) throw new BusinessException("DASHBOARD_WIDGET_NOT_FOUND", "看板组件不存在", HttpStatus.NOT_FOUND);
        return new LinkedHashMap<>(rows.get(0));
    }

    private void requireMetric(String metricCode) {
        if (!METRICS.contains(metricCode)) {
            throw new BusinessException("DASHBOARD_METRIC_NOT_ALLOWED", "只能使用预定义看板指标", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    private String normalizeRole(String roleCode) {
        return roleCode == null || roleCode.isBlank() ? "ALL" : roleCode.trim();
    }

    private void read(String communityId) {
        security.requirePermission("dashboard:read");
        security.requireProject(communityId);
    }

    private void write(String communityId) {
        security.requirePermission("dashboard:configure");
        security.requireProject(communityId);
    }

    private BusinessException conflict(String message) {
        return new BusinessException("OPTIMISTIC_LOCK_CONFLICT", message, HttpStatus.CONFLICT);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
