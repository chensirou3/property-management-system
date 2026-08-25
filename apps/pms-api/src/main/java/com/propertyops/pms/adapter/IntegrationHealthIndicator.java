package com.propertyops.pms.adapter;

import java.util.Map;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

@Component("integrations")
public class IntegrationHealthIndicator implements HealthIndicator {
    private final NamedParameterJdbcTemplate jdbc;

    public IntegrationHealthIndicator(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Health health() {
        try {
            Integer invalidPolicies = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM integration_adapter_policy
                    WHERE production_ready=TRUE OR mode NOT IN ('SIMULATOR','DISABLED')
                    """, Map.of(), Integer.class);
            Integer openDeadLetters = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM integration_dead_letter WHERE status='OPEN'
                    """, Map.of(), Integer.class);
            if (invalidPolicies != null && invalidPolicies > 0) {
                return Health.down().withDetail("invalidPolicies", invalidPolicies).build();
            }
            return Health.up().withDetail("mode", "SIMULATOR_OR_DISABLED")
                    .withDetail("productionConnected", false)
                    .withDetail("openDeadLetters", openDeadLetters == null ? 0 : openDeadLetters).build();
        } catch (RuntimeException exception) {
            return Health.down(exception).build();
        }
    }
}
