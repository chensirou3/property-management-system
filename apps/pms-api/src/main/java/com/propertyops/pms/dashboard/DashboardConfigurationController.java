package com.propertyops.pms.dashboard;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardConfigurationController {
    private final DashboardConfigurationService service;

    public DashboardConfigurationController(DashboardConfigurationService service) {
        this.service = service;
    }

    @GetMapping("/configurations")
    Object configurations(@RequestParam String communityId,
                          @RequestParam(defaultValue = "ALL") String roleCode) {
        return service.configurations(communityId, roleCode);
    }

    @PostMapping("/configurations")
    Object create(@Valid @RequestBody DashboardConfigurationModels.CreateWidgetRequest request) {
        return service.create(request);
    }

    @PutMapping("/configurations/{id}")
    Object update(@PathVariable String id, @RequestParam String communityId,
                  @Valid @RequestBody DashboardConfigurationModels.UpdateWidgetRequest request) {
        return service.update(id, communityId, request);
    }

    @PostMapping("/configurations:reorder")
    Object reorder(@Valid @RequestBody DashboardConfigurationModels.ScopeCommand request) {
        return service.reorder(request);
    }

    @PostMapping("/configurations:publish")
    Object publish(@Valid @RequestBody DashboardConfigurationModels.ScopeCommand request) {
        return service.publish(request);
    }
}
