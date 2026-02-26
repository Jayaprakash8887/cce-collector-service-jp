-- Normalized event log — the outbox for Kafka publishing
-- Each row corresponds to exactly one Kafka message on cce.events.inbound
CREATE TABLE event_log (
    id                       UUID NOT NULL DEFAULT gen_random_uuid(),
    inbound_event_id         UUID NOT NULL,
    cloudevents_id           VARCHAR NOT NULL,
    source                   VARCHAR NOT NULL,
    source_event_id          VARCHAR,
    subject                  VARCHAR NOT NULL,
    type                     VARCHAR NOT NULL,
    event_time               TIMESTAMPTZ NOT NULL,
    received_at              TIMESTAMPTZ NOT NULL,
    correlation_id           VARCHAR NOT NULL,
    data                     JSONB NOT NULL,
    data_content_type        VARCHAR NOT NULL DEFAULT 'application/fhir+json',
    protocol_instance_id     UUID,
    protocol_definition_id   UUID,
    action_id                VARCHAR,
    facility_id              VARCHAR,
    publish_status           VARCHAR NOT NULL DEFAULT 'PENDING'
                             CHECK (publish_status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    published_at             TIMESTAMPTZ,
    kafka_topic              VARCHAR,
    kafka_partition          INT,
    kafka_offset             BIGINT,
    UNIQUE (cloudevents_id, source)
) PARTITION BY RANGE (received_at);

-- Secondary idempotency index (source + source_event_id)
CREATE UNIQUE INDEX idx_event_log_source_sourceeventid
    ON event_log (source, source_event_id) WHERE source_event_id IS NOT NULL;

CREATE INDEX idx_event_log_subject      ON event_log (subject);
CREATE INDEX idx_event_log_type         ON event_log (type);
CREATE INDEX idx_event_log_correlation  ON event_log (correlation_id);
CREATE INDEX idx_event_log_facility     ON event_log (facility_id) WHERE facility_id IS NOT NULL;
CREATE INDEX idx_event_log_publish      ON event_log (publish_status) WHERE publish_status != 'PUBLISHED';

-- Monthly partitions
CREATE TABLE event_log_2026_01 PARTITION OF event_log
    FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');
CREATE TABLE event_log_2026_02 PARTITION OF event_log
    FOR VALUES FROM ('2026-02-01') TO ('2026-03-01');
CREATE TABLE event_log_2026_03 PARTITION OF event_log
    FOR VALUES FROM ('2026-03-01') TO ('2026-04-01');
CREATE TABLE event_log_2026_04 PARTITION OF event_log
    FOR VALUES FROM ('2026-04-01') TO ('2026-05-01');
CREATE TABLE event_log_2026_05 PARTITION OF event_log
    FOR VALUES FROM ('2026-05-01') TO ('2026-06-01');
CREATE TABLE event_log_2026_06 PARTITION OF event_log
    FOR VALUES FROM ('2026-06-01') TO ('2026-07-01');
