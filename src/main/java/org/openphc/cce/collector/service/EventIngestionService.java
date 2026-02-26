package org.openphc.cce.collector.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.openphc.cce.collector.api.dto.*;
import org.openphc.cce.collector.api.exception.CloudEventValidationException;
import org.openphc.cce.collector.api.exception.FhirValidationException;
import org.openphc.cce.collector.domain.model.EventLog;
import org.openphc.cce.collector.domain.model.InboundEvent;
import org.openphc.cce.collector.domain.model.enums.InboundStatus;
import org.openphc.cce.collector.domain.model.enums.PublishStatus;
import org.openphc.cce.collector.domain.model.enums.RejectionReason;
import org.openphc.cce.collector.domain.repository.EventLogRepository;
import org.openphc.cce.collector.domain.repository.InboundEventRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Main orchestrator: validate → normalize → persist → deduplicate → publish.
 * Implements the full event ingestion flow as specified in the design.
 */
@Service
@Slf4j
public class EventIngestionService {

    private final CloudEventValidator cloudEventValidator;
    private final FhirPayloadValidator fhirPayloadValidator;
    private final EventNormalizer eventNormalizer;
    private final DeduplicationService deduplicationService;
    private final EventPublisher eventPublisher;
    private final DeadLetterService deadLetterService;
    private final InboundEventRepository inboundEventRepository;
    private final EventLogRepository eventLogRepository;
    private final String inboundTopic;

    // Metrics
    private final Timer ingestionTimer;
    private final MeterRegistry meterRegistry;

    public EventIngestionService(
            CloudEventValidator cloudEventValidator,
            FhirPayloadValidator fhirPayloadValidator,
            EventNormalizer eventNormalizer,
            DeduplicationService deduplicationService,
            EventPublisher eventPublisher,
            DeadLetterService deadLetterService,
            InboundEventRepository inboundEventRepository,
            EventLogRepository eventLogRepository,
            @Value("${cce.kafka.topics.inbound}") String inboundTopic,
            MeterRegistry meterRegistry) {
        this.cloudEventValidator = cloudEventValidator;
        this.fhirPayloadValidator = fhirPayloadValidator;
        this.eventNormalizer = eventNormalizer;
        this.deduplicationService = deduplicationService;
        this.eventPublisher = eventPublisher;
        this.deadLetterService = deadLetterService;
        this.inboundEventRepository = inboundEventRepository;
        this.eventLogRepository = eventLogRepository;
        this.inboundTopic = inboundTopic;
        this.meterRegistry = meterRegistry;
        this.ingestionTimer = Timer.builder("cce.collector.ingestion.duration")
                .description("End-to-end event ingestion latency")
                .register(meterRegistry);
    }

    /**
     * Ingest a single clinical event — the primary entry point.
     */
    public EventIngestionResponse ingest(EventIngestionRequest request) {
        return ingestionTimer.record(() -> doIngest(request));
    }

