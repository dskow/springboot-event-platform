package com.dskow.eventplatform.processor.kafka;

import com.dskow.eventplatform.processor.model.Event;
import com.dskow.eventplatform.processor.s3.S3Archiver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class EventConsumer {

    private static final Logger log = LoggerFactory.getLogger(EventConsumer.class);

    private final S3Archiver archiver;
    private final Counter archivedEvents;
    private final Counter archivedBatches;
    private final Counter archiveFailures;

    public EventConsumer(S3Archiver archiver, MeterRegistry meters) {
        this.archiver = archiver;
        this.archivedEvents = Counter.builder("events.archived")
            .description("Events successfully written to S3")
            .register(meters);
        this.archivedBatches = Counter.builder("archive.batches")
            .description("S3 PutObject calls completed successfully")
            .register(meters);
        this.archiveFailures = Counter.builder("archive.failures")
            .description("S3 archive attempts that exhausted the retry budget")
            .register(meters);
    }

    /**
     * Each poll returns up to {@code spring.kafka.consumer.max-poll-records} events.
     * The batch is archived to S3 inline and the offsets are acked only after the
     * write succeeds — so a JVM crash between poll and ack causes Kafka to redeliver
     * the same records, not silently lose them.
     *
     * If S3 throws after its internal retry budget, the exception propagates out of
     * this method and {@link com.dskow.eventplatform.processor.config.KafkaConsumerConfig}'s
     * DefaultErrorHandler retries the batch (exponential backoff, 3 attempts) before
     * routing each record to the DLT.
     */
    @KafkaListener(topics = "${app.events-topic:events}", groupId = "event-processor")
    public void onBatch(List<Event> events, Acknowledgment ack) {
        if (events.isEmpty()) {
            ack.acknowledge();
            return;
        }
        log.debug("archiving batch of {} events", events.size());
        try {
            archiver.archive(events);
        } catch (RuntimeException e) {
            archiveFailures.increment();
            throw e;
        }
        archivedEvents.increment(events.size());
        archivedBatches.increment();
        ack.acknowledge();
    }

    public long getProcessedCount() {
        return (long) archivedEvents.count();
    }
}
