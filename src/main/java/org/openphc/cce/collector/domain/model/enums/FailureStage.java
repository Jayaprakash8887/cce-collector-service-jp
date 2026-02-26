package org.openphc.cce.collector.domain.model.enums;

/**
 * Stage at which event processing failed (for dead-letter classification).
 */
public enum FailureStage {
    VALIDATION,
    PROCESSING,
    KAFKA_PUBLISH
}
