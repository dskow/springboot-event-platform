package com.dskow.eventplatform.processor.kafka;

import com.dskow.eventplatform.processor.model.Event;
import com.dskow.eventplatform.processor.s3.S3Archiver;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class EventConsumer {

    private static final Logger log = LoggerFactory.getLogger(EventConsumer.class);

    private final S3Archiver archiver;
    private final int batchSize;
    private final BlockingQueue<Event> buffer;
    private final AtomicLong processedCount = new AtomicLong();

    public EventConsumer(
            S3Archiver archiver,
            @Value("${app.archive-batch-size:100}") int batchSize,
            @Value("${app.archive-buffer-capacity:10000}") int bufferCapacity) {
        this.archiver = archiver;
        this.batchSize = batchSize;
        this.buffer = new LinkedBlockingQueue<>(bufferCapacity);
    }

    /**
     * Each Kafka record is dispatched to a virtual thread (see ProcessorApplication's
     * processorExecutor bean). The buffer is drained either when it fills to
     * batch-size or by the periodic flush below — whichever comes first.
     */
    @KafkaListener(topics = "${app.events-topic:events}", groupId = "event-processor")
    @Async("processorExecutor")
    public void onEvent(Event event) throws InterruptedException {
        log.debug("processing event {} for asset {}", event.id(), event.assetId());
        // put() blocks when the buffer is full so back-pressure flows back to the
        // Kafka consumer instead of forcing an unbounded heap. Virtual-thread blocking
        // is cheap; the listener container thread that picked up this record will
        // simply pause polling.
        buffer.put(event);
        processedCount.incrementAndGet();
        if (buffer.size() >= batchSize) {
            flush();
        }
    }

    @Scheduled(fixedDelayString = "${app.archive-flush-ms:5000}")
    void flushScheduled() {
        if (!buffer.isEmpty()) {
            flush();
        }
    }

    private synchronized void flush() {
        if (buffer.isEmpty()) {
            return;
        }
        List<Event> drained = new ArrayList<>(batchSize);
        buffer.drainTo(drained, batchSize);
        if (!drained.isEmpty()) {
            archiver.archive(drained);
        }
    }

    public long getProcessedCount() {
        return processedCount.get();
    }

    /**
     * Drain the buffer to S3 on container shutdown so a rolling deploy does not
     * silently lose buffered events. Repeats until the buffer is empty since
     * concurrent producers may still be racing to put() during the shutdown window.
     */
    @PreDestroy
    public void drainOnShutdown() {
        if (buffer.isEmpty()) {
            return;
        }
        log.info("draining {} buffered events on shutdown", buffer.size());
        while (!buffer.isEmpty()) {
            flush();
        }
    }
}
