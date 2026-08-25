package com.propertyops.pms.property;

import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.propertyops.pms.common.data.PageResponse;

@RestController
@RequestMapping("/api/v1/property")
public class PropertyController {
    private final PropertyService service;

    public PropertyController(PropertyService service) {
        this.service = service;
    }

    @GetMapping("/tree")
    PropertyModels.AssetTreeResponse tree(@RequestParam String communityId,
                                          @RequestParam(required = false) String assetType,
                                          @RequestParam(required = false) String keyword) {
        return service.tree(communityId, assetType, keyword);
    }

    @GetMapping("/assets")
    PageResponse<PropertyModels.AssetListItem> assets(@RequestParam String communityId,
                                                       @RequestParam(required = false) String assetType,
                                                       @RequestParam(required = false) String keyword,
                                                       @RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        return service.assets(communityId, assetType, keyword, page, size);
    }

    @GetMapping("/assets/{id}")
    PropertyModels.AssetProfile asset(@PathVariable String id, @RequestParam String communityId) {
        return service.assetProfile(id, communityId);
    }

    @GetMapping("/customers")
    PageResponse<PropertyModels.CustomerListItem> customers(@RequestParam String communityId,
                                                             @RequestParam(required = false) String keyword,
                                                             @RequestParam(required = false) String customerType,
                                                             @RequestParam(required = false) String status,
                                                             @RequestParam(defaultValue = "1") int page,
                                                             @RequestParam(defaultValue = "20") int size) {
        return service.customers(communityId, keyword, customerType, status, page, size);
    }

    @GetMapping("/customers/{id}")
    PropertyModels.CustomerProfile customer(@PathVariable String id, @RequestParam String communityId) {
        return service.customerProfile(id, communityId);
    }

    @PostMapping("/relations")
    PropertyModels.RelationCommandResult startRelation(
            @Valid @RequestBody PropertyModels.StartRelationRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return service.startRelation(request, idempotencyKey);
    }

    @PostMapping("/relations/{id}:end")
    PropertyModels.RelationCommandResult endRelation(
            @PathVariable String id,
            @Valid @RequestBody PropertyModels.EndRelationRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return service.endRelation(id, request, idempotencyKey);
    }

    @PostMapping("/assets/{id}:transfer")
    PropertyModels.RelationCommandResult transfer(
            @PathVariable String id,
            @Valid @RequestBody PropertyModels.TransferOwnershipRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return service.transferOwnership(id, request, idempotencyKey);
    }

    @GetMapping(value = "/imports/template", produces = "text/csv;charset=UTF-8")
    ResponseEntity<String> importTemplate(@RequestParam String resource) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=property-" + service.normalizedResource(resource).toLowerCase() + "-template.csv")
                .body(service.importTemplate(resource));
    }

    @PostMapping("/imports:validate")
    PropertyModels.ImportValidationReport validateImport(
            @Valid @RequestBody PropertyModels.ImportValidationRequest request) {
        return service.validateImport(request);
    }
}
