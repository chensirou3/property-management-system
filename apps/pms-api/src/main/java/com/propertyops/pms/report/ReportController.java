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

import io.swagger.v3.oas.annotations.Parameter;

@RestController
@RequestMapping("/api/v1")
public class ReportController {
    private final ReportService reports;

    public ReportController(ReportService reports) { this.reports = reports; }

    @GetMapping("/reports/catalog")
    Object catalog(@RequestParam String communityId) { return reports.catalog(communityId); }

    @GetMapping(value = "/reports/filter-options", produces = MediaType.APPLICATION_JSON_VALUE)
    ReportModels.ReportFilterOptions filterOptions(@RequestParam String communityId, @RequestParam String reportCode) {
        return reports.filterOptions(communityId, reportCode);
    }

    @GetMapping("/reports/{code}")
    Object query(@PathVariable String code, @RequestParam String communityId,
                 @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "50") int size,
                 @RequestParam(required = false) String columns,
                 @RequestParam(name = "dateFrom", required = false) String dateFrom,
                 @RequestParam(name = "dateTo", required = false) String dateTo,
                 @RequestParam(name = "paymentChannel", required = false) String paymentChannel,
                 @RequestParam(name = "cashierId", required = false) String cashierId,
                 @RequestParam(name = "transactionNo", required = false) String transactionNo,
                 @RequestParam(name = "status", required = false) String status,
                 @RequestParam(name = "receiptStatus", required = false) String receiptStatus,
                 @RequestParam(name = "keyword", required = false) String keyword,
                 @RequestParam(name = "billingPeriodFrom", required = false) String billingPeriodFrom,
                 @RequestParam(name = "billingPeriodTo", required = false) String billingPeriodTo,
                 @RequestParam(name = "feeDefinitionId", required = false) String feeDefinitionId,
                 @RequestParam(name = "channel", required = false) String channel,
                 @RequestParam(name = "deliveryStatus", required = false) String deliveryStatus,
                 @RequestParam(name = "arrearsPeriodFrom", required = false) String arrearsPeriodFrom,
                 @RequestParam(name = "arrearsPeriodTo", required = false) String arrearsPeriodTo,
                 @RequestParam(name = "subjectType", required = false) String subjectType,
                 @RequestParam(name = "discountType", required = false) String discountType,
                 @RequestParam(name = "entryType", required = false) String entryType,
                 @RequestParam(name = "reminderType", required = false) String reminderType,
                 @RequestParam(name = "invoiceType", required = false) String invoiceType,
                 @RequestParam(name = "invoiceStatus", required = false) String invoiceStatus,
                 @RequestParam(name = "settlementDate", required = false) String settlementDate,
                 @RequestParam(name = "adjustmentType", required = false) String adjustmentType,
                 @Parameter(hidden = true) @RequestParam Map<String, String> all) {
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
