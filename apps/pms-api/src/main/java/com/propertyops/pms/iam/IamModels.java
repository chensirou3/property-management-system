package com.propertyops.pms.iam;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

public final class IamModels {
    private IamModels() {}

    public record EnterpriseView(String id, String code, String name, String status, long version,
                                 LocalDateTime createdAt, LocalDateTime updatedAt) {}

    public record OrganizationView(String id, String enterpriseId, String parentId, String communityId,
                                   String code, String name, String organizationType, int sortOrder,
                                   String status, long version, LocalDateTime createdAt, LocalDateTime updatedAt) {}

    public record PositionView(String id, String enterpriseId, String organizationId, String code, String name,
                               String description, String status, long version,
                               LocalDateTime createdAt, LocalDateTime updatedAt) {}

    public record EmployeeView(String id, String enterpriseId, String organizationId, String positionId,
                               String employeeNo, String displayName, String mobileMasked,
                               String employmentStatus, LocalDate hireDate, LocalDate leaveDate, long version,
                               LocalDateTime createdAt, LocalDateTime updatedAt) {}

    public record PermissionView(String id, String code, String name, String resourceType) {}

    public record RoleView(String id, String enterpriseId, String code, String name, String description,
                           boolean enabled, long version, Set<String> permissionIds,
                           LocalDateTime createdAt, LocalDateTime updatedAt) {}

    public record UserView(String id, String username, String displayName, String employeeId, boolean enabled,
                           boolean passwordChangeRequired, long version, Set<String> roleIds,
                           Set<String> projectIds, LocalDateTime lastLoginAt,
                           LocalDateTime createdAt, LocalDateTime updatedAt) {}

    public record ProjectView(String id, String enterpriseId, String name, String status) {}

    public record CreateEnterpriseRequest(
            @NotBlank @Size(max = 80) @Pattern(regexp = "[A-Za-z0-9_-]+") String code,
            @NotBlank @Size(max = 160) String name) {}

    public record UpdateEnterpriseRequest(
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Pattern(regexp = "ACTIVE|INACTIVE") String status,
            @NotNull Long expectedVersion) {}

    public record CreateOrganizationRequest(
            @NotBlank String enterpriseId,
            String parentId,
            String communityId,
            @NotBlank @Size(max = 80) @Pattern(regexp = "[A-Za-z0-9_-]+") String code,
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Pattern(regexp = "COMPANY|PROJECT|DEPARTMENT") String organizationType,
            @NotNull Integer sortOrder) {}

    public record UpdateOrganizationRequest(
            String parentId,
            String communityId,
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Pattern(regexp = "COMPANY|PROJECT|DEPARTMENT") String organizationType,
            @NotNull Integer sortOrder,
            @NotBlank @Pattern(regexp = "ACTIVE|INACTIVE") String status,
            @NotNull Long expectedVersion) {}

    public record CreatePositionRequest(
            @NotBlank String enterpriseId,
            @NotBlank String organizationId,
            @NotBlank @Size(max = 80) @Pattern(regexp = "[A-Za-z0-9_-]+") String code,
            @NotBlank @Size(max = 160) String name,
            @Size(max = 500) String description) {}

    public record UpdatePositionRequest(
            @NotBlank String organizationId,
            @NotBlank @Size(max = 160) String name,
            @Size(max = 500) String description,
            @NotBlank @Pattern(regexp = "ACTIVE|INACTIVE") String status,
            @NotNull Long expectedVersion) {}

    public record CreateEmployeeRequest(
            @NotBlank String enterpriseId,
            @NotBlank String organizationId,
            String positionId,
            @NotBlank @Size(max = 80) @Pattern(regexp = "[A-Za-z0-9_-]+") String employeeNo,
            @NotBlank @Size(max = 160) String displayName,
            @Size(max = 40) String mobileMasked,
            LocalDate hireDate) {}

    public record UpdateEmployeeRequest(
            @NotBlank String organizationId,
            String positionId,
            @NotBlank @Size(max = 160) String displayName,
            @Size(max = 40) String mobileMasked,
            @NotBlank @Pattern(regexp = "ACTIVE|INACTIVE|LEFT") String employmentStatus,
            LocalDate hireDate,
            LocalDate leaveDate,
            @NotNull Long expectedVersion) {}

    public record CreateUserRequest(
            @NotBlank @Size(max = 80) @Pattern(regexp = "[A-Za-z0-9_.-]+") String username,
            @NotBlank @Size(min = 12, max = 200) String password,
            @NotBlank @Size(max = 120) String displayName,
            String employeeId,
            @NotNull Boolean enabled,
            @NotNull Set<String> roleIds,
            @NotNull Set<String> projectIds) {}

    public record UpdateUserRequest(
            @NotBlank @Size(max = 120) String displayName,
            String employeeId,
            @NotNull Boolean enabled,
            @NotNull Boolean passwordChangeRequired,
            @NotNull Set<String> roleIds,
            @NotNull Set<String> projectIds,
            @NotNull Long expectedVersion) {}

    public record ResetPasswordRequest(
            @NotBlank @Size(min = 12, max = 200) String password,
            @NotNull Boolean requireChange,
            @NotNull Long expectedVersion) {}

    public record CreateRoleRequest(
            String enterpriseId,
            @NotBlank @Size(max = 80) @Pattern(regexp = "[A-Za-z0-9:_-]+") String code,
            @NotBlank @Size(max = 120) String name,
            @Size(max = 500) String description,
            @NotNull Set<String> permissionIds) {}

    public record UpdateRoleRequest(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 500) String description,
            @NotNull Boolean enabled,
            @NotNull Set<String> permissionIds,
            @NotNull Long expectedVersion) {}
}
