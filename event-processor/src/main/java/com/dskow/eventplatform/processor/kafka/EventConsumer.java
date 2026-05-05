package com.dskow.eventplatform.processor.kafka;

import com.dskow.eventplatform.processor.model.Event;
import com.dskow.eventplatform.processor.s3.S3Archiver;
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
    private final BlockingQueue<Event> buffer = new LinkedBlockingQueue<>();
    private final AtomicLong processedCount = new AtomicLong();

    public EventConsumer(
            S3Archiver archiver,
            @Value("${app.archive-batch-size:100}") int batchSize) {
        this.archiver = archiver;
        this.batchSize = batchSize;
    }

    /**
     * Each Kafka record is dispatched to a virtual thread (see ProcessorApplication's
     * processorExecutor bean). The buffer is drained either when it fills to
     * batch-size or by the periodic flush below — whichever comes first.
     */
    @KafkaListener(topics = "${app.events-topic:events}", groupId = "event-processor")
    @Async("processorExecutor")
    public void onEvent(Event event) {
        log.debug("processing event {} for asset {}", event.id(), event.assetId());
        buffer.offer(event);
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
}
