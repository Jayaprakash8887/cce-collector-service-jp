package org.openphc.cce.collector.domain.model.enums;

/**
 * Kafka publish status for event_log records (outbox pattern).
 */
public enum PublishStatus {
    PENDING,
    PUBLISHED,
    FAILED
}
