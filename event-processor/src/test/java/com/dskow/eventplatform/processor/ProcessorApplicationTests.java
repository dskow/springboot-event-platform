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
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;

class ProcessorApplicationTests {

    @Test
    void onBatchArchivesAndAcknowledgesOnSuccess() {
        S3Archiver archiver = Mockito.mock(S3Archiver.class);
        Acknowledgment ack = Mockito.mock(Acknowledgment.class);
        MeterRegistry meters = new SimpleMeterRegistry();
        EventConsumer consumer = new EventConsumer(archiver, meters);

        List<ConsumerRecord<String, Event>> batch = List.of(record("a"), record("b"), record("c"));
        consumer.onBatch(batch, ack);

        Mockito.verify(archiver).archive(List.of(event("a"), event("b"), event("c")));
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

        assertThatThrownBy(() -> consumer.onBatch(List.of(record("x")), ack))
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

    @Test
    void onBatchArchivesCleanPrefixThenThrowsForDeserializationFailure() {
        // [good, good, BAD, good] — archive the first two, route the third to DLT
        // via BatchListenerFailedException(2). DefaultErrorHandler will commit
        // offsets up to index 1 and recover index 2 to the DLT.
        S3Archiver archiver = Mockito.mock(S3Archiver.class);
        Acknowledgment ack = Mockito.mock(Acknowledgment.class);
        MeterRegistry meters = new SimpleMeterRegistry();
        EventConsumer consumer = new EventConsumer(archiver, meters);

        List<ConsumerRecord<String, Event>> batch = List.of(
            record("a"),
            record("b"),
            poisonRecord(),
            record("d"));

        assertThatThrownBy(() -> consumer.onBatch(batch, ack))
            .isInstanceOfSatisfying(BatchListenerFailedException.class,
                ex -> assertThat(ex.getIndex()).isEqualTo(2));

        Mockito.verify(archiver).archive(List.of(event("a"), event("b")));
        Mockito.verifyNoInteractions(ack);
        assertThat(meters.counter("events.archived").count()).isEqualTo(2.0);
        assertThat(meters.counter("kafka.deserialization.failures").count()).isEqualTo(1.0);
    }

    @Test
    void onBatchSkipsArchiveWhenFirstRecordIsPoisoned() {
        // [BAD, good, good] — nothing to archive before index 0; throw immediately
        // so DefaultErrorHandler routes index 0 to the DLT and the next poll re-
        // delivers the remaining two from the broker.
        S3Archiver archiver = Mockito.mock(S3Archiver.class);
        Acknowledgment ack = Mockito.mock(Acknowledgment.class);
        MeterRegistry meters = new SimpleMeterRegistry();
        EventConsumer consumer = new EventConsumer(archiver, meters);

        List<ConsumerRecord<String, Event>> batch = List.of(
            poisonRecord(),
            record("b"),
            record("c"));

        assertThatThrownBy(() -> consumer.onBatch(batch, ack))
            .isInstanceOfSatisfying(BatchListenerFailedException.class,
                ex -> assertThat(ex.getIndex()).isZero());

        Mockito.verifyNoInteractions(archiver);
        Mockito.verifyNoInteractions(ack);
        assertThat(meters.counter("kafka.deserialization.failures").count()).isEqualTo(1.0);
        assertThat(meters.counter("events.archived").count()).isZero();
    }

    private Event event(String id) {
        return new Event(id, "asset-1", Instant.now(), 35.7, -78.6, "in-transit");
    }

    private ConsumerRecord<String, Event> record(String id) {
        return new ConsumerRecord<>("events", 0, 0L, "asset-1", event(id));
    }

    private ConsumerRecord<String, Event> poisonRecord() {
        ConsumerRecord<String, Event> rec = new ConsumerRecord<>("events", 0, 0L, "asset-1", null);
        rec.headers().add(new RecordHeader(
            ErrorHandlingDeserializer.VALUE_DESERIALIZER_EXCEPTION_HEADER,
            "boom".getBytes()));
        return rec;
    }
}
