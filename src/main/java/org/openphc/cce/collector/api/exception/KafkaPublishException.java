package org.openphc.cce.collector.api.exception;

import lombok.Getter;
import org.openphc.cce.collector.api.dto.CloudEventMessage;

/**
 * Exception thrown when Kafka publish fails.
 */
@Getter
public class KafkaPublishException extends RuntimeException {

    private final transient CloudEventMessage event;

    public KafkaPublishException(CloudEventMessage event, Throwable cause) {
        super("Failed to publish event " + event.getId() + " to Kafka", cause);
        this.event = event;
    }
}
