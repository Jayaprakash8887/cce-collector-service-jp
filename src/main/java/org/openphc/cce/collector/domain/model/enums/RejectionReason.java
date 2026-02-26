package org.openphc.cce.collector.domain.model.enums;

/**
 * Reason an event was rejected and dead-lettered.
 */
public enum RejectionReason {
    INVALID_ENVELOPE,
    INVALID_FHIR,
    DUPLICATE,
    MISSING_SUBJECT,
    PAYLOAD_TOO_LARGE,
    DESERIALIZATION_ERROR,
    KAFKA_PUBLISH_FAILURE
}
