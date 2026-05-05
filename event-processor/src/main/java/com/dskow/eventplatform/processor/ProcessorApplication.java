package com.dskow.eventplatform.processor;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
public class ProcessorApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProcessorApplication.class, args);
    }

    /**
     * Virtual-thread-per-task executor. Each Kafka record is processed on its own
     * lightweight thread so blocking I/O (S3 archival) does not stall the consumer.
     */
    @Bean(name = "processorExecutor")
    public Executor processorExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
