package com.dskow.eventplatform.processor.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dskow.eventplatform.processor.model.Event;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import tools.jackson.databind.json.JsonMapper;

class S3ArchiverTest {

    private final List<Event> batch = List.of(
        new Event("a", "asset-1", Instant.now(), 1.0, 2.0, "in-transit")
    );

    @Test
    void archiveSucceedsOnFirstAttempt() {
        S3Client s3 = Mockito.mock(S3Client.class);
        Mockito.when(s3.putObject(Mockito.any(PutObjectRequest.class), Mockito.any(RequestBody.class)))
            .thenReturn(PutObjectResponse.builder().build());

        S3Archiver archiver = new S3Archiver(s3, JsonMapper.builder().build(),
            "test-bucket", 3, 1L, 1L, false);

        archiver.archive(batch);

        Mockito.verify(s3, Mockito.times(1))
            .putObject(Mockito.any(PutObjectRequest.class), Mockito.any(RequestBody.class));
    }

    @Test
    void archiveRetriesTransientFailuresThenSucceeds() {
        S3Client s3 = Mockito.mock(S3Client.class);
        Mockito.when(s3.putObject(Mockito.any(PutObjectRequest.class), Mockito.any(RequestBody.class)))
            .thenThrow(AwsServiceException.builder().message("transient").build())
            .thenThrow(AwsServiceException.builder().message("transient").build())
            .thenReturn(PutObjectResponse.builder().build());

        S3Archiver archiver = new S3Archiver(s3, JsonMapper.builder().build(),
            "test-bucket", 3, 1L, 1L, false);

        archiver.archive(batch);

        Mockito.verify(s3, Mockito.times(3))
            .putObject(Mockito.any(PutObjectRequest.class), Mockito.any(RequestBody.class));
    }

    @Test
    void archiveThrowsAfterMaxAttempts() {
        S3Client s3 = Mockito.mock(S3Client.class);
        Mockito.when(s3.putObject(Mockito.any(PutObjectRequest.class), Mockito.any(RequestBody.class)))
            .thenThrow(AwsServiceException.builder().message("persistent").build());

        S3Archiver archiver = new S3Archiver(s3, JsonMapper.builder().build(),
            "test-bucket", 3, 1L, 1L, false);

        assertThatThrownBy(() -> archiver.archive(batch))
            .isInstanceOf(S3Archiver.ArchiveException.class)
            .hasMessageContaining("retries");

        Mockito.verify(s3, Mockito.times(3))
            .putObject(Mockito.any(PutObjectRequest.class), Mockito.any(RequestBody.class));
    }

    @Test
    void archiveDoesNotRetryNoSuchBucket() {
        S3Client s3 = Mockito.mock(S3Client.class);
        Mockito.when(s3.putObject(Mockito.any(PutObjectRequest.class), Mockito.any(RequestBody.class)))
            .thenThrow(NoSuchBucketException.builder().message("nope").build());

        S3Archiver archiver = new S3Archiver(s3, JsonMapper.builder().build(),
            "test-bucket", 3, 1L, 1L, false);

        assertThatThrownBy(() -> archiver.archive(batch))
            .isInstanceOf(S3Archiver.ArchiveException.class)
            .hasMessageContaining("bucket missing");

        Mockito.verify(s3, Mockito.times(1))
            .putObject(Mockito.any(PutObjectRequest.class), Mockito.any(RequestBody.class));
    }

    @Test
    void archiveSkipsEmptyBatch() {
        S3Client s3 = Mockito.mock(S3Client.class);

        S3Archiver archiver = new S3Archiver(s3, JsonMapper.builder().build(),
            "test-bucket", 3, 1L, 1L, false);

        archiver.archive(List.of());

        Mockito.verifyNoInteractions(s3);
    }

    @Test
    void archiveKeyIncludesDatePrefixAndUuid() {
        S3Client s3 = Mockito.mock(S3Client.class);
        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        Mockito.when(s3.putObject(captor.capture(), Mockito.any(RequestBody.class)))
            .thenReturn(PutObjectResponse.builder().build());

        S3Archiver archiver = new S3Archiver(s3, JsonMapper.builder().build(),
            "test-bucket", 3, 1L, 1L, false);

        archiver.archive(batch);

        String key = captor.getValue().key();
        assertThat(key).startsWith("events/");
        assertThat(key).matches("events/\\d{4}/\\d{2}/\\d{2}/\\d+-[0-9a-f-]{36}-1\\.json");
    }
}
