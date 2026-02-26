-- Dead-letter store for events that failed validation or processing
CREATE TABLE dead_letter_event (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    inbound_event_id UUID,
    cloudevents_id   VARCHAR,
    source           VARCHAR,
    type             VARCHAR,
    subject          VARCHAR,
    raw_payload      JSONB NOT NULL,
    rejection_reason VARCHAR NOT NULL
                     CHECK (rejection_reason IN (
                         'INVALID_ENVELOPE', 'INVALID_FHIR', 'DUPLICATE',
                         'MISSING_SUBJECT', 'UNKNOWN_SOURCE', 'PAYLOAD_TOO_LARGE',
                         'DESERIALIZATION_ERROR', 'KAFKA_PUBLISH_FAILURE'
                     )),
    failure_stage    VARCHAR NOT NULL
                     CHECK (failure_stage IN ('VALIDATION', 'PROCESSING', 'KAFKA_PUBLISH')),
    error_details    TEXT,
    correlation_id   VARCHAR,
    facility_id      VARCHAR,
    received_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    retry_count      INTEGER NOT NULL DEFAULT 0,
    next_retry_at    TIMESTAMPTZ,
    resolved         BOOLEAN NOT NULL DEFAULT false,
    resolved_at      TIMESTAMPTZ
);

CREATE INDEX idx_dead_letter_reason     ON dead_letter_event (rejection_reason);
CREATE INDEX idx_dead_letter_source     ON dead_letter_event (source);
CREATE INDEX idx_dead_letter_received   ON dead_letter_event (received_at);
CREATE INDEX idx_dead_letter_unresolved ON dead_letter_event (next_retry_at) WHERE resolved = false;
