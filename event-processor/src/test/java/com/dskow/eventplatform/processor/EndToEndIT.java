package com.dskow.eventplatform.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.dskow.eventplatform.processor.model.Event;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end durability test: produce N events into Kafka, wait for them to
 * appear in S3, assert no loss and no duplicates. Exercises the full path —
 * EventConsumer batch listener, S3Archiver retry logic, manual ack ordering,
 * and the date-prefixed key shape.
 */
@SpringBootTest(classes = ProcessorApplication.class)
@Testcontainers
class EndToEndIT {

    private static final String BUCKET = "it-event-archive";
    private static final String TOPIC = "events";

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:4.1.2"));

    @Container
    static LocalStackContainer localstack = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:3.7"))
        .withServices(LocalStackContainer.Service.S3);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("app.archive-bucket", () -> BUCKET);
        registry.add("app.events-topic", () -> TOPIC);
        registry.add("app.s3.endpoint",
            () -> localstack.getEndpointOverride(LocalStackContainer.Service.S3).toString());
        registry.add("app.s3.region", localstack::getRegion);
        // Tighten the retry windows so a misconfigured test fails fast.
        registry.add("app.archive-max-attempts", () -> "2");
        registry.add("app.archive-initial-backoff-ms", () -> "50");
        registry.add("app.archive-max-backoff-ms", () -> "100");
        // Keep the consumer poll batch tiny so we exercise multiple flushes.
        registry.add("spring.kafka.consumer.max-poll-records", () -> "5");
    }

    @BeforeAll
    static void createBucket() {
        try (S3Client client = S3Client.builder()
                .endpointOverride(URI.create(
                    localstack.getEndpointOverride(LocalStackContainer.Service.S3).toString()))
                .credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("test", "test")))
                .region(Region.of(localstack.getRegion()))
                .forcePathStyle(true)
                .build()) {
            client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        }
    }

    @Autowired
    KafkaTemplate<Object, Object> producer;

    @Autowired
    S3Client s3;

    @Test
    void producesNEventsAndArchivesAllNToS3WithoutLoss() {
        int n = 23;
        for (int i = 0; i < n; i++) {
            Event e = new Event(
                "e-" + i,
                "asset-1",
                Instant.now(),
                35.7 + i * 0.001,
                -78.6,
                "in-transit");
            producer.send(TOPIC, e.assetId(), e);
        }
        producer.flush();

        Awaitility.await()
            .atMost(Duration.ofSeconds(30))
            .pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> {
                Set<String> archivedIds = collectArchivedEventIds();
                assertThat(archivedIds)
                    .as("expected exactly %d unique event ids in S3", n)
                    .hasSize(n);
                for (int i = 0; i < n; i++) {
                    assertThat(archivedIds).contains("e-" + i);
                }
            });
    }

    private Set<String> collectArchivedEventIds() {
        var json = JsonMapper.builder().build();
        Set<String> ids = new HashSet<>();
        ListObjectsV2Response listing;
        String continuation = null;
        do {
            ListObjectsV2Request.Builder req = ListObjectsV2Request.builder()
                .bucket(BUCKET)
                .prefix("events/");
            if (continuation != null) {
                req.continuationToken(continuation);
            }
            listing = s3.listObjectsV2(req.build());
            for (S3Object obj : listing.contents()) {
                try (ResponseInputStream<GetObjectResponse> body = s3.getObject(
                        GetObjectRequest.builder().bucket(BUCKET).key(obj.key()).build())) {
                    Event[] batch = json.readValue(body.readAllBytes(), Event[].class);
                    for (Event e : batch) {
                        ids.add(e.id());
                    }
                } catch (Exception ex) {
                    throw new RuntimeException("failed to read " + obj.key(), ex);
                }
            }
            continuation = listing.isTruncated() ? listing.nextContinuationToken() : null;
        } while (continuation != null);
        return ids;
    }

    static {
        // Awaitility default timeout is 10s; we override per-call above but set
        // a sane global pollDelay so we don't hammer S3 listing.
        Awaitility.setDefaultPollDelay(0, TimeUnit.MILLISECONDS);
    }
}
