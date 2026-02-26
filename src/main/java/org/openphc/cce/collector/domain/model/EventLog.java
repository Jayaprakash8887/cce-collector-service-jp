package org.openphc.cce.collector.domain.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.openphc.cce.collector.domain.model.enums.PublishStatus;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Normalized event log — the outbox for Kafka publishing.
 * Each row corresponds to exactly one Kafka message on cce.events.inbound.
 */
@Entity
@Table(name = "event_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "inbound_event_id", nullable = false)
    private UUID inboundEventId;

    @Column(name = "cloudevents_id", nullable = false)
    private String cloudeventsId;

    @Column(nullable = false)
    private String source;

    @Column(name = "source_event_id")
    private String sourceEventId;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false)
    private String type;

    @Column(name = "event_time", nullable = false)
    private OffsetDateTime eventTime;

    @Column(name = "received_at", nullable = false)
    private OffsetDateTime receivedAt;

    @Column(name = "correlation_id", nullable = false)
    private String correlationId;

    @Column(nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> data;

    @Column(name = "data_content_type", nullable = false)
    @Builder.Default
    private String dataContentType = "application/fhir+json";

    // CCE extension attributes
    @Column(name = "protocol_instance_id")
    private UUID protocolInstanceId;

    @Column(name = "protocol_definition_id")
    private UUID protocolDefinitionId;

    @Column(name = "action_id")
    private String actionId;

    @Column(name = "facility_id")
    private String facilityId;

    // Kafka publish tracking
    @Enumerated(EnumType.STRING)
    @Column(name = "publish_status", nullable = false)
    @Builder.Default
    private PublishStatus publishStatus = PublishStatus.PENDING;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    @Column(name = "kafka_topic")
    private String kafkaTopic;

    @Column(name = "kafka_partition")
    private Integer kafkaPartition;

    @Column(name = "kafka_offset")
    private Long kafkaOffset;
}
