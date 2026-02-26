-- Raw inbound request log — every received event is persisted as-is before processing
CREATE TABLE inbound_event (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cloudevents_id   VARCHAR NOT NULL,
    source           VARCHAR NOT NULL,
    type             VARCHAR NOT NULL,
    spec_version     VARCHAR NOT NULL DEFAULT '1.0',
    subject          VARCHAR,
    event_time       TIMESTAMPTZ,
    data_content_type VARCHAR DEFAULT 'application/fhir+json',
    facility_id      VARCHAR,
    correlation_id   VARCHAR,
    source_event_id  VARCHAR,
    raw_payload      JSONB NOT NULL,
    status           VARCHAR NOT NULL DEFAULT 'RECEIVED'
                     CHECK (status IN ('RECEIVED', 'ACCEPTED', 'REJECTED', 'DUPLICATE')),
    rejection_reason VARCHAR,
    received_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_inbound_event_id_source UNIQUE (cloudevents_id, source)
);

CREATE INDEX idx_inbound_event_subject     ON inbound_event (subject);
CREATE INDEX idx_inbound_event_source      ON inbound_event (source);
CREATE INDEX idx_inbound_event_status      ON inbound_event (status);
CREATE INDEX idx_inbound_event_received    ON inbound_event (received_at);
