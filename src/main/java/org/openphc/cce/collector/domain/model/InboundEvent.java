package org.openphc.cce.collector.domain.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.openphc.cce.collector.domain.model.enums.InboundStatus;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Raw inbound request log — every received event is persisted as-is before processing.
 * Used for audit trail and primary deduplication.
 */
@Entity
@Table(name = "inbound_event")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InboundEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "cloudevents_id", nullable = false)
    private String cloudeventsId;

    @Column(nullable = false)
    private String source;

    @Column(nullable = false)
    private String type;

    @Column(name = "spec_version", nullable = false)
    @Builder.Default
    private String specVersion = "1.0";

    private String subject;

    @Column(name = "event_time")
    private OffsetDateTime eventTime;

    @Column(name = "data_content_type")
    @Builder.Default
    private String dataContentType = "application/fhir+json";

    @Column(name = "facility_id")
    private String facilityId;

    @Column(name = "correlation_id")
    private String correlationId;

    @Column(name = "source_event_id")
    private String sourceEventId;

    @Column(name = "raw_payload", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> rawPayload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private InboundStatus status = InboundStatus.RECEIVED;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "received_at", nullable = false)
    @Builder.Default
    private OffsetDateTime receivedAt = OffsetDateTime.now();
}
