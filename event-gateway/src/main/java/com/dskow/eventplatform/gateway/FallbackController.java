package com.dskow.eventplatform.gateway;

import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/fallback")
public class FallbackController {

    @RequestMapping("/events")
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
