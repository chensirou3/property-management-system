package com.propertyops.pms.finance;

import java.time.LocalDate;

import jakarta.validation.Valid;

import org.springframework.format.annotation.DateTimeFormat;
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
public class FinancialGovernanceController {
    private final FinancialGovernanceService governance;
    private final FinancialOperationsService operations;

    public FinancialGovernanceController(FinancialGovernanceService governance,
                                         FinancialOperationsService operations) {
        this.governance = governance;
        this.operations = operations;
    }

    @GetMapping("/finance/bills")
    Object bills(@RequestParam String communityId,
                 @RequestParam(required = false) String keyword,
                 @RequestParam(required = false) String status,
                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate,
                 @RequestParam(defaultValue = "1") int page,
                 @RequestParam(defaultValue = "50") int size) {
        return governance.bills(communityId, keyword, status, asOfDate, page, size);
    }

    @GetMapping("/finance/bills/{id}")
    Object bill(@PathVariable String id, @RequestParam String communityId) {
        return governance.bill(communityId, id);
    }

    @GetMapping("/finance/arrears")
    Object arrears(@RequestParam String communityId,
                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate,
                   @RequestParam(required = false) String keyword) {
        return governance.arrears(communityId, asOfDate, keyword);
    }

    @GetMapping("/finance/transactions")
    Object transactions(@RequestParam String communityId,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                        @RequestParam(required = false) String channel,
                        @RequestParam(required = false) String type) {
        return governance.transactions(communityId, from, to, channel, type);
    }

    @GetMapping("/finance/balances")
    Object balances(@RequestParam String communityId, @RequestParam(required = false) String customerId) {
        return governance.balances(communityId, customerId);
    }

    @GetMapping("/finance/discount-policies")
    Object discountPolicies(@RequestParam String communityId,
                            @RequestParam(required = false)
                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate effectiveDate) {
        return governance.discountPolicies(communityId, effectiveDate);
    }

    @PostMapping("/finance/discount-policies")
    Object createDiscountPolicy(@Valid @RequestBody FinancialModels.CreateDiscountPolicy request) {
        return governance.createDiscountPolicy(request);
    }

    @PutMapping("/finance/discount-policies/{id}")
    Object updateDiscountPolicy(@PathVariable String id, @RequestParam String communityId,
                                @Valid @RequestBody FinancialModels.UpdateDiscountPolicy request) {
        return governance.updateDiscountPolicy(id, communityId, request);
    }

    @GetMapping("/finance/adjustments")
    Object adjustments(@RequestParam String communityId,
                       @RequestParam(required = false) String status,
                       @RequestParam(required = false) String billId) {
        return governance.adjustments(communityId, status, billId);
    }

    @PostMapping("/finance/adjustments")
    Object createAdjustment(@Valid @RequestBody FinancialModels.CreateAdjustment request,
                            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return governance.createAdjustment(request, key);
    }

    @PostMapping("/finance/adjustments/{id}:approve")
    Object approveAdjustment(@PathVariable String id,
                             @Valid @RequestBody FinancialModels.AdjustmentDecision request) {
        return governance.approveAdjustment(id, request);
    }

    @PostMapping("/finance/adjustments/{id}:reject")
    Object rejectAdjustment(@PathVariable String id,
                            @Valid @RequestBody FinancialModels.AdjustmentDecision request) {
        return governance.rejectAdjustment(id, request);
    }

    @GetMapping("/cashier/shifts")
    Object shifts(@RequestParam String communityId, @RequestParam(required = false) String status) {
        return operations.shifts(communityId, status);
    }

    @GetMapping("/cashier/shifts:current")
    Object currentShift(@RequestParam String communityId) {
        return operations.currentShift(communityId);
    }

    @PostMapping("/cashier/shifts")
    Object openShift(@Valid @RequestBody FinancialModels.OpenShift request,
                     @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return operations.openShift(request, key);
    }

    @PostMapping("/cashier/shifts/{id}:close")
    Object closeShift(@PathVariable String id, @Valid @RequestBody FinancialModels.CloseShift request) {
        return operations.closeShift(id, request);
    }

    @PostMapping("/cashier/shifts/{id}:lock")
    Object lockShift(@PathVariable String id, @Valid @RequestBody FinancialModels.LockShift request) {
        return operations.lockShift(id, request);
    }

    @GetMapping("/finance/settlements")
    Object settlements(@RequestParam String communityId) {
        return operations.settlements(communityId);
    }

    @GetMapping("/finance/settlements:preview")
    Object settlementPreview(@RequestParam String communityId,
                             @RequestParam(required = false)
                             @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate settlementDate) {
        return operations.settlementPreview(communityId, settlementDate);
    }

    @PostMapping("/finance/settlements")
    Object closeSettlement(@Valid @RequestBody FinancialModels.SettlementRequest request,
                           @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return operations.closeSettlement(request, key);
    }

    @PostMapping("/finance/settlements/{id}:lock")
    Object lockSettlement(@PathVariable String id,
                          @Valid @RequestBody FinancialModels.SettlementLock request) {
        return operations.lockSettlement(id, request);
    }

    @GetMapping("/finance/receipt-segments")
    Object receiptSegments(@RequestParam String communityId) {
        return operations.receiptSegments(communityId);
    }

    @PostMapping("/finance/receipt-segments")
    Object createReceiptSegment(@Valid @RequestBody FinancialModels.CreateReceiptSegment request) {
        return operations.createReceiptSegment(request);
    }

    @GetMapping("/finance/receipts")
    Object receipts(@RequestParam String communityId, @RequestParam(required = false) String status) {
        return operations.receipts(communityId, status);
    }

    @PostMapping("/finance/receipts/{id}:replace")
    Object replaceReceipt(@PathVariable String id, @Valid @RequestBody FinancialModels.ReceiptOperation request) {
        return operations.replaceReceipt(id, request);
    }

    @PostMapping("/finance/receipts/{id}:void")
    Object voidReceipt(@PathVariable String id, @Valid @RequestBody FinancialModels.ReceiptOperation request) {
        return operations.voidReceipt(id, request);
    }

    @GetMapping("/finance/invoices")
    Object invoices(@RequestParam String communityId) {
        return operations.invoices(communityId);
    }

    @PostMapping("/finance/invoices/{id}:operate")
    Object operateInvoice(@PathVariable String id, @Valid @RequestBody FinancialModels.InvoiceOperation request) {
        return operations.operateInvoice(id, request);
    }

    @GetMapping("/finance/reconciliation")
    Object reconciliation(@RequestParam String communityId) {
        return operations.reconciliation(communityId);
    }
}
