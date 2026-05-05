package com.dskow.eventplatform.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dskow.eventplatform.processor.kafka.EventConsumer;
import com.dskow.eventplatform.processor.model.Event;
import com.dskow.eventplatform.processor.s3.S3Archiver;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.kafka.support.Acknowledgment;

class ProcessorApplicationTests {

    @Test
    void onBatchArchivesAndAcknowledgesOnSuccess() {
        S3Archiver archiver = Mockito.mock(S3Archiver.class);
        Acknowledgment ack = Mockito.mock(Acknowledgment.class);
        MeterRegistry meters = new SimpleMeterRegistry();
        EventConsumer consumer = new EventConsumer(archiver, meters);

        List<Event> batch = List.of(event("a"), event("b"), event("c"));
        consumer.onBatch(batch, ack);

        Mockito.verify(archiver).archive(batch);
        Mockito.verify(ack).acknowledge();
        assertThat(consumer.getProcessedCount()).isEqualTo(3);
        assertThat(meters.counter("events.archived").count()).isEqualTo(3.0);
        assertThat(meters.counter("archive.batches").count()).isEqualTo(1.0);
        assertThat(meters.counter("archive.failures").count()).isZero();
    }

    @Test
    void onBatchDoesNotAckAndIncrementsFailureCounterWhenArchiverThrows() {
        S3Archiver archiver = Mockito.mock(S3Archiver.class);
        Acknowledgment ack = Mockito.mock(Acknowledgment.class);
        MeterRegistry meters = new SimpleMeterRegistry();
        Mockito.doThrow(new S3Archiver.ArchiveException("boom", new RuntimeException()))
            .when(archiver).archive(Mockito.anyList());
        EventConsumer consumer = new EventConsumer(archiver, meters);

        assertThatThrownBy(() -> consumer.onBatch(List.of(event("x")), ack))
            .isInstanceOf(S3Archiver.ArchiveException.class);

        Mockito.verifyNoInteractions(ack);
        assertThat(meters.counter("archive.failures").count()).isEqualTo(1.0);
        assertThat(meters.counter("events.archived").count()).isZero();
    }

    @Test
    void onBatchAcksAndReturnsEarlyForEmptyPoll() {
        S3Archiver archiver = Mockito.mock(S3Archiver.class);
        Acknowledgment ack = Mockito.mock(Acknowledgment.class);
        MeterRegistry meters = new SimpleMeterRegistry();
        EventConsumer consumer = new EventConsumer(archiver, meters);

        consumer.onBatch(List.of(), ack);

        Mockito.verifyNoInteractions(archiver);
        Mockito.verify(ack).acknowledge();
    }

    private Event event(String id) {
        return new Event(id, "asset-1", Instant.now(), 35.7, -78.6, "in-transit");
    }
}
