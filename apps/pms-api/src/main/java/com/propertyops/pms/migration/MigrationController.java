package com.propertyops.pms.migration;

import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.propertyops.pms.common.data.PageResponse;

@RestController
@RequestMapping("/api/v1/migrations")
public class MigrationController {
    private final MigrationService service;

    public MigrationController(MigrationService service) {
        this.service = service;
    }

    @GetMapping("/batches")
    PageResponse<MigrationModels.BatchSummary> batches(
            @RequestParam String communityId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.batches(communityId, status, keyword, page, size);
    }

    @GetMapping("/batches/{id}")
    MigrationModels.BatchDetail batch(@PathVariable String id, @RequestParam String communityId) {
        return service.batch(id, communityId, false);
    }

    @PostMapping("/batches")
    MigrationModels.BatchDetail create(@Valid @RequestBody MigrationModels.CreateBatchRequest request) {
        return service.create(request);
    }

    @PostMapping("/batches/{id}:validate")
    MigrationModels.CommandResult validate(@PathVariable String id,
                                            @Valid @RequestBody MigrationModels.BatchCommandRequest request) {
        return service.validate(id, request);
    }

    @PostMapping("/batches/{id}:approve")
    MigrationModels.CommandResult approve(@PathVariable String id,
                                           @Valid @RequestBody MigrationModels.ApprovalRequest request) {
        return service.approve(id, request);
    }

    @PostMapping("/batches/{id}:execute")
    MigrationModels.CommandResult execute(@PathVariable String id,
                                           @Valid @RequestBody MigrationModels.BatchCommandRequest request) {
        return service.execute(id, request);
    }

    @PostMapping("/batches/{id}:reconcile")
    MigrationModels.CommandResult reconcile(@PathVariable String id,
                                             @Valid @RequestBody MigrationModels.BatchCommandRequest request) {
        return service.reconcile(id, request);
    }

    @PostMapping("/batches/{id}:rollback")
    MigrationModels.CommandResult rollback(@PathVariable String id,
                                            @Valid @RequestBody MigrationModels.RollbackRequest request) {
        return service.rollback(id, request);
    }

    @GetMapping(value = "/template", produces = "text/csv;charset=UTF-8")
    ResponseEntity<String> template() {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=migration-template.csv")
                .body(service.template());
    }
}
