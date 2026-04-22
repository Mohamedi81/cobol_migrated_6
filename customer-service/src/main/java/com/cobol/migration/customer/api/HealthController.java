package com.cobol.migration.customer.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Initial customer-service API skeleton.
 * Replaces basic availability of customer CICS transactions for health monitoring.
 */
@RestController
public class HealthController {

    @GetMapping("/actuator/simple-health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "timestamp", Instant.now().toString(),
                "service", "customer-service"
        );
    }
}
