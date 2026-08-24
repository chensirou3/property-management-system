package com.propertyops.pms.fee;

import javax.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class FeeController {
    private final FeeService service;

    public FeeController(FeeService service) {
        this.service = service;
    }

    @PostMapping("/fee-standards")
    Object createStandard(@Valid @RequestBody FeeService.CreateStandard request) {
        return service.createStandard(request);
    }

    @PostMapping("/fee-allocations:batch-assign")
    Object batchAssign(@Valid @RequestBody FeeService.BatchAssign request) {
        return service.batchAssign(request);
    }

    @PostMapping("/fee-allocations:batch-cancel")
    Object batchCancel(@Valid @RequestBody FeeService.BatchCancel request) {
        return service.batchCancel(request);
    }

    @PostMapping("/receivable-jobs:preview")
    Object preview(@Valid @RequestBody FeeService.ReceivableRequest request) {
        return service.preview(request);
    }

    @PostMapping("/receivable-jobs")
    Object generate(@Valid @RequestBody FeeService.ReceivableRequest request,
                    @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return service.generate(request, idempotencyKey);
    }
}
