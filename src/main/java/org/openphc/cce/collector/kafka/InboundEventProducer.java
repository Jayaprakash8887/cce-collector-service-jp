package org.openphc.cce.collector.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.openphc.cce.collector.api.dto.CloudEventMessage;
import org.openphc.cce.collector.api.exception.KafkaPublishException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * Publishes validated CloudEventMessages to the cce.events.inbound Kafka topic.
 * Partition key is the subject (patient UPID) to guarantee per-patient ordering.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InboundEventProducer {

    private final KafkaTemplate<String, CloudEventMessage> kafkaTemplate;

    @Value("${cce.kafka.topics.inbound}")
    private String inboundTopic;

    /**
     * Publish a CloudEventMessage to Kafka.
     *
     * @param event the normalized CloudEventMessage
     * @return future with Kafka RecordMetadata on success
     */
    public CompletableFuture<RecordMetadata> publish(CloudEventMessage event) {
        String key = event.getSubject(); // Partition by patient_id (UPID)

        return kafkaTemplate.send(inboundTopic, key, event)
                .thenApply(result -> {
                    RecordMetadata metadata = result.getRecordMetadata();
                    log.info("Published event {} to {}[{}]@{}",
                            event.getId(), metadata.topic(), metadata.partition(), metadata.offset());
                    return metadata;
                })
                .exceptionally(ex -> {
                    log.error("Failed to publish event {} to Kafka", event.getId(), ex);
                    throw new KafkaPublishException(event, ex);
                });
    }
}
