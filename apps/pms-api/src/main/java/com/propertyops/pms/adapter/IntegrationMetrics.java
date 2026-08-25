package com.propertyops.pms.adapter;

import java.util.Map;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class IntegrationMetrics {
    private final MeterRegistry registry;
    private final NamedParameterJdbcTemplate jdbc;

    public IntegrationMetrics(MeterRegistry registry, NamedParameterJdbcTemplate jdbc) {
        this.registry = registry;
        this.jdbc = jdbc;
    }

    @PostConstruct
    void register() {
        for (String status : new String[]{"PENDING", "RETRY", "PUBLISHED", "DEAD_LETTER"}) {
            Gauge.builder("pms.integration.outbox.events", () -> count(
                            "SELECT COUNT(*) FROM outbox_event WHERE status=:status", Map.of("status", status)))
                    .description("Outbox events by delivery status").tag("status", status).register(registry);
        }
        Gauge.builder("pms.integration.dead.letters.open", () -> count(
                        "SELECT COUNT(*) FROM integration_dead_letter WHERE status='OPEN'", Map.of()))
                .description("Open integration dead letters").register(registry);
        Gauge.builder("pms.integration.callbacks.replayed", () -> count(
                        "SELECT COALESCE(SUM(replay_count),0) FROM integration_callback_inbox", Map.of()))
                .description("Verified callback replay count").register(registry);
    }

    private double count(String sql, Map<String, ?> params) {
        try {
            Number value = jdbc.queryForObject(sql, params, Number.class);
            return value == null ? 0 : value.doubleValue();
        } catch (RuntimeException ignored) {
            return Double.NaN;
        }
    }
}
