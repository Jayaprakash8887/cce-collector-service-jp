package org.openphc.cce.collector.domain.model.enums;

/**
 * Status of an inbound event through the ingestion pipeline.
 */
public enum InboundStatus {
    RECEIVED,
    ACCEPTED,
    REJECTED,
    DUPLICATE
}
