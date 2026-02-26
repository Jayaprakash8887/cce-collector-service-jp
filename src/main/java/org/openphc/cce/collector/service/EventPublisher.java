package org.openphc.cce.collector.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.openphc.cce.collector.api.dto.CloudEventMessage;
import org.openphc.cce.collector.domain.model.EventLog;
import org.openphc.cce.collector.domain.model.enums.PublishStatus;
import org.openphc.cce.collector.domain.repository.EventLogRepository;
import org.openphc.cce.collector.kafka.InboundEventProducer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Publishes event_log records to Kafka (outbox pattern).
 * Also handles retry of failed/pending publishes.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EventPublisher {

    private final InboundEventProducer inboundEventProducer;
    private final EventLogRepository eventLogRepository;

    @Value("${cce.collector.outbox.retry-max-age-minutes:60}")
    private int retryMaxAgeMinutes;

    /**
     * Publish a single event log record to Kafka.
     *
     * @param eventLog the event log to publish
     * @return the CloudEventMessage that was published
     */
    public CloudEventMessage publish(EventLog eventLog) {
        CloudEventMessage message = buildCloudEventMessage(eventLog);

        try {
            RecordMetadata metadata = inboundEventProducer.publish(message).join();

            // Update event log with Kafka metadata
            eventLog.setPublishStatus(PublishStatus.PUBLISHED);
            eventLog.setPublishedAt(OffsetDateTime.now(ZoneOffset.UTC));
            eventLog.setKafkaTopic(metadata.topic());
            eventLog.setKafkaPartition(metadata.partition());
            eventLog.setKafkaOffset(metadata.offset());
            eventLogRepository.save(eventLog);

            return message;
        } catch (Exception e) {
            log.error("Kafka publish failed for event_log id={}: {}", eventLog.getId(), e.getMessage());
            eventLog.setPublishStatus(PublishStatus.FAILED);
            eventLogRepository.save(eventLog);
            throw e;
        }
    }

    /**
     * Scheduled retry of pending/failed event log records.
     */
    @Scheduled(fixedDelayString = "${cce.collector.outbox.retry-interval-seconds:30}000")
    @Transactional
    public void retryPendingPublishes() {
        OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(30);
        OffsetDateTime maxAge = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(retryMaxAgeMinutes);

        List<EventLog> pending = eventLogRepository
                .findByPublishStatusAndReceivedAtBefore(PublishStatus.PENDING, cutoff);

        List<EventLog> failed = eventLogRepository
                .findByPublishStatusAndReceivedAtBefore(PublishStatus.FAILED, cutoff);

        pending.addAll(failed);

        for (EventLog eventLog : pending) {
            if (eventLog.getReceivedAt().isBefore(maxAge)) {
                log.warn("Event log id={} exceeded max retry age, skipping", eventLog.getId());
                continue;
            }
            try {
                publish(eventLog);
                log.info("Successfully retried publish for event_log id={}", eventLog.getId());
            } catch (Exception e) {
                log.warn("Retry publish failed for event_log id={}: {}", eventLog.getId(), e.getMessage());
            }
        }
    }

    /**
     * Build a CloudEventMessage from an EventLog record.
     */
    private CloudEventMessage buildCloudEventMessage(EventLog eventLog) {
        return CloudEventMessage.builder()
                .id(eventLog.getCloudeventsId())
                .source(eventLog.getSource())
                .type(eventLog.getType())
                .specVersion("1.0")
                .subject(eventLog.getSubject())
                .time(eventLog.getEventTime())
                .dataContentType(eventLog.getDataContentType())
                .correlationId(eventLog.getCorrelationId())
                .sourceEventId(eventLog.getSourceEventId())
                .protocolInstanceId(eventLog.getProtocolInstanceId() != null
                        ? eventLog.getProtocolInstanceId().toString() : null)
                .protocolDefinitionId(eventLog.getProtocolDefinitionId() != null
                        ? eventLog.getProtocolDefinitionId().toString() : null)
                .actionId(eventLog.getActionId())
                .facilityId(eventLog.getFacilityId())
                .data(eventLog.getData())
                .build();
    }
}
