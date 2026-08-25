package com.propertyops.pms.dashboard;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.propertyops.pms.security.SecurityContextService;
import com.propertyops.pms.report.ReportQueryEngine;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {
    private final NamedParameterJdbcTemplate jdbc;
    private final SecurityContextService security;
    private final ReportQueryEngine reports;

    public DashboardController(NamedParameterJdbcTemplate jdbc, SecurityContextService security,
                               ReportQueryEngine reports) {
        this.jdbc = jdbc;
        this.security = security;
        this.reports = reports;
    }

    @GetMapping
    Map<String, Object> dashboard(@RequestParam String communityId) {
        security.requirePermission("dashboard:read");
        security.requireProject(communityId);
        Map<String, Object> params = Map.of("communityId", communityId);
        Map<String, Object> counts = jdbc.queryForMap("""
                SELECT
                    SUM(CASE WHEN asset_type='ROOM' THEN 1 ELSE 0 END) AS rooms,
                    SUM(CASE WHEN asset_type='PARKING' THEN 1 ELSE 0 END) AS parking_spaces
                FROM asset WHERE community_id=:communityId
                """, params);
        counts.put("customers", count("customer", communityId));
        counts.put("meters", count("meter", communityId));
        counts.put("fee_definitions", count("fee_definition", communityId));
        counts.put("fee_standards", jdbc.queryForObject("""
                SELECT COUNT(*) FROM fee_standard fs
                JOIN fee_definition fd ON fd.id=fs.fee_definition_id
                WHERE fd.community_id=:communityId
                """, params, Long.class));
        counts.put("allocations", jdbc.queryForObject("""
                SELECT COUNT(*) FROM fee_allocation fa JOIN asset a ON a.id=fa.asset_id
                WHERE a.community_id=:communityId
                """, params, Long.class));

        Map<String, Object> finance = reports.dashboardFinance(communityId);

        Map<String, Object> quality = new LinkedHashMap<>();
        quality.put("orphan_customer_relations", jdbc.queryForObject("""
                SELECT COUNT(*) FROM customer_asset_relation r
                LEFT JOIN customer c ON c.id=r.customer_id LEFT JOIN asset a ON a.id=r.asset_id
                WHERE (c.id IS NULL OR a.id IS NULL)
                """, Map.of(), Long.class));
        quality.put("orphan_allocations", jdbc.queryForObject("""
                SELECT COUNT(*) FROM fee_allocation f
                LEFT JOIN fee_standard s ON s.id=f.fee_standard_id LEFT JOIN asset a ON a.id=f.asset_id
                WHERE s.id IS NULL OR a.id IS NULL
                """, Map.of(), Long.class));
        quality.put("synthetic", true);

        return Map.of(
                "communityId", communityId,
                "counts", counts,
                "finance", finance,
                "quality", quality,
                "adapters", Map.of("payment", "simulator", "invoice", "simulator", "iot", "simulator", "java110", "disabled")
        );
    }

    private Long count(String table, String communityId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE community_id=:communityId",
                Map.of("communityId", communityId), Long.class);
    }
}
