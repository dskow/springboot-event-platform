package com.dskow.eventplatform.processor.kafka;

import com.dskow.eventplatform.processor.model.Event;
import com.dskow.eventplatform.processor.s3.S3Archiver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.stereotype.Component;

@Component
public class EventConsumer {

    private static final Logger log = LoggerFactory.getLogger(EventConsumer.class);

    private final S3Archiver archiver;
    private final Counter archivedEvents;
    private final Counter archivedBatches;
    private final Counter archiveFailures;
    private final Counter deserializationFailures;

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
        this.deserializationFailures = Counter.builder("kafka.deserialization.failures")
            .description("Records that failed deserialization and were routed to the DLT")
            .register(meters);
    }

    /**
     * Each poll returns up to {@code spring.kafka.consumer.max-poll-records} records.
     * The clean prefix is archived to S3 inline and offsets are acked only after the
     * write succeeds — so a JVM crash between poll and ack causes Kafka to redeliver
     * the same records, not silently lose them.
     *
     * Records that failed deserialization arrive as {@code null} values with the
     * original failure stashed in {@link ErrorHandlingDeserializer}'s exception
     * headers. We archive every record before the first such failure, then throw
     * {@link BatchListenerFailedException} so the configured DefaultErrorHandler
     * commits offsets for the archived prefix and routes only the bad record to
     * the DLT. Without this, a single poison-pill record would either crash on a
     * {@code null} in the serialized JSON or block the partition.
     *
     * If S3 throws after its internal retry budget, the exception propagates out of
     * this method and {@link com.dskow.eventplatform.processor.config.KafkaConsumerConfig}'s
     * DefaultErrorHandler retries the batch (exponential backoff, 3 attempts) before
     * routing each record to the DLT.
     */
    @KafkaListener(topics = "${app.events-topic:events}", groupId = "event-processor")
    public void onBatch(List<ConsumerRecord<String, Event>> records, Acknowledgment ack) {
        if (records.isEmpty()) {
            ack.acknowledge();
            return;
        }

        int failIdx = findDeserializationFailure(records);
        int archiveEnd = failIdx == -1 ? records.size() : failIdx;

        if (archiveEnd > 0) {
            List<Event> clean = new ArrayList<>(archiveEnd);
            for (int i = 0; i < archiveEnd; i++) {
                clean.add(records.get(i).value());
            }
            log.debug("archiving batch of {} events", clean.size());
            try {
                archiver.archive(clean);
            } catch (RuntimeException e) {
                archiveFailures.increment();
                throw e;
            }
            archivedEvents.increment(clean.size());
            archivedBatches.increment();
        }

        if (failIdx >= 0) {
            ConsumerRecord<String, Event> bad = records.get(failIdx);
            log.warn("deserialization failure at offset {} of {}-{}; routing record to DLT",
                bad.offset(), bad.topic(), bad.partition());
            deserializationFailures.increment();
            // BatchListenerFailedException tells DefaultErrorHandler that records
            // before failIdx succeeded — offsets can advance — and only this index
            // needs recovery. The recoverer (configured in KafkaConsumerConfig)
            // republishes to the DLT with the original headers preserved.
            throw new BatchListenerFailedException("deserialization failure", failIdx);
        }

        ack.acknowledge();
    }

    private static int findDeserializationFailure(List<ConsumerRecord<String, Event>> records) {
        for (int i = 0; i < records.size(); i++) {
            var headers = records.get(i).headers();
            if (headers.lastHeader(ErrorHandlingDeserializer.VALUE_DESERIALIZER_EXCEPTION_HEADER) != null
                    || headers.lastHeader(ErrorHandlingDeserializer.KEY_DESERIALIZER_EXCEPTION_HEADER) != null) {
                return i;
            }
        }
        return -1;
    }
}
