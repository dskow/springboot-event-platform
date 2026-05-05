package com.dskow.eventplatform.ingest.kafka;

import com.dskow.eventplatform.ingest.model.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class EventProducer {

    private static final Logger log = LoggerFactory.getLogger(EventProducer.class);

    private final KafkaTemplate<String, Event> kafkaTemplate;
    private final String topic;

    public EventProducer(
            KafkaTemplate<String, Event> kafkaTemplate,
            @Value("${app.events-topic:events}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public void send(Event event) {
        kafkaTemplate.send(topic, event.assetId(), event)
            .whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("failed to publish event {}: {}", safeId(event.id()), ex.getMessage());
                } else {
                    log.debug("published event {} to {}-{}@{}",
                        safeId(event.id()),
                        result.getRecordMetadata().topic(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
                }
            });
    }

    /**
     * Render an event id safely for logs. Bean validation on {@link Event}
     * already restricts the id to {@code [A-Za-z0-9._-]+} at the controller
     * boundary; this is defence in depth for any path that constructs an
     * {@code Event} without that validator (test fixtures, future producers
     * for a different transport) so a hostile id can't forge log lines via
     * embedded CR/LF or terminal escape sequences.
     */
    static String safeId(String id) {
        if (id == null) {
            return "<null>";
        }
        String filtered = id.replaceAll("[^A-Za-z0-9._-]", "_");
        return filtered.length() > 128 ? filtered.substring(0, 128) + "…" : filtered;
    }
}
