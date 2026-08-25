package com.propertyops.pms.finance;

import jakarta.validation.Valid;

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
public class FinanceController {
    private final FinanceService service;

    public FinanceController(FinanceService service) {
        this.service = service;
    }

    @GetMapping("/cashier/context")
    Object context(@RequestParam String communityId, @RequestParam(required = false) String keyword) {
        return service.cashierContext(communityId, keyword);
    }

    @PostMapping("/payment-orders")
    Object createOrder(@Valid @RequestBody FinanceService.PaymentOrderRequest request,
                       @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return service.createPaymentOrder(request, key);
    }

    @PostMapping("/payment-orders/{id}:confirm-simulated")
    Object confirm(@PathVariable String id, @RequestParam String communityId) {
        return service.confirm(id, communityId);
    }

    @PostMapping("/prepayment-accounts")
    Object createPrepayment(@Valid @RequestBody FinanceService.AccountRequest request) {
        return service.createPrepaymentAccount(request);
    }

    @PostMapping("/prepayment-accounts/{id}:top-up")
    Object topUp(@PathVariable String id, @Valid @RequestBody FinanceService.AccountAmountRequest request,
                 @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return service.topUp(id, request, key);
    }

    @PostMapping("/prepayment-accounts/{id}:apply")
    Object apply(@PathVariable String id, @Valid @RequestBody FinanceService.PrepaymentApply request,
                 @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return service.applyPrepayment(id, request, key);
    }

    @PostMapping("/deposits")
    Object collectDeposit(@Valid @RequestBody FinanceService.DepositCollect request,
                          @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return service.collectDeposit(request, key);
    }

    @PostMapping("/deposits/{id}:refund")
    Object refundDeposit(@PathVariable String id, @Valid @RequestBody FinanceService.AccountAmountRequest request,
                         @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return service.refundDeposit(id, request, key);
    }

    @PostMapping("/payment-transactions/{id}:reverse")
    Object reverse(@PathVariable String id, @Valid @RequestBody FinanceService.ReversalRequest request) {
        return service.reverse(id, request);
    }

    @PostMapping("/invoices:simulate")
    Object simulateInvoice(@Valid @RequestBody FinanceService.InvoiceRequest request) {
        return service.simulateInvoice(request);
    }
}
