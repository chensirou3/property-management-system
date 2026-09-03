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
                    COUNT(CASE WHEN asset_type='ROOM' THEN 1 END) AS rooms,
                    COUNT(CASE WHEN asset_type='PARKING' THEN 1 END) AS parking_spaces
                FROM asset
                WHERE community_id=:communityId AND enabled=TRUE
                """, params);
        counts.put("customers", count("customer", communityId));
        counts.put("meters", count("meter", communityId));
        counts.put("fee_definitions", count("fee_definition", communityId));
        counts.put("fee_standards", jdbc.queryForObject("""
                SELECT COUNT(*) FROM fee_standard fs
                JOIN fee_definition fd ON fd.id=fs.fee_definition_id
                WHERE fd.community_id=:communityId
                """, params, Long.class));
        counts.putAll(jdbc.queryForMap("""
                SELECT COUNT(*) AS allocations,
                       COUNT(CASE WHEN target_type='ASSET' THEN 1 END) AS asset_allocations,
                       COUNT(CASE WHEN target_type='METER' THEN 1 END) AS meter_allocations
                FROM fee_allocation
                WHERE community_id=:communityId
                """, params));

        Map<String, Object> finance = reports.dashboardFinance(communityId);

        Map<String, Object> quality = new LinkedHashMap<>();
        quality.put("room_detail_mismatches", jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM asset a
                LEFT JOIN room_detail rd ON rd.asset_id=a.id
                WHERE a.community_id=:communityId AND a.enabled=TRUE
                  AND ((a.asset_type='ROOM' AND rd.asset_id IS NULL)
                    OR (a.asset_type<>'ROOM' AND rd.asset_id IS NOT NULL))
                """, params, Long.class));
        quality.put("orphan_customer_relations", jdbc.queryForObject("""
                SELECT COUNT(*) FROM customer_asset_relation r
                LEFT JOIN customer c ON c.id=r.customer_id AND c.community_id=r.community_id
                LEFT JOIN asset a ON a.id=r.asset_id AND a.community_id=r.community_id
                WHERE r.community_id=:communityId AND (c.id IS NULL OR a.id IS NULL)
                """, params, Long.class));
        quality.put("orphan_allocations", jdbc.queryForObject("""
                SELECT COUNT(*) FROM fee_allocation f
                LEFT JOIN fee_standard s ON s.id=f.fee_standard_id AND s.community_id=f.community_id
                LEFT JOIN asset a ON f.target_type='ASSET' AND a.id=f.asset_id AND a.community_id=f.community_id
                LEFT JOIN meter m ON f.target_type='METER' AND m.id=f.meter_id AND m.community_id=f.community_id
                WHERE f.community_id=:communityId
                  AND (s.id IS NULL
                    OR f.target_type IS NULL OR f.target_type NOT IN ('ASSET','METER')
                    OR (f.target_type='ASSET'
                        AND (f.asset_id IS NULL OR f.meter_id IS NOT NULL OR a.id IS NULL))
                    OR (f.target_type='METER'
                        AND (f.meter_id IS NULL OR f.asset_id IS NOT NULL OR m.id IS NULL)))
                """, params, Long.class));
        quality.put("synthetic", true);

        return Map.of(
                "communityId", communityId,
                "counts", counts,
                "finance", finance,
                "quality", quality,
                "adapters", Map.of("payment", "simulator", "invoice", "simulator", "bank", "simulator",
                        "iot", "simulator", "java110", "disabled")
        );
    }

    private Long count(String table, String communityId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE community_id=:communityId",
                Map.of("communityId", communityId), Long.class);
    }
}
