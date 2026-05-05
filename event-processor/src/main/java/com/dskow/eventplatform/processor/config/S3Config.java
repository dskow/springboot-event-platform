package com.dskow.eventplatform.processor.config;

import java.net.URI;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration
public class S3Config {

    /**
     * S3 client wired for both LocalStack (when {@code app.s3.endpoint} is set)
     * and real AWS (default credential chain — IAM role, env vars, etc.).
     */
    @Bean
    public S3Client s3Client(
            @Value("${app.s3.endpoint:#{null}}") String endpoint,
            @Value("${app.s3.region:us-east-1}") String region) {
        var builder = S3Client.builder().region(Region.of(region));
        if (endpoint != null && !endpoint.isBlank()) {
            builder
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("test", "test")))
                .forcePathStyle(true);
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }
        return builder.build();
    }

    @Bean
    public ObjectMapper archiveObjectMapper() {
        // Jackson 3 has built-in java.time support; no jsr310 module needed
        return JsonMapper.builder().build();
    }
}
