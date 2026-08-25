package com.propertyops.pms.meter;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class MeterController {
    private final MeterService service;

    public MeterController(MeterService service) {
        this.service = service;
    }

    @PostMapping("/meter-reading-batches")
    Object createBatch(@Valid @RequestBody MeterService.CreateBatch request) {
        return service.createBatch(request);
    }

    @PostMapping("/meter-readings:input")
    Object input(@Valid @RequestBody MeterService.ReadingsInput request) {
        return service.input(request);
    }

    @PostMapping("/meter-readings:import-simulated")
    Object importSimulated(@RequestParam String communityId, @RequestParam String batchId,
                           @RequestBody List<String> meterIds) {
        return service.importSimulated(communityId, batchId, meterIds);
    }

    @PostMapping("/meter-reading-batches/{id}:approve")
    Object approve(@PathVariable String id, @RequestParam String communityId) {
        return service.approve(id, communityId);
    }

    @PostMapping("/meter-share-rules:preview")
    Object sharePreview(@Valid @RequestBody MeterService.ShareRequest request) {
        return service.sharePreview(request);
    }

    @PostMapping("/meter-share-rules:apply")
    Object applyShare(@Valid @RequestBody MeterService.ShareRequest request) {
        return service.applyShare(request);
    }

    @PostMapping("/meters/{id}:replace")
    Object replace(@PathVariable String id, @Valid @RequestBody MeterService.ReplacementRequest request) {
        return service.replace(id, request);
    }

    @PostMapping("/meter-reading-batches/{id}:generate-charges")
    Object generateCharges(@PathVariable String id, @Valid @RequestBody MeterService.ChargeRequest request) {
        return service.generateCharges(id, request);
    }
}
