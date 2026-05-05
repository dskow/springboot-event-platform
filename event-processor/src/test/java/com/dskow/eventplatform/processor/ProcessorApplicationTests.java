package com.dskow.eventplatform.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dskow.eventplatform.processor.kafka.EventConsumer;
import com.dskow.eventplatform.processor.model.Event;
import com.dskow.eventplatform.processor.s3.S3Archiver;
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
        EventConsumer consumer = new EventConsumer(archiver);

        List<Event> batch = List.of(event("a"), event("b"), event("c"));
        consumer.onBatch(batch, ack);

        Mockito.verify(archiver).archive(batch);
        Mockito.verify(ack).acknowledge();
        assertThat(consumer.getProcessedCount()).isEqualTo(3);
    }

    @Test
    void onBatchDoesNotAckWhenArchiverThrows() {
        S3Archiver archiver = Mockito.mock(S3Archiver.class);
        Acknowledgment ack = Mockito.mock(Acknowledgment.class);
        Mockito.doThrow(new S3Archiver.ArchiveException("boom", new RuntimeException()))
            .when(archiver).archive(Mockito.anyList());
        EventConsumer consumer = new EventConsumer(archiver);

        assertThatThrownBy(() -> consumer.onBatch(List.of(event("x")), ack))
            .isInstanceOf(S3Archiver.ArchiveException.class);

        Mockito.verifyNoInteractions(ack);
        assertThat(consumer.getProcessedCount()).isZero();
    }

    @Test
    void onBatchAcksAndReturnsEarlyForEmptyPoll() {
        S3Archiver archiver = Mockito.mock(S3Archiver.class);
        Acknowledgment ack = Mockito.mock(Acknowledgment.class);
        EventConsumer consumer = new EventConsumer(archiver);

        consumer.onBatch(List.of(), ack);

        Mockito.verifyNoInteractions(archiver);
        Mockito.verify(ack).acknowledge();
    }

    private Event event(String id) {
        return new Event(id, "asset-1", Instant.now(), 35.7, -78.6, "in-transit");
    }
}
