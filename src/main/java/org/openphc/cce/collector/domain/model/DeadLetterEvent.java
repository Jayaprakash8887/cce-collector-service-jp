package org.openphc.cce.collector.domain.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.openphc.cce.collector.domain.model.enums.FailureStage;
import org.openphc.cce.collector.domain.model.enums.RejectionReason;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Dead-letter store for events that failed validation or processing.
 */
@Entity
@Table(name = "dead_letter_event")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeadLetterEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "inbound_event_id")
    private UUID inboundEventId;

    @Column(name = "cloudevents_id")
    private String cloudeventsId;

    private String source;

    private String type;

    private String subject;

    @Column(name = "raw_payload", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> rawPayload;

    @Enumerated(EnumType.STRING)
    @Column(name = "rejection_reason", nullable = false)
    private RejectionReason rejectionReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_stage", nullable = false)
    private FailureStage failureStage;

    @Column(name = "error_details", columnDefinition = "TEXT")
    private String errorDetails;

    @Column(name = "correlation_id")
    private String correlationId;

    @Column(name = "facility_id")
    private String facilityId;

    @Column(name = "received_at", nullable = false)
    @Builder.Default
    private OffsetDateTime receivedAt = OffsetDateTime.now();

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private int retryCount = 0;

    @Column(name = "next_retry_at")
    private OffsetDateTime nextRetryAt;

    @Column(nullable = false)
    @Builder.Default
    private boolean resolved = false;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;
}
