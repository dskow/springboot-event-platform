package com.dskow.eventplatform.ingest.api;

import com.dskow.eventplatform.ingest.kafka.EventProducer;
import com.dskow.eventplatform.ingest.model.Event;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;
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

    /**
     * Conservative whitelist for the Idempotency-Key header. Bounds the length
     * (so a 1 MB header can't ride through to S3 as the event id) and forbids
     * control characters (so a header containing newlines can't forge log
     * lines further down the pipeline). Aligned with the same alphabet used
     * for {@code assetId} validation in {@link Event}.
     */
    private static final Pattern IDEMPOTENCY_KEY_PATTERN =
        Pattern.compile("^[A-Za-z0-9._-]{1,128}$");

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
        if (idempotencyKey != null && !IDEMPOTENCY_KEY_PATTERN.matcher(idempotencyKey).matches()) {
            return ResponseEntity.badRequest().build();
        }
        // Defense in depth: @DecimalMin/@DecimalMax on the record reject NaN and
        // ±Infinity in Hibernate Validator (BigDecimal.valueOf throws), but the
        // exception path is implementation-defined. An explicit isFinite check
        // here makes the contract unambiguous and survives a validator swap.
        if (!Double.isFinite(incoming.latitude()) || !Double.isFinite(incoming.longitude())) {
            return ResponseEntity.badRequest().build();
        }
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