    /**
     * Core ingestion logic for a single event.
     */
    private EventIngestionResponse doIngest(EventIngestionRequest request) {
        OffsetDateTime receivedAt = OffsetDateTime.now(ZoneOffset.UTC);

        // Step 2: CloudEvents envelope validation
        try {
            cloudEventValidator.validate(request);
        } catch (CloudEventValidationException e) {
            recordMetric(request.getSource(), "rejected");
            deadLetterService.persistValidationFailure(
                    null, request.getId(), request.getSource(), request.getType(),
                    request.getSubject(), request.toRawPayload(),
                    RejectionReason.INVALID_ENVELOPE, e.getMessage(),
                    request.getCorrelationid(), request.getFacilityid());
            throw e;
        }

        // Step 3: Deduplication check (before DB persist to avoid constraint violations)
        if (deduplicationService.isDuplicate(request.getSource(), request.getId())) {
            recordMetric(request.getSource(), "duplicate");
            return buildDuplicateResponse(request, receivedAt);
        }

        // Step 4: Persist raw inbound event
        InboundEvent inboundEvent = persistInboundEvent(request, receivedAt);

        // Step 5: Normalization
        String normalizedType = eventNormalizer.normalizeEventType(request.getType());
        String correlationId = eventNormalizer.ensureCorrelationId(request.getCorrelationid());
        OffsetDateTime eventTime = eventNormalizer.ensureEventTime(request.getTime());

        // Step 6: FHIR payload validation
        try {
            fhirPayloadValidator.validate(request);
        } catch (FhirValidationException e) {
            inboundEvent.setStatus(InboundStatus.REJECTED);
            inboundEvent.setRejectionReason(RejectionReason.INVALID_FHIR.name());
            inboundEventRepository.save(inboundEvent);
            recordMetric(request.getSource(), "rejected");
            deadLetterService.persistValidationFailure(
                    inboundEvent.getId(), request.getId(), request.getSource(), request.getType(),
                    request.getSubject(), request.toRawPayload(),
                    RejectionReason.INVALID_FHIR, String.join("; ", e.getErrors()),
                    correlationId, request.getFacilityid());
            throw e;
        }

        // Step 7: Update inbound event status to accepted
        inboundEvent.setStatus(InboundStatus.ACCEPTED);
        inboundEventRepository.save(inboundEvent);

        // Step 8: Persist normalized event to event_log (outbox)
        EventLog eventLog = persistEventLog(request, inboundEvent, normalizedType,
                correlationId, eventTime, receivedAt);

        // Step 9: Publish to Kafka
        try {
            eventPublisher.publish(eventLog);
        } catch (Exception e) {
            log.error("Kafka publish failed for event id={}: {}", request.getId(), e.getMessage());
            deadLetterService.persistKafkaFailure(
                    inboundEvent.getId(), request.getId(), request.getSource(),
                    normalizedType, request.getSubject(), request.toRawPayload(),
                    e.getMessage(), correlationId, request.getFacilityid());
            // Event stays in event_log with publish_status=PENDING/FAILED for retry
        }

        recordMetric(request.getSource(), "accepted");

        // Step 10: Return HTTP response
        return EventIngestionResponse.builder()
                .eventId(request.getId())
                .status("accepted")
                .correlationId(correlationId)
                .publishedTopic(inboundTopic)
                .receivedAt(receivedAt)
                .build();
    }

    /**
     * Persist the raw inbound event (audit trail, first write).
     */
    @Transactional
    protected InboundEvent persistInboundEvent(EventIngestionRequest request, OffsetDateTime receivedAt) {
        InboundEvent inboundEvent = InboundEvent.builder()
                .cloudeventsId(request.getId())
                .source(request.getSource())
                .type(request.getType())
                .specVersion(request.getSpecversion())
                .subject(request.getSubject())
                .eventTime(request.getTime() != null && !request.getTime().isBlank()
                        ? OffsetDateTime.parse(request.getTime()) : null)
                .dataContentType(request.getDatacontenttype())
                .facilityId(request.getFacilityid())
                .correlationId(request.getCorrelationid())
                .sourceEventId(request.getSourceeventid())
                .rawPayload(request.toRawPayload())
                .status(InboundStatus.RECEIVED)
                .receivedAt(receivedAt)
                .build();

        return inboundEventRepository.save(inboundEvent);
    }

    /**
     * Persist the normalized event to event_log (outbox table).
     */
    @Transactional
    protected EventLog persistEventLog(EventIngestionRequest request, InboundEvent inboundEvent,
                                        String normalizedType, String correlationId,
                                        OffsetDateTime eventTime, OffsetDateTime receivedAt) {
        EventLog eventLog = EventLog.builder()
                .inboundEventId(inboundEvent.getId())
                .cloudeventsId(request.getId())
                .source(request.getSource())
                .sourceEventId(request.getSourceeventid())
                .subject(request.getSubject())
                .type(normalizedType)
                .eventTime(eventTime)
                .receivedAt(receivedAt)
                .correlationId(correlationId)
                .data(request.getData())
                .dataContentType(request.getDatacontenttype() != null
                        ? request.getDatacontenttype() : "application/fhir+json")
                .protocolInstanceId(parseUuid(request.getProtocolinstanceid()))
                .protocolDefinitionId(parseUuid(request.getProtocoldefinitionid()))
                .actionId(request.getActionid())
                .facilityId(request.getFacilityid())
                .publishStatus(PublishStatus.PENDING)
                .build();

        return eventLogRepository.save(eventLog);
    }

    private EventIngestionResponse buildDuplicateResponse(EventIngestionRequest request, OffsetDateTime receivedAt) {
        return EventIngestionResponse.builder()
                .eventId(request.getId())
                .status("duplicate")
                .correlationId(request.getCorrelationid())
                .receivedAt(receivedAt)
                .build();
    }

    private UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid UUID format: {}", value);
            return null;
        }
    }

    private void recordMetric(String source, String status) {
        Counter.builder("cce.collector.events.received")
                .tag("source", source != null ? source : "unknown")
                .tag("status", status)
                .register(meterRegistry)
                .increment();
    }
}
