package com.dskow.eventplatform.ingest.api;

import com.dskow.eventplatform.ingest.kafka.EventProducer;
import com.dskow.eventplatform.ingest.model.Event;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events")
public class EventController {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final EventProducer producer;

    public EventController(EventProducer producer) {
        this.producer = producer;
    }

    /**
     * Resolve the event id in priority order: explicit body field → Idempotency-Key
     * header → freshly generated UUID. Clients that need safe retry on POST should
     * send the header so the resulting event id is stable across retries; downstream
     * consumers can then dedupe on id.
     */
    @PostMapping
    public ResponseEntity<Event> ingest(
            @Valid @RequestBody Event incoming,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey) {
        String resolvedId;
        if (incoming.id() != null) {
            resolvedId = incoming.id();
        } else if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            resolvedId = idempotencyKey;
        } else {
            resolvedId = UUID.randomUUID().toString();
        }
        Event stamped = new Event(
            resolvedId,
            incoming.assetId(),
            incoming.timestamp() == null ? Instant.now() : incoming.timestamp(),
            incoming.latitude(),
            incoming.longitude(),
            incoming.status()
        );
        producer.send(stamped);
        return ResponseEntity.accepted().body(stamped);
    }
}
