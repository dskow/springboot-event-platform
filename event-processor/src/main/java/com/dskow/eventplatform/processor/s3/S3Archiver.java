package com.dskow.eventplatform.processor.s3;

import com.dskow.eventplatform.processor.model.Event;
import java.time.Instant;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Component
public class S3Archiver {

    private static final Logger log = LoggerFactory.getLogger(S3Archiver.class);

    private final S3Client s3;
    private final ObjectMapper json;
    private final String bucket;

    public S3Archiver(
            S3Client s3,
            ObjectMapper archiveObjectMapper,
            @Value("${app.archive-bucket:event-archive}") String bucket) {
        this.s3 = s3;
        this.json = archiveObjectMapper;
        this.bucket = bucket;
    }

    public void archive(List<Event> batch) {
        if (batch.isEmpty()) {
            return;
        }
        String key = "events/" + Instant.now().toEpochMilli() + "-" + batch.size() + ".json";
        try {
            byte[] payload = json.writeValueAsBytes(batch);
            s3.putObject(
                PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType("application/json")
                    .build(),
                RequestBody.fromBytes(payload)
            );
            log.info("archived batch of {} events to s3://{}/{}", batch.size(), bucket, key);
        } catch (NoSuchBucketException e) {
            log.error("archive bucket {} does not exist; skipping batch of {}", bucket, batch.size());
        } catch (JacksonException e) {
            log.error("failed to serialise batch of {}: {}", batch.size(), e.getMessage());
        } catch (RuntimeException e) {
            log.error("failed to archive batch of {} to s3://{}/{}", batch.size(), bucket, key, e);
        }
    }
}
