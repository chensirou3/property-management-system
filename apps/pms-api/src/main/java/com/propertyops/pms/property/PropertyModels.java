package com.propertyops.pms.property;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class PropertyModels {
    private PropertyModels() {}

    public record GridNode(String id, String parentId, String code, String name, String status) {}
    public record BuildingNode(String id, String gridId, String code, String name, String status) {}
    public record UnitNode(String id, String buildingId, String code, String name, String status) {}
    public record AssetNode(String id, String gridId, String buildingId, String unitId, String assetType,
                            String code, String name, String occupancyStatus, String operationStatus,
                            boolean enabled) {}
    public record AssetTreeResponse(String communityId, List<GridNode> grids, List<BuildingNode> buildings,
                                    List<UnitNode> units, List<AssetNode> assets, int assetCount) {}

    public record AssetListItem(String id, String assetType, String code, String displayName, String gridName,
                                String buildingName, String unitName, String floorNo, BigDecimal buildingArea,
                                BigDecimal usableArea, String occupancyStatus, String operationStatus,
                                boolean enabled, String primaryCustomerName, int activeRelationCount, long version) {}

    public record AssetDetail(String id, String communityId, String gridId, String buildingId, String unitId,
                              String assetType, String code, String displayName, String floorNo,
                              BigDecimal buildingArea, BigDecimal usableArea, String occupancyStatus,
                              String operationStatus, boolean enabled, LocalDate validFrom, LocalDate validTo,
                              String gridName, String buildingName, String unitName, long version,
                              LocalDateTime createdAt, LocalDateTime updatedAt) {}

    public record RelationItem(String id, String customerId, String customerName, String assetId,
                               String assetName, String relationType, boolean primaryRelation,
                               LocalDate startDate, LocalDate endDate, String changeReason, String status,
                               long version) {}
    public record VehicleItem(String id, String plateNoMasked, String vehicleType, String color, String status,
                              String parkingAssetId, String parkingName, LocalDate startDate, LocalDate endDate) {}
    public record MeterItem(String id, String meterNo, String meterType, String meterClass, String status) {}
    public record PropertyEventItem(String id, String eventType, String relationType, LocalDate effectiveDate,
                                    String customerId, String customerName, String reason,
                                    String previousRelationId, String newRelationId, LocalDateTime createdAt) {}
    public record AssetProfile(AssetDetail asset, Map<String, Object> typeDetail, List<RelationItem> relations,
                               List<VehicleItem> vehicles, List<MeterItem> meters,
                               List<PropertyEventItem> timeline) {}

    public record CustomerListItem(String id, String customerNo, String displayName, String customerType,
                                   String customerClass, String mobileMasked, String status,
                                   int activeAssetCount, int activeVehicleCount, long version) {}
    public record CustomerDetail(String id, String communityId, String customerNo, String displayName,
                                 String customerType, String customerClass, String mobileMasked,
                                 String certificateType, String certificateMasked, String gender,
                                 LocalDate birthday, String remarks, String status, long version,
                                 LocalDateTime createdAt, LocalDateTime updatedAt) {}
    public record CustomerProfile(CustomerDetail customer, List<RelationItem> relations,
                                  List<VehicleItem> vehicles, List<PropertyEventItem> timeline) {}

    public record StartRelationRequest(
            @NotBlank String communityId,
            @NotBlank String customerId,
            @NotBlank String assetId,
            @NotBlank @Pattern(regexp = "OWNER|CO_OWNER|TENANT|OCCUPANT") String relationType,
            boolean primaryRelation,
            @NotNull LocalDate startDate,
            @Size(max = 500) String reason) {}

    public record EndRelationRequest(
            @NotBlank String communityId,
            @NotNull LocalDate effectiveDate,
            @NotBlank @Size(max = 500) String reason,
            @Min(0) long expectedVersion) {}

    public record TransferOwnershipRequest(
            @NotBlank String communityId,
            @NotBlank String newOwnerCustomerId,
            @NotNull LocalDate effectiveDate,
            @NotBlank @Size(max = 500) String reason,
            @Min(0) long expectedAssetVersion) {}

    public record RelationCommandResult(String eventId, String relationId, String assetId, String customerId,
                                        String status, boolean replayed) {}

    public record ImportValidationRequest(
            @NotBlank String communityId,
            @NotBlank @Pattern(regexp = "GRID|BUILDING|UNIT|ASSET|CUSTOMER|RELATION") String resource,
            @NotEmpty @Size(max = 500) List<@NotNull Map<String, Object>> rows) {}
    public record ImportRowResult(int rowNumber, boolean valid, List<String> errors, List<String> warnings) {}
    public record ImportValidationReport(String resource, int totalRows, int validRows, int invalidRows,
                                         boolean readyToImport, List<ImportRowResult> rows) {}
}
