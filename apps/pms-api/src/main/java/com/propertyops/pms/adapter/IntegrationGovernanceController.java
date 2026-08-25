package com.propertyops.pms.adapter;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.propertyops.pms.common.api.RequestIdFilter;

@RestController
@RequestMapping("/api/v1/integrations")
public class IntegrationGovernanceController {
    private final IntegrationGovernanceService integrations;

    public IntegrationGovernanceController(IntegrationGovernanceService integrations) {
        this.integrations = integrations;
    }

    @GetMapping("/workbench")
    Map<String, Object> workbench(@RequestParam String communityId) {
        return integrations.workbench(communityId);
    }

    @PostMapping("/adapters/{adapterCode}:test")
    Map<String, Object> testAdapter(@PathVariable String adapterCode, @RequestParam String communityId) {
        return integrations.testAdapter(communityId, adapterCode);
    }

    @PostMapping("/callbacks/{adapterCode}")
    Map<String, Object> callback(@PathVariable String adapterCode,
                                 @RequestHeader("X-PMS-Callback-Id") String callbackId,
                                 @RequestHeader("X-PMS-Timestamp") String timestamp,
                                 @RequestHeader("X-PMS-Signature") String signature,
                                 @RequestBody String payload,
                                 HttpServletRequest request) {
        String requestId = String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE));
        return integrations.acceptCallback(adapterCode, callbackId, timestamp, signature, payload, requestId);
    }

    @PostMapping("/outbox-events:simulate")
    Map<String, Object> createOutbox(@Valid @RequestBody IntegrationModels.SimulatedOutboxRequest request) {
        return integrations.createSimulatedOutbox(request);
    }

    @PostMapping("/outbox-events/{id}:simulate-delivery")
    Map<String, Object> deliver(@PathVariable String id,
                                @Valid @RequestBody IntegrationModels.SimulatedDeliveryRequest request) {
        return integrations.simulateDelivery(id, request);
    }

    @PostMapping("/outbox-events:drain-simulated")
    Map<String, Object> drain(@RequestParam String communityId) {
        return integrations.drainSimulated(communityId);
    }

    @PostMapping("/dead-letters/{id}:replay")
    Map<String, Object> replay(@PathVariable String id) {
        return integrations.replayDeadLetter(id);
    }
}
