package com.propertyops.pms.fee;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class FeeController {
    private final FeeConfigurationService configuration;
    private final ReceivableService receivables;

    public FeeController(FeeConfigurationService configuration, ReceivableService receivables) {
        this.configuration = configuration;
        this.receivables = receivables;
    }

    @GetMapping("/fees/definitions")
    List<Map<String, Object>> definitions(@RequestParam String communityId) {
        return configuration.definitions(communityId);
    }

    @PostMapping("/fees/definitions")
    Object createDefinition(@Valid @RequestBody FeeModels.CreateDefinition request) {
        return configuration.createDefinition(request);
    }

    @PutMapping("/fees/definitions/{id}")
    Object updateDefinition(@PathVariable String id, @RequestParam String communityId,
                            @Valid @RequestBody FeeModels.UpdateDefinition request) {
        return configuration.updateDefinition(id, communityId, request);
    }

    @GetMapping("/fees/standards")
    List<Map<String, Object>> standards(@RequestParam String communityId,
                                       @RequestParam(required = false) String feeDefinitionId) {
        return configuration.standards(communityId, feeDefinitionId);
    }

    @PostMapping({"/fees/standards", "/fee-standards"})
    Object createStandard(@Valid @RequestBody FeeModels.CreateStandard request) {
        return configuration.createStandard(request);
    }

    @GetMapping("/fees/standards/{id}/versions")
    Object versions(@PathVariable String id, @RequestParam String communityId) {
        return configuration.versions(id, communityId);
    }

    @PostMapping("/fees/standards/{id}/versions")
    Object createVersion(@PathVariable String id, @Valid @RequestBody FeeModels.CreateStandardVersion request) {
        return configuration.addVersion(id, request);
    }

    @PostMapping("/fees/standards/{id}:disable")
    Object disableStandard(@PathVariable String id, @RequestParam String communityId) {
        return configuration.disableStandard(id, communityId);
    }

    @GetMapping("/fees/allocations")
    Object allocations(@RequestParam String communityId,
                       @RequestParam(required = false) String feeStandardId) {
        return configuration.allocations(communityId, feeStandardId);
    }

    @PostMapping("/fees/allocations:preview")
    Object previewAllocations(@Valid @RequestBody FeeModels.AllocationRequest request) {
        return configuration.previewAllocations(request);
    }

    @PostMapping({"/fees/allocations:assign", "/fee-allocations:batch-assign"})
    Object assignAllocations(@Valid @RequestBody FeeModels.AllocationRequest request) {
        return configuration.assignAllocations(request);
    }

    @PostMapping({"/fees/allocations:cancel", "/fee-allocations:batch-cancel"})
    Object cancelAllocations(@Valid @RequestBody FeeModels.CancelAllocations request) {
        return configuration.cancelAllocations(request);
    }

    @PostMapping({"/receivables:preview", "/receivable-jobs:preview"})
    Object previewPeriodic(@Valid @RequestBody FeeModels.PeriodicRequest request) {
        return receivables.previewPeriodic(request);
    }

    @PostMapping("/receivable-jobs")
    Object createPeriodic(@Valid @RequestBody FeeModels.PeriodicRequest request,
                          @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return receivables.createPeriodic(request, idempotencyKey);
    }

    @GetMapping("/receivable-jobs")
    Object jobs(@RequestParam String communityId,
                @RequestParam(required = false) String jobType) {
        return receivables.jobs(communityId, jobType);
    }

    @GetMapping("/receivable-jobs/{id}")
    Object job(@PathVariable String id, @RequestParam String communityId) {
        return receivables.job(id, communityId);
    }

    @PostMapping("/temporary-receivables:preview")
    Object previewTemporary(@Valid @RequestBody FeeModels.TemporaryRequest request) {
        return receivables.previewTemporary(request);
    }

    @PostMapping("/temporary-receivable-jobs")
    Object createTemporary(@Valid @RequestBody FeeModels.TemporaryRequest request,
                           @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return receivables.createTemporary(request, idempotencyKey);
    }
}
