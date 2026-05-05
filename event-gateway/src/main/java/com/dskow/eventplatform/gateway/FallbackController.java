package com.dskow.eventplatform.gateway;

import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/fallback")
public class FallbackController {

    // Match the verb of the upstream route the circuit breaker forwards from.
    // The /api/events route accepts POST only, so a fallback that answered
    // every verb would invent capabilities the real endpoint never offered.
    @PostMapping("/events")
    public ResponseEntity<Map<String, Object>> eventsFallback() {
        return ResponseEntity
            .status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(Map.of(
                "status", "degraded",
                "message", "event-ingest is currently unavailable; circuit breaker open",
                "timestamp", Instant.now().toString()
            ));
    }
}
