package com.dskow.eventplatform.processor.s3;

import com.dskow.eventplatform.processor.model.Event;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class S3Archiver {

    private static final Logger log = LoggerFactory.getLogger(S3Archiver.class);

    private static final DateTimeFormatter DATE_PREFIX =
        DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneOffset.UTC);

    private final S3Client s3;
    private final ObjectMapper json;
    private final String bucket;
    private final int maxAttempts;
    private final long initialBackoffMs;
    private final long maxBackoffMs;

    public S3Archiver(
            S3Client s3,
            ObjectMapper archiveObjectMapper,
            @Value("${app.archive-bucket:event-archive}") String bucket,
            @Value("${app.archive-max-attempts:3}") int maxAttempts,
            @Value("${app.archive-initial-backoff-ms:100}") long initialBackoffMs,
            @Value("${app.archive-max-backoff-ms:2000}") long maxBackoffMs) {
        this.s3 = s3;
        this.json = archiveObjectMapper;
        this.bucket = bucket;
        this.maxAttempts = maxAttempts;
        this.initialBackoffMs = initialBackoffMs;
        this.maxBackoffMs = maxBackoffMs;
    }

    /**
     * Serialize the batch and PUT it to S3, retrying transient failures with
     * exponential backoff. Throws on terminal failure so the Kafka listener does
     * not ack — the consumer will redeliver, or DefaultErrorHandler will route to
     * the DLT after its own retry budget is exhausted.
     *
     * Non-retriable conditions (bad bucket name, serialization bugs) are logged
     * and rethrown immediately rather than wasting the retry budget.
     */
    public void archive(List<Event> batch) {
        if (batch.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        String key = "events/"
            + DATE_PREFIX.format(now)
            + "/"
            + now.toEpochMilli()
            + "-"
            + UUID.randomUUID()
            + "-"
            + batch.size()
            + ".json";

        byte[] payload;
        try {
            payload = json.writeValueAsBytes(batch);
        } catch (JacksonException e) {
            log.error("failed to serialise batch of {} for s3://{}/{}: {}",
                batch.size(), bucket, key, e.getMessage());
            throw new ArchiveException("serialization failed", e);
        }

        long backoff = initialBackoffMs;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                s3.putObject(
                    PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType("application/json")
                        .build(),
                    RequestBody.fromBytes(payload)
                );
                log.info("archived batch of {} events to s3://{}/{} (attempt {})",
                    batch.size(), bucket, key, attempt);
                return;
            } catch (NoSuchBucketException e) {
                // Configuration bug — retrying will not help.
                log.error("archive bucket {} does not exist; aborting batch of {}",
                    bucket, batch.size());
                throw new ArchiveException("bucket missing: " + bucket, e);
            } catch (RuntimeException e) {
                if (attempt == maxAttempts) {
                    log.error("failed to archive batch of {} to s3://{}/{} after {} attempts",
                        batch.size(), bucket, key, attempt, e);
                    throw new ArchiveException("s3 putObject failed after retries", e);
                }
                log.warn("attempt {}/{} to archive batch of {} failed: {} — retrying in {} ms",
                    attempt, maxAttempts, batch.size(), e.getMessage(), backoff);
                sleep(backoff);
                backoff = Math.min(backoff * 2, maxBackoffMs);
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new ArchiveException("interrupted during backoff", ie);
        }
    }

    public static class ArchiveException extends RuntimeException {
        public ArchiveException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
