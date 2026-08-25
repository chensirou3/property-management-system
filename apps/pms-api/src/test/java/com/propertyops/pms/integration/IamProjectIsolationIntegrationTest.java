package com.propertyops.pms.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
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
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class IamProjectIsolationIntegrationTest {
    private static final String SEEDED_ENTERPRISE = "31000000-0000-0000-0000-000000000001";
    private static final String PRIMARY_PROJECT = "30000000-0000-0000-0000-000000000001";
    private static final String ISOLATED_PROJECT = "30000000-0000-0000-0000-000000000002";
    private static final String PROJECT_MANAGER_ROLE = "10000000-0000-0000-0000-000000000002";
    private static final String SEEDED_EMPLOYEE = "34000000-0000-0000-0000-000000000001";
    private static final String ADMIN_USERNAME = "integration-admin";
    private static final String ADMIN_PASSWORD = "integration-admin-password";
    private static final String EMPLOYEE_USERNAME = "integration-project-manager";
    private static final String EMPLOYEE_PASSWORD = "Integration-Employee-2026!";
    private static final String CHANGED_PASSWORD = "Changed-Employee-2026!";

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("pms_iam_test")
            .withUsername("pms_test")
            .withPassword("pms_test_password");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("pms.bootstrap.admin-username", () -> ADMIN_USERNAME);
        registry.add("pms.bootstrap.admin-password", () -> ADMIN_PASSWORD);
        registry.add("pms.security.jwt-secret", () -> "integration-only-jwt-secret-with-more-than-32-characters");
        registry.add("pms.security.login-max-failures", () -> "3");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired NamedParameterJdbcTemplate jdbc;

    @Test
    void openApiContractMatchesCommittedSnapshot() throws Exception {
        String actual = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        try (var expectedStream = getClass().getResourceAsStream("/openapi-contract.json")) {
            assertThat(expectedStream).as("committed OpenAPI contract snapshot").isNotNull();
            JsonNode actualContract = objectMapper.readTree(actual);
            JsonNode expectedContract = objectMapper.readTree(expectedStream);
            ((com.fasterxml.jackson.databind.node.ObjectNode) actualContract).remove("servers");
            ((com.fasterxml.jackson.databind.node.ObjectNode) expectedContract).remove("servers");
            assertThat(actualContract).isEqualTo(expectedContract);
        }
    }

    @Test
    void ordinaryAccountSeesOnlyGrantedProjectAndCannotUseIamAdministration() throws Exception {
        String adminToken = login(ADMIN_USERNAME, ADMIN_PASSWORD);

        mockMvc.perform(get("/api/v1/iam/projects").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        String request = objectMapper.writeValueAsString(Map.of(
                "username", EMPLOYEE_USERNAME,
                "password", EMPLOYEE_PASSWORD,
                "displayName", "隔离测试项目经理",
                "employeeId", SEEDED_EMPLOYEE,
                "enabled", true,
                "roleIds", Set.of(PROJECT_MANAGER_ROLE),
                "projectIds", Set.of(PRIMARY_PROJECT)
        ));
        mockMvc.perform(post("/api/v1/iam/users")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(EMPLOYEE_USERNAME))
                .andExpect(jsonPath("$.projectIds.length()").value(1))
                .andExpect(jsonPath("$.passwordChangeRequired").value(true));

        String temporaryToken = login(EMPLOYEE_USERNAME, EMPLOYEE_PASSWORD);
        mockMvc.perform(get("/api/v1/data/communities")
                        .header("Authorization", bearer(temporaryToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));

        String passwordResponse = mockMvc.perform(put("/api/v1/auth/change-password")
                        .header("Authorization", bearer(temporaryToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "currentPassword", EMPLOYEE_PASSWORD,
                                "newPassword", CHANGED_PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.passwordChangeRequired").value(false))
                .andReturn().getResponse().getContentAsString();
        String employeeToken = objectMapper.readTree(passwordResponse).path("accessToken").asText();

        mockMvc.perform(get("/api/v1/data/communities")
                        .header("Authorization", bearer(employeeToken))
                        .param("page", "1").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(PRIMARY_PROJECT));

        mockMvc.perform(get("/api/v1/data/assets")
                        .header("Authorization", bearer(employeeToken))
                        .param("communityId", PRIMARY_PROJECT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(609));

        mockMvc.perform(get("/api/v1/data/assets")
                        .header("Authorization", bearer(employeeToken))
                        .param("communityId", ISOLATED_PROJECT))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PROJECT_ACCESS_DENIED"));

        mockMvc.perform(get("/api/v1/iam/users").header("Authorization", bearer(employeeToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));

        mockMvc.perform(put("/api/v1/iam/users/{id}", userId(EMPLOYEE_USERNAME))
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "displayName", "隔离测试项目经理",
                                "enabled", true,
                                "passwordChangeRequired", false,
                                "roleIds", Set.of(PROJECT_MANAGER_ROLE),
                                "projectIds", Set.of(PRIMARY_PROJECT),
                                "expectedVersion", 1))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/data/communities")
                        .header("Authorization", bearer(employeeToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void propertyArchiveLifecycleIsIdempotentScopedAndAudited() throws Exception {
        String adminToken = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String roomId = "40000000-0000-0000-0000-000000000001";
        String secondRoomId = "40000000-0000-0000-0000-000000000002";
        String isolatedRoomId = "35200000-0000-0000-0000-000000000001";
        String isolatedBuildingId = "35000000-0000-0000-0000-000000000001";

        getJsonRequest(adminToken, "/api/v1/property/tree?communityId=" + PRIMARY_PROJECT + "&assetType=ROOM")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetCount").value(359))
                .andExpect(jsonPath("$.buildings.length()").value(8));
        getJsonRequest(adminToken, "/api/v1/property/tree?communityId=" + ISOLATED_PROJECT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grids.length()").value(1))
                .andExpect(jsonPath("$.assets.length()").value(2));

        JsonNode grid = postJsonOk(adminToken, "/api/v1/data/grids?communityId=" + PRIMARY_PROJECT,
                body("code", "E2E-GRID-" + suffix, "name", "档案生命周期网格 " + suffix,
                        "sort_order", 10, "status", "ACTIVE"));
        mockMvc.perform(delete("/api/v1/data/grids/{id}", grid.path("id").asText())
                        .header("Authorization", bearer(adminToken)).param("communityId", PRIMARY_PROJECT)
                        .param("version", "0"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INACTIVE"));

        JsonNode unit = postJsonOk(adminToken, "/api/v1/data/units?communityId=" + PRIMARY_PROJECT,
                body("building_id", "31000000-0000-0000-0000-000000000001",
                        "code", "E2E-U-" + suffix, "name", "档案生命周期单元 " + suffix, "status", "ACTIVE"));
        mockMvc.perform(delete("/api/v1/data/units/{id}", unit.path("id").asText())
                        .header("Authorization", bearer(adminToken)).param("communityId", PRIMARY_PROJECT)
                        .param("version", "0"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INACTIVE"));

        JsonNode temporaryAsset = postJsonOk(adminToken, "/api/v1/data/assets?communityId=" + PRIMARY_PROJECT,
                body("building_id", "31000000-0000-0000-0000-000000000001", "asset_type", "ROOM",
                        "code", "E2E-R-" + suffix, "display_name", "档案生命周期房屋 " + suffix,
                        "floor_no", "1", "building_area", 80, "usable_area", 70,
                        "occupancy_status", "VACANT", "operation_status", "NORMAL", "enabled", true));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM room_detail WHERE asset_id=:id",
                Map.of("id", temporaryAsset.path("id").asText()), Long.class)).isEqualTo(1);
        mockMvc.perform(delete("/api/v1/data/assets/{id}", temporaryAsset.path("id").asText())
                        .header("Authorization", bearer(adminToken)).param("communityId", PRIMARY_PROJECT)
                        .param("version", "0"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));

        JsonNode room = getJsonOk(adminToken,
                "/api/v1/property/assets/" + roomId + "?communityId=" + PRIMARY_PROJECT);
        assertThat(room.path("asset").path("code").asText()).isEqualTo("R0001");
        long roomVersion = room.path("asset").path("version").asLong();

        JsonNode customer = postJsonOk(adminToken, "/api/v1/data/customers?communityId=" + PRIMARY_PROJECT,
                body("customer_no", "E2E-CUS-" + suffix, "display_name", "关系生命周期客户 " + suffix,
                        "customer_type", "PERSON", "customer_class", "OWNER",
                        "mobile_masked", "E2E-***-" + suffix, "gender", "UNKNOWN", "status", "ACTIVE"));
        String customerId = customer.path("id").asText();

        postJsonRequest(adminToken, "/api/v1/property/relations",
                body("communityId", PRIMARY_PROJECT, "customerId", customerId, "assetId", isolatedRoomId,
                        "relationType", "OCCUPANT", "primaryRelation", false,
                        "startDate", "2026-08-25", "reason", "跨项目拒绝样本"), "cross-project-" + suffix)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROPERTY_DATA_NOT_FOUND"));

        postJsonRequest(adminToken, "/api/v1/data/assets?communityId=" + PRIMARY_PROJECT,
                body("grid_id", null, "building_id", isolatedBuildingId, "asset_type", "ROOM",
                        "code", "CROSS-" + suffix, "display_name", "跨项目无效房屋",
                        "building_area", 80, "usable_area", 70, "occupancy_status", "VACANT",
                        "operation_status", "NORMAL", "enabled", true))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PROPERTY_INVALID_REFERENCE"));

        String relationKey = "relation-start-" + suffix;
        var relationRequest = body("communityId", PRIMARY_PROJECT, "customerId", customerId,
                "assetId", secondRoomId, "relationType", "OCCUPANT", "primaryRelation", false,
                "startDate", "2026-08-25", "reason", "入住登记");
        String relationResponse = postJsonRequest(adminToken, "/api/v1/property/relations", relationRequest, relationKey)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.replayed").value(false))
                .andReturn().getResponse().getContentAsString();
        String relationId = objectMapper.readTree(relationResponse).path("relationId").asText();
        postJsonRequest(adminToken, "/api/v1/property/relations", relationRequest, relationKey)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.relationId").value(relationId))
                .andExpect(jsonPath("$.replayed").value(true));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_asset_relation WHERE id=:id",
                Map.of("id", relationId), Long.class)).isEqualTo(1);

        postJsonRequest(adminToken, "/api/v1/property/relations/" + relationId + ":end",
                body("communityId", PRIMARY_PROJECT, "effectiveDate", "2026-08-31",
                        "reason", "结束入住", "expectedVersion", 0), "relation-end-" + suffix)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENDED"));

        var transferRequest = body("communityId", PRIMARY_PROJECT, "newOwnerCustomerId", customerId,
                "effectiveDate", "2026-09-01", "reason", "产权变更验收", "expectedAssetVersion", roomVersion);
        String transferKey = "ownership-transfer-" + suffix;
        postJsonRequest(adminToken, "/api/v1/property/assets/" + roomId + ":transfer", transferRequest, transferKey)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value(customerId))
                .andExpect(jsonPath("$.replayed").value(false));
        postJsonRequest(adminToken, "/api/v1/property/assets/" + roomId + ":transfer", transferRequest, transferKey)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));

        JsonNode transferredRoom = getJsonOk(adminToken,
                "/api/v1/property/assets/" + roomId + "?communityId=" + PRIMARY_PROJECT);
        assertThat(transferredRoom.path("asset").path("version").asLong()).isEqualTo(roomVersion + 1);
        assertThat(transferredRoom.path("timeline").get(0).path("eventType").asText())
                .isEqualTo("OWNERSHIP_TRANSFERRED");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM customer_asset_relation
                WHERE asset_id=:assetId AND customer_id=:customerId AND relation_type='OWNER'
                  AND status='ACTIVE' AND end_date IS NULL
                """, Map.of("assetId", roomId, "customerId", customerId), Long.class)).isEqualTo(1);

        mockMvc.perform(delete("/api/v1/data/assets/{id}", roomId)
                        .header("Authorization", bearer(adminToken))
                        .param("communityId", PRIMARY_PROJECT)
                        .param("version", String.valueOf(roomVersion + 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROPERTY_RESOURCE_IN_USE"));

        getJsonRequest(adminToken, "/api/v1/property/customers/" + customerId + "?communityId=" + PRIMARY_PROJECT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.relations.length()").value(2))
                .andExpect(jsonPath("$.timeline.length()").value(3));

        postJsonRequest(adminToken, "/api/v1/property/imports:validate",
                body("communityId", PRIMARY_PROJECT, "resource", "ASSET", "rows", java.util.List.of(
                        body("assetType", "ROOM", "code", "IMP-" + suffix, "displayName", "导入校验房屋",
                                "buildingId", "31000000-0000-0000-0000-000000000001",
                                "buildingArea", "70", "usableArea", "80"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readyToImport").value(false))
                .andExpect(jsonPath("$.invalidRows").value(1))
                .andExpect(jsonPath("$.rows[0].errors[0]").value("usableArea 不能大于 buildingArea"));
        postJsonRequest(adminToken, "/api/v1/property/imports:validate",
                body("communityId", PRIMARY_PROJECT, "resource", "GRID", "rows", java.util.List.of(
                        body("code", "IMP-GRID-" + suffix, "name", "可导入网格", "status", "ACTIVE", "sortOrder", 10))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readyToImport").value(true))
                .andExpect(jsonPath("$.validRows").value(1));
        getJsonRequest(adminToken, "/api/v1/property/imports/template?resource=ASSET")
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).contains("assetType,code,displayName"));

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_event
                WHERE community_id=:communityId AND action_code IN
                    ('property-relation:start','property-relation:end','property-ownership:transfer')
                  AND resource_id IN (:relationId, :assetId)
                """, Map.of("communityId", PRIMARY_PROJECT, "relationId", relationId, "assetId", roomId), Long.class))
                .isEqualTo(3);
    }

    @Test
    void repeatedInvalidLoginsAreRateLimitedAndAuditedWithoutRawIdentity() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "username", "missing-rate-limit-account", "password", "Wrong-Password-2026!"));
        for (int attempt = 1; attempt <= 2; attempt++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .with(request -> { request.setRemoteAddr("198.51.100.24"); return request; })
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }
        mockMvc.perform(post("/api/v1/auth/login")
                        .with(request -> { request.setRemoteAddr("198.51.100.24"); return request; })
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_LOGIN_ATTEMPTS"));
        mockMvc.perform(post("/api/v1/auth/login")
                        .with(request -> { request.setRemoteAddr("198.51.100.24"); return request; })
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests());

        Long failures = jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_event WHERE action_code='AUTH_LOGIN_FAILURE'
                """, Map.of(), Long.class);
        Long denied = jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_event WHERE action_code='AUTH_LOGIN_RATE_LIMITED'
                """, Map.of(), Long.class);
        Long leaked = jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_event WHERE detail_json LIKE '%missing-rate-limit-account%'
                """, Map.of(), Long.class);
        assertThat(failures).isGreaterThanOrEqualTo(3);
        assertThat(denied).isGreaterThanOrEqualTo(1);
        assertThat(leaked).isZero();
    }

    @Test
    void administratorCompletesIamLifecycleWithValidationConflictsAndProtectedTransitions() throws Exception {
        String adminToken = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String enterpriseCode = "E2E_ENT_" + suffix;
        String organizationCode = "E2E_ORG_" + suffix;
        String childOrganizationCode = "E2E_CHILD_" + suffix;
        String positionCode = "E2E_POS_" + suffix;
        String employeeNo = "E2E_EMP_" + suffix;
        String roleCode = "E2E_ROLE_" + suffix;
        String username = "integration-lifecycle-" + suffix.toLowerCase();
        String initialPassword = "Lifecycle-Initial-2026!";
        String resetPassword = "Lifecycle-Reset-2026!";

        postJsonRequest(adminToken, "/api/v1/iam/enterprises",
                body("code", "invalid code", "name", "校验失败企业"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        JsonNode enterprise = postJsonOk(adminToken, "/api/v1/iam/enterprises",
                body("code", enterpriseCode, "name", "生命周期测试企业"));
        assertThat(enterprise.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(enterprise.path("version").asLong()).isZero();
        String enterpriseId = enterprise.path("id").asText();

        postJsonRequest(adminToken, "/api/v1/iam/enterprises",
                body("code", enterpriseCode, "name", "重复企业"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IAM_DUPLICATE_OR_INVALID_REFERENCE"));

        enterprise = putJsonOk(adminToken, "/api/v1/iam/enterprises/" + enterpriseId,
                body("name", "生命周期测试企业（已更新）", "status", "ACTIVE", "expectedVersion", 0));
        assertThat(enterprise.path("version").asLong()).isEqualTo(1);
        putJsonRequest(adminToken, "/api/v1/iam/enterprises/" + enterpriseId,
                body("name", "过期覆盖", "status", "ACTIVE", "expectedVersion", 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPTIMISTIC_LOCK_CONFLICT"));

        postJsonRequest(adminToken, "/api/v1/iam/organizations",
                body("enterpriseId", enterpriseId, "communityId", PRIMARY_PROJECT,
                        "code", "INVALID_PROJECT_" + suffix, "name", "跨企业项目",
                        "organizationType", "PROJECT", "sortOrder", 1))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IAM_INVALID_REFERENCE"));

        JsonNode rootOrganization = postJsonOk(adminToken, "/api/v1/iam/organizations",
                body("enterpriseId", SEEDED_ENTERPRISE, "code", organizationCode,
                        "name", "生命周期测试总部", "organizationType", "COMPANY", "sortOrder", 10));
        String rootOrganizationId = rootOrganization.path("id").asText();
        JsonNode childOrganization = postJsonOk(adminToken, "/api/v1/iam/organizations",
                body("enterpriseId", SEEDED_ENTERPRISE, "parentId", rootOrganizationId,
                        "code", childOrganizationCode, "name", "生命周期测试部门",
                        "organizationType", "DEPARTMENT", "sortOrder", 20));
        String childOrganizationId = childOrganization.path("id").asText();

        postJsonRequest(adminToken, "/api/v1/iam/organizations",
                body("enterpriseId", SEEDED_ENTERPRISE, "code", organizationCode,
                        "name", "重复组织", "organizationType", "DEPARTMENT", "sortOrder", 30))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IAM_DUPLICATE_OR_INVALID_REFERENCE"));
        putJsonRequest(adminToken, "/api/v1/iam/organizations/" + rootOrganizationId,
                body("parentId", childOrganizationId, "name", "循环总部",
                        "organizationType", "COMPANY", "sortOrder", 10,
                        "status", "ACTIVE", "expectedVersion", 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORGANIZATION_CYCLE"));

        postJsonRequest(adminToken, "/api/v1/iam/positions",
                body("enterpriseId", enterpriseId,
                        "organizationId", "32000000-0000-0000-0000-000000000001",
                        "code", "INVALID_POSITION_" + suffix, "name", "跨企业岗位"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IAM_INVALID_REFERENCE"));
        JsonNode position = postJsonOk(adminToken, "/api/v1/iam/positions",
                body("enterpriseId", SEEDED_ENTERPRISE, "organizationId", rootOrganizationId,
                        "code", positionCode, "name", "生命周期测试岗位", "description", "集成测试岗位"));
        String positionId = position.path("id").asText();

        postJsonRequest(adminToken, "/api/v1/iam/employees",
                body("enterpriseId", SEEDED_ENTERPRISE, "organizationId", childOrganizationId,
                        "positionId", positionId, "employeeNo", "INVALID_EMP_" + suffix,
                        "displayName", "岗位组织不一致"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IAM_INVALID_REFERENCE"));
        JsonNode employee = postJsonOk(adminToken, "/api/v1/iam/employees",
                body("enterpriseId", SEEDED_ENTERPRISE, "organizationId", rootOrganizationId,
                        "positionId", positionId, "employeeNo", employeeNo,
                        "displayName", "生命周期测试人员", "mobileMasked", "139****2026",
                        "hireDate", "2026-08-01"));
        String employeeId = employee.path("id").asText();

        postJsonRequest(adminToken, "/api/v1/iam/employees",
                body("enterpriseId", SEEDED_ENTERPRISE, "organizationId", rootOrganizationId,
                        "positionId", positionId, "employeeNo", employeeNo, "displayName", "重复人员"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IAM_DUPLICATE_OR_INVALID_REFERENCE"));
        putJsonRequest(adminToken, "/api/v1/iam/employees/" + employeeId,
                body("organizationId", rootOrganizationId, "positionId", positionId,
                        "displayName", "生命周期测试人员", "mobileMasked", "139****2026",
                        "employmentStatus", "LEFT", "hireDate", "2026-08-01", "expectedVersion", 0))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LEAVE_DATE_REQUIRED"));
        String iamReadPermission = jdbc.queryForObject(
                "SELECT id FROM sys_permission WHERE code='iam:read'", Map.of(), String.class);
        postJsonRequest(adminToken, "/api/v1/iam/roles",
                body("enterpriseId", enterpriseId, "code", "INVALID_ROLE_" + suffix,
                        "name", "无效权限角色", "permissionIds", Set.of(UUID.randomUUID().toString())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IAM_INVALID_REFERENCE"));
        JsonNode foreignRole = postJsonOk(adminToken, "/api/v1/iam/roles",
                body("enterpriseId", enterpriseId, "code", "FOREIGN_ROLE_" + suffix,
                        "name", "跨企业测试角色", "permissionIds", Set.of(iamReadPermission)));
        String foreignRoleId = foreignRole.path("id").asText();
        JsonNode role = postJsonOk(adminToken, "/api/v1/iam/roles",
                body("enterpriseId", SEEDED_ENTERPRISE, "code", roleCode, "name", "生命周期测试角色",
                        "description", "仅用于集成测试", "permissionIds", Set.of(iamReadPermission)));
        String roleId = role.path("id").asText();

        postJsonRequest(adminToken, "/api/v1/iam/roles",
                body("enterpriseId", SEEDED_ENTERPRISE, "code", roleCode, "name", "重复角色",
                        "permissionIds", Set.of(iamReadPermission)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IAM_DUPLICATE_OR_INVALID_REFERENCE"));
        role = putJsonOk(adminToken, "/api/v1/iam/roles/" + roleId,
                body("name", "生命周期测试角色（已更新）", "description", "已更新",
                        "enabled", true, "permissionIds", Set.of(iamReadPermission), "expectedVersion", 0));
        assertThat(role.path("version").asLong()).isEqualTo(1);
        putJsonRequest(adminToken, "/api/v1/iam/roles/" + roleId,
                body("name", "过期角色", "description", "过期",
                        "enabled", true, "permissionIds", Set.of(iamReadPermission), "expectedVersion", 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPTIMISTIC_LOCK_CONFLICT"));

        postJsonRequest(adminToken, "/api/v1/iam/users",
                body("username", "no-role-" + suffix.toLowerCase(), "password", initialPassword,
                        "displayName", "无角色账号", "enabled", true,
                        "roleIds", Set.of(), "projectIds", Set.of(PRIMARY_PROJECT)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ROLE_REQUIRED"));
        postJsonRequest(adminToken, "/api/v1/iam/users",
                body("username", "foreign-role-" + suffix.toLowerCase(), "password", initialPassword,
                        "displayName", "跨企业角色账号", "employeeId", employeeId, "enabled", true,
                        "roleIds", Set.of(foreignRoleId), "projectIds", Set.of(PRIMARY_PROJECT)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IAM_INVALID_REFERENCE"));
        JsonNode user = postJsonOk(adminToken, "/api/v1/iam/users",
                body("username", username, "password", initialPassword,
                        "displayName", "生命周期测试账号", "employeeId", employeeId, "enabled", true,
                        "roleIds", Set.of(roleId), "projectIds", Set.of(PRIMARY_PROJECT)));
        String userId = user.path("id").asText();
        assertThat(user.path("passwordChangeRequired").asBoolean()).isTrue();

        postJsonRequest(adminToken, "/api/v1/iam/users",
                body("username", username, "password", initialPassword,
                        "displayName", "重复账号", "employeeId", employeeId, "enabled", true,
                        "roleIds", Set.of(roleId), "projectIds", Set.of(PRIMARY_PROJECT)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IAM_DUPLICATE_OR_INVALID_REFERENCE"));
        user = putJsonOk(adminToken, "/api/v1/iam/users/" + userId,
                body("displayName", "生命周期测试账号（已更新）", "employeeId", employeeId,
                        "enabled", true, "passwordChangeRequired", false,
                        "roleIds", Set.of(roleId), "projectIds", Set.of(PRIMARY_PROJECT), "expectedVersion", 0));
        assertThat(user.path("version").asLong()).isEqualTo(1);
        putJsonRequest(adminToken, "/api/v1/iam/users/" + userId,
                body("displayName", "过期账号", "employeeId", employeeId,
                        "enabled", true, "passwordChangeRequired", false,
                        "roleIds", Set.of(roleId), "projectIds", Set.of(PRIMARY_PROJECT), "expectedVersion", 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPTIMISTIC_LOCK_CONFLICT"));

        user = putJsonOk(adminToken, "/api/v1/iam/users/" + userId + "/password",
                body("password", resetPassword, "requireChange", false, "expectedVersion", 1));
        assertThat(user.path("version").asLong()).isEqualTo(2);
        String lifecycleToken = login(username, resetPassword);
        getJsonRequest(lifecycleToken, "/api/v1/iam/enterprises")
                .andExpect(status().isOk());

        JsonNode users = getJsonOk(adminToken, "/api/v1/iam/users");
        JsonNode currentAdmin = findByText(users, "username", ADMIN_USERNAME);
        putJsonRequest(adminToken, "/api/v1/iam/users/" + currentAdmin.path("id").asText(),
                body("displayName", currentAdmin.path("displayName").asText(),
                        "employeeId", nullableText(currentAdmin.path("employeeId")), "enabled", false,
                        "passwordChangeRequired", currentAdmin.path("passwordChangeRequired").asBoolean(),
                        "roleIds", currentAdmin.path("roleIds"), "projectIds", currentAdmin.path("projectIds"),
                        "expectedVersion", currentAdmin.path("version").asLong()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SELF_DISABLE_FORBIDDEN"));

        JsonNode roles = getJsonOk(adminToken, "/api/v1/iam/roles");
        JsonNode platformAdminRole = findByText(roles, "code", "PLATFORM_ADMIN");
        putJsonRequest(adminToken, "/api/v1/iam/roles/" + platformAdminRole.path("id").asText(),
                body("name", platformAdminRole.path("name").asText(),
                        "description", nullableText(platformAdminRole.path("description")), "enabled", false,
                        "permissionIds", platformAdminRole.path("permissionIds"),
                        "expectedVersion", platformAdminRole.path("version").asLong()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLATFORM_ADMIN_PROTECTED"));

        putJsonRequest(adminToken, "/api/v1/iam/roles/" + roleId,
                body("name", "仍在使用的角色", "description", "不能停用",
                        "enabled", false, "permissionIds", Set.of(iamReadPermission), "expectedVersion", 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IAM_RESOURCE_IN_USE"));
        putJsonRequest(adminToken, "/api/v1/iam/employees/" + employeeId,
                body("organizationId", rootOrganizationId, "positionId", positionId,
                        "displayName", "仍有账号的人员", "mobileMasked", "139****2026",
                        "employmentStatus", "LEFT", "hireDate", "2026-08-01",
                        "leaveDate", "2026-08-24", "expectedVersion", 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IAM_RESOURCE_IN_USE"));
        putJsonRequest(adminToken, "/api/v1/iam/positions/" + positionId,
                body("organizationId", rootOrganizationId, "name", "仍有人员的岗位",
                        "description", "不能停用", "status", "INACTIVE", "expectedVersion", 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IAM_RESOURCE_IN_USE"));
        putJsonRequest(adminToken, "/api/v1/iam/organizations/" + rootOrganizationId,
                body("name", "仍有下级资源的组织", "organizationType", "COMPANY", "sortOrder", 10,
                        "status", "INACTIVE", "expectedVersion", 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IAM_RESOURCE_IN_USE"));

        user = putJsonOk(adminToken, "/api/v1/iam/users/" + userId,
                body("displayName", "生命周期测试账号（停用）", "employeeId", employeeId,
                        "enabled", false, "passwordChangeRequired", false,
                        "roleIds", Set.of(roleId), "projectIds", Set.of(PRIMARY_PROJECT), "expectedVersion", 2));
        assertThat(user.path("enabled").asBoolean()).isFalse();
        getJsonRequest(lifecycleToken, "/api/v1/iam/enterprises")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        postJsonRequest(null, "/api/v1/auth/login", body("username", username, "password", resetPassword))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        employee = putJsonOk(adminToken, "/api/v1/iam/employees/" + employeeId,
                body("organizationId", rootOrganizationId, "positionId", positionId,
                        "displayName", "生命周期测试人员（离职）", "mobileMasked", "139****2026",
                        "employmentStatus", "LEFT", "hireDate", "2026-08-01",
                        "leaveDate", "2026-08-24", "expectedVersion", 0));
        assertThat(employee.path("employmentStatus").asText()).isEqualTo("LEFT");
        assertThat(employee.path("version").asLong()).isEqualTo(1);
        role = putJsonOk(adminToken, "/api/v1/iam/roles/" + roleId,
                body("name", "生命周期测试角色（停用）", "description", "已停用",
                        "enabled", false, "permissionIds", Set.of(iamReadPermission), "expectedVersion", 1));
        assertThat(role.path("enabled").asBoolean()).isFalse();
        position = putJsonOk(adminToken, "/api/v1/iam/positions/" + positionId,
                body("organizationId", rootOrganizationId, "name", "生命周期测试岗位（停用）",
                        "description", "已停用", "status", "INACTIVE", "expectedVersion", 0));
        assertThat(position.path("status").asText()).isEqualTo("INACTIVE");
        childOrganization = putJsonOk(adminToken, "/api/v1/iam/organizations/" + childOrganizationId,
                body("parentId", rootOrganizationId, "name", "生命周期测试部门（停用）",
                        "organizationType", "DEPARTMENT", "sortOrder", 20,
                        "status", "INACTIVE", "expectedVersion", 0));
        assertThat(childOrganization.path("status").asText()).isEqualTo("INACTIVE");
        rootOrganization = putJsonOk(adminToken, "/api/v1/iam/organizations/" + rootOrganizationId,
                body("name", "生命周期测试总部（停用）", "organizationType", "COMPANY", "sortOrder", 10,
                        "status", "INACTIVE", "expectedVersion", 0));
        assertThat(rootOrganization.path("status").asText()).isEqualTo("INACTIVE");
        putJsonRequest(adminToken, "/api/v1/iam/enterprises/" + enterpriseId,
                body("name", "仍有启用角色的企业", "status", "INACTIVE", "expectedVersion", 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IAM_RESOURCE_IN_USE"));
        foreignRole = putJsonOk(adminToken, "/api/v1/iam/roles/" + foreignRoleId,
                body("name", "跨企业测试角色（停用）", "description", "已停用",
                        "enabled", false, "permissionIds", Set.of(iamReadPermission), "expectedVersion", 0));
        assertThat(foreignRole.path("enabled").asBoolean()).isFalse();
        enterprise = putJsonOk(adminToken, "/api/v1/iam/enterprises/" + enterpriseId,
                body("name", "生命周期测试企业（停用）", "status", "INACTIVE", "expectedVersion", 1));
        assertThat(enterprise.path("status").asText()).isEqualTo("INACTIVE");

        Long audited = jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_event
                 WHERE resource_id IN (:enterpriseId, :organizationId, :positionId, :employeeId, :roleId, :userId)
                """, body("enterpriseId", enterpriseId, "organizationId", rootOrganizationId,
                        "positionId", positionId, "employeeId", employeeId, "roleId", roleId, "userId", userId),
                Long.class);
        assertThat(audited).isGreaterThanOrEqualTo(12);
    }

    private String login(String username, String password) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(response).path("accessToken").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private String userId(String username) {
        return jdbc.queryForObject("SELECT id FROM sys_user WHERE username=:username",
                Map.of("username", username), String.class);
    }

    private ResultActions getJsonRequest(String token, String path) throws Exception {
        return mockMvc.perform(get(path).header("Authorization", bearer(token)));
    }

    private JsonNode getJsonOk(String token, String path) throws Exception {
        String response = getJsonRequest(token, path).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private ResultActions postJsonRequest(String token, String path, Object request) throws Exception {
        var builder = post(path).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request));
        if (token != null) builder.header("Authorization", bearer(token));
        return mockMvc.perform(builder);
    }

    private ResultActions postJsonRequest(String token, String path, Object request, String idempotencyKey)
            throws Exception {
        var builder = post(path).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .header("Idempotency-Key", idempotencyKey);
        if (token != null) builder.header("Authorization", bearer(token));
        return mockMvc.perform(builder);
    }

    private JsonNode postJsonOk(String token, String path, Object request) throws Exception {
        String response = postJsonRequest(token, path, request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private ResultActions putJsonRequest(String token, String path, Object request) throws Exception {
        return mockMvc.perform(put(path).header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)));
    }

    private JsonNode putJsonOk(String token, String path, Object request) throws Exception {
        String response = putJsonRequest(token, path, request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private JsonNode findByText(JsonNode array, String field, String expected) {
        for (JsonNode item : array) {
            if (expected.equals(item.path(field).asText())) return item;
        }
        throw new AssertionError("Missing item with " + field + "=" + expected);
    }

    private String nullableText(JsonNode value) {
        return value == null || value.isNull() || value.isMissingNode() ? null : value.asText();
    }

    private Map<String, Object> body(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put((String) values[index], values[index + 1]);
        }
        return result;
    }
}
