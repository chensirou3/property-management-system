package com.propertyops.pms.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class DashboardIntegrationTest {
    private static final String PRIMARY_PROJECT = "30000000-0000-0000-0000-000000000001";
    private static final String ISOLATED_PROJECT = "30000000-0000-0000-0000-000000000002";
    private static final String ADMIN_USERNAME = "dashboard-integration-admin";
    private static final String ADMIN_PASSWORD = "DashboardFixture-2026!";

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("pms_dashboard_test").withUsername("pms_test").withPassword("pms_test_password");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("pms.bootstrap.admin-username", () -> ADMIN_USERNAME);
        registry.add("pms.bootstrap.admin-password", () -> ADMIN_PASSWORD);
        registry.add("pms.security.jwt-secret", () -> "dashboard-integration-only-jwt-secret-with-more-than-32-characters");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired NamedParameterJdbcTemplate jdbc;

    @Test
    void reportsTargetAwareProjectScopedQualityWithoutFixedInventoryTargets() throws Exception {
        String token = login();

        JsonNode primary = dashboard(token, PRIMARY_PROJECT);
        assertThat(primary.path("counts").path("rooms").asInt()).isEqualTo(359);
        assertThat(primary.path("counts").path("parking_spaces").asInt()).isEqualTo(250);
        assertThat(primary.path("counts").path("allocations").asInt()).isEqualTo(773);
        assertThat(primary.path("counts").path("asset_allocations").asInt()).isEqualTo(743);
        assertThat(primary.path("counts").path("meter_allocations").asInt()).isEqualTo(30);
        assertThat(primary.path("quality").path("room_detail_mismatches").asInt()).isZero();
        assertThat(primary.path("quality").path("orphan_customer_relations").asInt()).isZero();
        assertThat(primary.path("quality").path("orphan_allocations").asInt()).isZero();

        JsonNode isolated = dashboard(token, ISOLATED_PROJECT);
        assertThat(isolated.path("counts").path("rooms").asInt()).isEqualTo(1);
        assertThat(isolated.path("counts").path("parking_spaces").asInt()).isEqualTo(1);
        assertThat(isolated.path("quality").path("room_detail_mismatches").asInt()).isZero();
        assertThat(isolated.path("quality").path("orphan_customer_relations").asInt()).isZero();
        assertThat(isolated.path("quality").path("orphan_allocations").asInt()).isZero();

        String incompleteRoomId = UUID.randomUUID().toString();
        String archivedParkingId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO asset
                    (id,community_id,asset_type,code,display_name,building_area,usable_area,occupancy_status,
                     operation_status,enabled,version,created_at,updated_at)
                VALUES (:id,:communityId,'ROOM',:code,'隔离项目缺少明细测试房屋',88,70,'VACANT',
                        'NORMAL',TRUE,0,:now,:now)
                """, Map.of("id", incompleteRoomId, "communityId", ISOLATED_PROJECT,
                "code", "ROOM-MISMATCH-" + incompleteRoomId, "now", LocalDateTime.now()));
        jdbc.update("""
                INSERT INTO asset
                    (id,community_id,asset_type,code,display_name,building_area,usable_area,occupancy_status,
                     operation_status,enabled,version,created_at,updated_at)
                VALUES (:id,:communityId,'PARKING',:code,'隔离项目已归档测试车位',12.5,12.5,'VACANT',
                        'INACTIVE',FALSE,0,:now,:now)
                """, Map.of("id", archivedParkingId, "communityId", ISOLATED_PROJECT,
                "code", "PARKING-ARCHIVED-" + archivedParkingId, "now", LocalDateTime.now()));
        try {
            isolated = dashboard(token, ISOLATED_PROJECT);
            assertThat(isolated.path("counts").path("rooms").asInt()).isEqualTo(2);
            assertThat(isolated.path("counts").path("parking_spaces").asInt()).isEqualTo(1);
            assertThat(isolated.path("quality").path("room_detail_mismatches").asInt()).isEqualTo(1);

            jdbc.update("""
                    UPDATE asset
                    SET enabled=FALSE, operation_status='INACTIVE', version=version+1, updated_at=:now
                    WHERE id=:id
                    """, Map.of("id", incompleteRoomId, "now", LocalDateTime.now()));

            isolated = dashboard(token, ISOLATED_PROJECT);
            assertThat(isolated.path("counts").path("rooms").asInt()).isEqualTo(1);
            assertThat(isolated.path("counts").path("parking_spaces").asInt()).isEqualTo(1);
            assertThat(isolated.path("quality").path("room_detail_mismatches").asInt()).isZero();

            primary = dashboard(token, PRIMARY_PROJECT);
            assertThat(primary.path("counts").path("rooms").asInt()).isEqualTo(359);
            assertThat(primary.path("counts").path("parking_spaces").asInt()).isEqualTo(250);
            assertThat(primary.path("quality").path("room_detail_mismatches").asInt()).isZero();
        } finally {
            jdbc.update("DELETE FROM asset WHERE id=:id", Map.of("id", incompleteRoomId));
            jdbc.update("DELETE FROM asset WHERE id=:id", Map.of("id", archivedParkingId));
        }
    }

    private JsonNode dashboard(String token, String projectId) throws Exception {
        String response = mockMvc.perform(get("/api/v1/dashboard")
                        .header("Authorization", "Bearer " + token)
                        .param("communityId", projectId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private String login() throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", ADMIN_USERNAME, "password", ADMIN_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("accessToken").asText();
    }
}
