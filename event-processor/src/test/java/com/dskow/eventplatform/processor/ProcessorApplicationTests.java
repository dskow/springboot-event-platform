package com.dskow.eventplatform.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.dskow.eventplatform.processor.kafka.EventConsumer;
import com.dskow.eventplatform.processor.model.Event;
import com.dskow.eventplatform.processor.s3.S3Archiver;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class ProcessorApplicationTests {

    @Test
    void consumerBuffersUntilBatchSizeThenFlushesToArchiver() throws InterruptedException {
        S3Archiver archiver = Mockito.mock(S3Archiver.class);
        EventConsumer consumer = new EventConsumer(archiver, 3, 100);

        consumer.onEvent(event("a"));
        consumer.onEvent(event("b"));
        Mockito.verifyNoInteractions(archiver);

        consumer.onEvent(event("c"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Event>> captor = ArgumentCaptor.forClass(List.class);
        Mockito.verify(archiver).archive(captor.capture());
        assertThat(captor.getValue()).hasSize(3);
        assertThat(consumer.getProcessedCount()).isEqualTo(3);
    }

    @Test
    void drainOnShutdownFlushesRemainingBuffer() throws InterruptedException {
        S3Archiver archiver = Mockito.mock(S3Archiver.class);
        EventConsumer consumer = new EventConsumer(archiver, 100, 100);

        consumer.onEvent(event("x"));
        consumer.onEvent(event("y"));
        Mockito.verifyNoInteractions(archiver);

        consumer.drainOnShutdown();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Event>> captor = ArgumentCaptor.forClass(List.class);
        Mockito.verify(archiver).archive(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
    }

    private Event event(String id) {
        return new Event(id, "asset-1", Instant.now(), 35.7, -78.6, "in-transit");
    }
}
