package com.propertyops.pms.report;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.http.ContentDisposition;
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

@RestController
@RequestMapping("/api/v1")
public class ReportController {
    private final ReportService reports;

    public ReportController(ReportService reports) { this.reports = reports; }

    @GetMapping("/reports/catalog")
    Object catalog(@RequestParam String communityId) { return reports.catalog(communityId); }

    @GetMapping("/reports/{code}")
    Object query(@PathVariable String code, @RequestParam String communityId,
                 @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "50") int size,
                 @RequestParam(required = false) String columns, @RequestParam Map<String, String> all) {
        Map<String, String> filters = new LinkedHashMap<>(all);
        List.of("communityId", "page", "size", "columns").forEach(filters::remove);
        List<String> selected = columns == null || columns.isBlank() ? List.of()
                : Arrays.stream(columns.split(",")).map(String::trim).filter(value -> !value.isBlank()).toList();
        return reports.query(communityId, code, filters, selected, page, size);
    }

    @PostMapping("/report-jobs")
    Object createExport(@Valid @RequestBody ReportModels.CreateExportJob request,
                        @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return reports.createExport(request, key);
    }

    @GetMapping("/report-jobs")
    Object exports(@RequestParam String communityId) { return reports.exports(communityId); }

    @GetMapping("/report-jobs/{id}")
    Object export(@PathVariable String id, @RequestParam String communityId) {
        return reports.exportJob(id, communityId);
    }

    @GetMapping("/report-jobs/{id}/artifact")
    ResponseEntity<byte[]> exportArtifact(@PathVariable String id, @RequestParam String communityId) {
        return artifact(reports.exportArtifact(id, communityId));
    }

    @PostMapping("/receipt-print-jobs")
    Object createReceiptPrint(@Valid @RequestBody ReportModels.CreateReceiptPrintJob request,
                              @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return reports.createReceiptPrint(request, key);
    }

    @GetMapping("/receipt-print-jobs")
    Object receiptPrints(@RequestParam String communityId) { return reports.receiptPrints(communityId); }

    @GetMapping("/receipt-print-jobs/{id}")
    Object receiptPrint(@PathVariable String id, @RequestParam String communityId) {
        return reports.receiptPrintJob(id, communityId);
    }

    @GetMapping("/receipt-print-jobs/{id}/artifact")
    ResponseEntity<byte[]> receiptArtifact(@PathVariable String id, @RequestParam String communityId) {
        return artifact(reports.receiptArtifact(id, communityId));
    }

    @PostMapping("/notification-batches")
    Object createNotification(@Valid @RequestBody ReportModels.CreateNotificationBatch request,
                              @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return reports.createNotification(request, key);
    }

    @GetMapping("/notification-batches")
    Object notifications(@RequestParam String communityId) { return reports.notifications(communityId); }

    @GetMapping("/notification-batches/{id}")
    Object notification(@PathVariable String id, @RequestParam String communityId) {
        return reports.notification(id, communityId);
    }

    private ResponseEntity<byte[]> artifact(ReportService.Artifact artifact) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(artifact.name(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(artifact.mime())).body(artifact.bytes());
    }
}
