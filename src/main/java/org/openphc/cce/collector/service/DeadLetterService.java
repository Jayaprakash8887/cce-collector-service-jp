package org.openphc.cce.collector.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openphc.cce.collector.domain.model.DeadLetterEvent;
import org.openphc.cce.collector.domain.model.enums.FailureStage;
import org.openphc.cce.collector.domain.model.enums.RejectionReason;
import org.openphc.cce.collector.domain.repository.DeadLetterEventRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages dead-letter events — persist and query rejected/failed events.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeadLetterService {

    private final DeadLetterEventRepository deadLetterEventRepository;

    /**
     * Persist a dead-letter event for a validation failure.
     */
    @Transactional
    public DeadLetterEvent persistValidationFailure(
            UUID inboundEventId,
            String cloudeventsId,
            String source,
            String type,
            String subject,
            Map<String, Object> rawPayload,
            RejectionReason reason,
            String errorDetails,
            String correlationId,
            String facilityId) {

        DeadLetterEvent dle = DeadLetterEvent.builder()
                .inboundEventId(inboundEventId)
                .cloudeventsId(cloudeventsId)
                .source(source)
                .type(type)
                .subject(subject)
                .rawPayload(rawPayload)
                .rejectionReason(reason)
                .failureStage(FailureStage.VALIDATION)
                .errorDetails(errorDetails)
                .correlationId(correlationId)
                .facilityId(facilityId)
                .build();

        DeadLetterEvent saved = deadLetterEventRepository.save(dle);
        log.error("Dead-lettered event: id={}, source={}, reason={}", cloudeventsId, source, reason);
        return saved;
    }

    /**
     * Persist a dead-letter event for a Kafka publish failure.
     */
    @Transactional
    public DeadLetterEvent persistKafkaFailure(
            UUID inboundEventId,
            String cloudeventsId,
            String source,
            String type,
            String subject,
            Map<String, Object> rawPayload,
            String errorDetails,
            String correlationId,
            String facilityId) {

        DeadLetterEvent dle = DeadLetterEvent.builder()
                .inboundEventId(inboundEventId)
                .cloudeventsId(cloudeventsId)
                .source(source)
                .type(type)
                .subject(subject)
                .rawPayload(rawPayload)
                .rejectionReason(RejectionReason.KAFKA_PUBLISH_FAILURE)
                .failureStage(FailureStage.KAFKA_PUBLISH)
                .errorDetails(errorDetails)
                .correlationId(correlationId)
                .facilityId(facilityId)
                .build();

        DeadLetterEvent saved = deadLetterEventRepository.save(dle);
        log.error("Dead-lettered event (Kafka failure): id={}, source={}", cloudeventsId, source);
        return saved;
    }

    /**
     * List unresolved dead-letter events.
     */
    public Page<DeadLetterEvent> listUnresolved(Pageable pageable) {
        return deadLetterEventRepository.findByResolvedFalse(pageable);
    }

    /**
     * Get a specific dead-letter event.
     */
    public Optional<DeadLetterEvent> findById(UUID id) {
        return deadLetterEventRepository.findById(id);
    }

    /**
     * List dead-letter events by rejection reason.
     */
    public Page<DeadLetterEvent> findByReason(RejectionReason reason, Pageable pageable) {
        return deadLetterEventRepository.findByRejectionReason(reason, pageable);
    }

    /**
     * List dead-letter events by source.
     */
    public Page<DeadLetterEvent> findBySource(String source, Pageable pageable) {
        return deadLetterEventRepository.findBySource(source, pageable);
    }

    /**
     * Mark a dead-letter event as resolved.
     */
    @Transactional
    public Optional<DeadLetterEvent> resolve(UUID id) {
        return deadLetterEventRepository.findById(id).map(dle -> {
            dle.setResolved(true);
            dle.setResolvedAt(OffsetDateTime.now());
            return deadLetterEventRepository.save(dle);
        });
    }

    /**
     * Count active (unresolved) dead-letter events.
     */
    public long countUnresolved() {
        return deadLetterEventRepository.countByResolvedFalse();
    }
}
