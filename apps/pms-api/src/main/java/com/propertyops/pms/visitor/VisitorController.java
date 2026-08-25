package com.propertyops.pms.visitor;

import java.time.LocalDate;

import jakarta.validation.Valid;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/visitors")
public class VisitorController {
    private final VisitorService service;

    public VisitorController(VisitorService service) {
        this.service = service;
    }

    @GetMapping
    Object records(@RequestParam String communityId,
                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                   @RequestParam(required = false) String keyword,
                   @RequestParam(required = false) String status,
                   @RequestParam(defaultValue = "1") int page,
                   @RequestParam(defaultValue = "20") int size) {
        return service.records(communityId, from, to, keyword, status, page, size);
    }

    @PostMapping
    Object register(@Valid @RequestBody VisitorModels.RegisterRequest request) {
        return service.register(request);
    }

    @PostMapping("/{id}:check-in")
    Object checkIn(@PathVariable String id, @Valid @RequestBody VisitorModels.TransitionRequest request) {
        return service.checkIn(id, request);
    }

    @PostMapping("/{id}:check-out")
    Object checkOut(@PathVariable String id, @Valid @RequestBody VisitorModels.TransitionRequest request) {
        return service.checkOut(id, request);
    }
}
