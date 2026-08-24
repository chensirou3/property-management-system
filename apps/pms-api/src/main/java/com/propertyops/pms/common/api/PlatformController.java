package com.propertyops.pms.common.api;

import java.time.Instant;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/platform")
public class PlatformController {
    @GetMapping("/runtime")
    Map<String, Object> runtime() {
        return Map.of(
                "name", "物业管理平台",
                "environment", "synthetic-data",
                "paymentAdapter", "simulator",
                "invoiceAdapter", "simulator",
                "iotAdapter", "simulator",
                "serverTime", Instant.now().toString()
        );
    }
}

