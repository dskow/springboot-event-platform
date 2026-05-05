package com.dskow.eventplatform.ingest.api;

import com.dskow.eventplatform.ingest.kafka.EventProducer;
import com.dskow.eventplatform.ingest.model.Event;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events")
public class EventController {

    private final EventProducer producer;

    public EventController(EventProducer producer) {
        this.producer = producer;
    }

    @PostMapping
    public ResponseEntity<Event> ingest(@Valid @RequestBody Event incoming) {
        Event stamped = new Event(
            incoming.id() == null ? UUID.randomUUID().toString() : incoming.id(),
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
