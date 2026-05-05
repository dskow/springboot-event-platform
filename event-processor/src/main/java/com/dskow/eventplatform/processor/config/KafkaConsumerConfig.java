package com.dskow.eventplatform.processor.config;

import com.dskow.eventplatform.processor.model.Event;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration
public class KafkaConsumerConfig {

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Event> kafkaListenerContainerFactory(
            ConsumerFactory<String, Event> consumerFactory,
            DefaultErrorHandler errorHandler) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, Event>();
        factory.setConsumerFactory(consumerFactory);
        factory.setBatchListener(true);
        factory.getContainerProperties().setAckMode(AckMode.MANUAL);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }

    /**
     * After the configured retry budget is exhausted (3 attempts with exponential
     * backoff), each failing record is republished to the DLT and the partition
     * advances. Without this the consumer would loop on a poison-pill record
     * forever, blocking every record behind it in the partition.
     */
    @Bean
    public DefaultErrorHandler errorHandler(
            KafkaTemplate<Object, Object> dltTemplate,
            @Value("${app.dlt-topic:events.DLT}") String dltTopic) {
        var recoverer = new DeadLetterPublishingRecoverer(
            dltTemplate,
            (record, ex) -> new TopicPartition(dltTopic, record.partition()));
        var backOff = new ExponentialBackOff();
        backOff.setInitialInterval(1_000L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(10_000L);
        backOff.setMaxAttempts(3);
        return new DefaultErrorHandler(recoverer, backOff);
    }
}
