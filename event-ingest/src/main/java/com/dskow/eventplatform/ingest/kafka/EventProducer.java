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
                    log.error("failed to publish event {}: {}", event.id(), ex.getMessage());
                } else {
                    log.debug("published event {} to {}-{}@{}",
                        event.id(),
                        result.getRecordMetadata().topic(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
                }
            });
    }
}
