# CCE Collector Service — AI Agent Instructions (Standalone Repo)

## Project Overview

This is the **Collector Service** — the event ingestion gateway of the Care Coordination Engine (CCE). It is built and deployed as a standalone Spring Boot application in its own repository. It receives clinical events from external EHR/RHIE systems (via openHIM mediators or direct integrations), validates and normalizes them into CloudEvents v1.0 envelopes with FHIR R4 payloads, and publishes them to Kafka for downstream processing by the Compliance Service.

**Upstream design documents (maintained in the `cce-compliance-sub_system` repo):**
- `CCE Solution Design v0.3 Draft.pdf` — full architecture, event model, matching logic, APIs
- `CCE_Technology_Stack_Proposal.md` — tech choices, DB schema, infrastructure

---

## What This Service Does

The Collector Service is responsible for:
1. **Receiving clinical events** via REST API from external systems (openHIM RHIE mediators, EMR direct push, CHW apps)
2. **Validating CloudEvents envelope** — mandatory fields (`specversion`, `id`, `source`, `type`, `subject`), extensions, structure
3. **Validating FHIR R4 payloads** — parsing and structural validation of `data` when `datacontenttype` = `application/fhir+json`
4. **Normalizing event metadata** — standardizing event types, extracting patient identifiers, generating missing fields (correlation IDs)
5. **Deduplicating inbound events** — rejecting or marking duplicate submissions using `(id, source)` compound key
6. **Publishing validated events** to Kafka topic `cce.events.inbound` with `subject` (patient_id) as partition key
7. **Dead-lettering invalid events** — persisting rejected events with failure reasons for audit and retry
8. **Serving health/readiness endpoints** for orchestration platforms (Kubernetes, Docker)

**What this service does NOT do:**
- Protocol matching, step completion, or compliance tracking (Compliance Service)
- Time-based state transitions (Scheduler Service)
- OAuth token management, routing, or rate limiting (Gateway Service)
- Analytics or reporting (Analytics Service)
- Intelligence event delivery or action execution (Intelligence Subsystem — out of scope)

---

## Position in the CCE Platform

```
┌─────────────────────────────────────────────────────────────┐
│                    External Systems                          │
│  eBUZIMA EMR  │  SmartCare  │  CHW App  │  Lab Systems      │
└──────┬────────┴──────┬──────┴─────┬─────┴──────┬────────────┘
       │               │            │            │
       ▼               ▼            ▼            ▼
┌─────────────────────────────────────────────────────────────┐
│               openHIM / RHIE Mediator Layer                  │
│         (routes, transforms, adds correlation IDs)           │
└──────────────────────────┬──────────────────────────────────┘
                           │  HTTP POST (CloudEvents)
                           ▼
┌─────────────────────────────────────────────────────────────┐
│              ★ CCE Collector Service ★                        │
│    Validate → Normalize → Deduplicate → Publish to Kafka     │
└──────────────────────────┬──────────────────────────────────┘
                           │  Kafka: cce.events.inbound
                           ▼
┌─────────────────────────────────────────────────────────────┐
│              CCE Compliance Service                           │
│    Match → Enroll → Complete Steps → Detect Deviations       │
└─────────────────────────────────────────────────────────────┘
```

The Collector Service is the **single point of entry** for all clinical events into the CCE platform. No external system publishes directly to Kafka — all events flow through the Collector.

---

## Technology Stack

| Concern | Technology | Version |
|---------|------------|---------|
| Language | Java | 21 (LTS) |
| Framework | Spring Boot | 3.4.x |
| Build tool | Maven | 3.9+ |
| Database | PostgreSQL | 16+ (latest stable) |
| Message broker | Apache Kafka | 3.7+ (KRaft mode) |
| Cache | Redis | 7.x |
| FHIR library | HAPI FHIR | 7.4.0 |
| DB access | Spring Data JPA + Hibernate | (Spring Boot managed) |
| DB migration | Flyway | (Spring Boot managed) |
| Connection pool | HikariCP | (Spring Boot default) |
| Observability | Micrometer + OpenTelemetry | (Spring Boot managed) |
| Testing | JUnit 5, Testcontainers, MockMvc | |

### Key Maven Dependencies

```xml
<!-- HAPI FHIR (validation of inbound FHIR payloads) -->
<dependency>
    <groupId>ca.uhn.hapi.fhir</groupId>
    <artifactId>hapi-fhir-structures-r4</artifactId>
    <version>7.4.0</version>
</dependency>
<dependency>
    <groupId>ca.uhn.hapi.fhir</groupId>
    <artifactId>hapi-fhir-validation</artifactId>
    <version>7.4.0</version>
</dependency>
<dependency>
    <groupId>ca.uhn.hapi.fhir</groupId>
    <artifactId>hapi-fhir-validation-resources-r4</artifactId>
    <version>7.4.0</version>
</dependency>

<!-- Spring Boot starters -->
<!-- spring-boot-starter-web, spring-boot-starter-data-jpa, spring-kafka,
     spring-boot-starter-data-redis, spring-boot-starter-actuator,
     spring-boot-starter-validation -->
```

---

## Recommended Project Structure

```
cce-collector-service/
├── pom.xml
├── src/
│   ├── main/
│   │   ├── java/org/openphc/cce/collector/
│   │   │   ├── CollectorServiceApplication.java
│   │   │   ├── config/
│   │   │   │   ├── KafkaProducerConfig.java
│   │   │   │   ├── FhirConfig.java              # FhirContext.forR4() singleton bean
│   │   │   │   ├── RedisConfig.java
│   │   │   │   ├── JpaConfig.java
│   │   │   │   ├── SecurityConfig.java           # mTLS / API key validation
│   │   │   │   └── WebConfig.java                # CORS, request logging
│   │   │   ├── domain/
│   │   │   │   ├── model/
│   │   │   │   │   ├── InboundEvent.java         # Raw inbound request record (audit/dedup)
│   │   │   │   │   ├── EventLog.java             # Normalized event published to Kafka (outbox)
│   │   │   │   │   ├── DeadLetterEvent.java      # Rejected event record
│   │   │   │   │   ├── SourceRegistration.java   # Registered event sources
│   │   │   │   │   └── enums/
│   │   │   │   │       ├── InboundStatus.java     # received, accepted, rejected, duplicate
│   │   │   │   │       ├── PublishStatus.java     # pending, published, failed
│   │   │   │   │       └── RejectionReason.java   # invalid_envelope, invalid_fhir, duplicate,
│   │   │   │   │                                  # missing_subject, unknown_source, payload_too_large
│   │   │   │   └── repository/
│   │   │   │       ├── InboundEventRepository.java
│   │   │   │       ├── EventLogRepository.java
│   │   │   │       ├── DeadLetterEventRepository.java
│   │   │   │       └── SourceRegistrationRepository.java
│   │   │   ├── service/
│   │   │   │   ├── EventIngestionService.java    # Main orchestrator: validate → normalize → persist → publish
│   │   │   │   ├── CloudEventValidator.java      # CloudEvents v1.0 envelope validation
│   │   │   │   ├── FhirPayloadValidator.java     # FHIR R4 structural validation via HAPI
│   │   │   │   ├── EventNormalizer.java           # Standardize types, fill defaults, extract metadata
│   │   │   │   ├── DeduplicationService.java      # (id, source) compound dedup via Redis fast-path + DB
│   │   │   │   ├── EventPublisher.java            # Publishes event_log records to Kafka (outbox)
│   │   │   │   ├── SourceRegistrationService.java # Manage allowed event sources
│   │   │   │   └── DeadLetterService.java         # Persist and optionally publish dead letters
│   │   │   ├── kafka/
│   │   │   │   └── InboundEventProducer.java      # Publishes to cce.events.inbound
│   │   │   ├── api/
│   │   │   │   ├── controller/
│   │   │   │   │   ├── EventIngestionController.java  # POST /v1/events — main entry point
│   │   │   │   │   ├── DeadLetterController.java      # GET /v1/dead-letters — view rejected
│   │   │   │   │   └── SourceController.java          # CRUD for registered sources
│   │   │   │   ├── dto/
│   │   │   │   │   ├── ApiResponse.java               # { "data": ... } envelope
│   │   │   │   │   ├── ApiError.java                  # { "error": { "code", "message" } }
│   │   │   │   │   ├── EventIngestionRequest.java     # CloudEvents envelope (inbound DTO)
│   │   │   │   │   ├── EventIngestionResponse.java    # Accepted/rejected receipt
│   │   │   │   │   └── DeadLetterDto.java
│   │   │   │   └── exception/
│   │   │   │       └── GlobalExceptionHandler.java
│   │   │   └── fhir/
│   │   │       ├── FhirResourceParser.java        # HAPI FHIR parse + type detection
│   │   │       └── FhirResourceValidator.java     # Profile validation (optional, extensible)
│   │   └── resources/
│   │       ├── application.yml
│   │       ├── application-local.yml
│   │       ├── application-staging.yml
│   │       ├── application-production.yml
│   │       └── db/migration/
│   │           ├── V1__create_inbound_event.sql
│   │           ├── V2__create_event_log.sql
│   │           ├── V3__create_dead_letter_event.sql
│   │           └── V4__create_source_registration.sql
│   └── test/
│       └── java/org/openphc/cce/collector/
│           ├── service/
│           ├── api/
│           ├── kafka/
│           ├── fhir/
│           └── integration/
├── Dockerfile
├── docker-compose.yml                               # local dev: PostgreSQL, Kafka, Redis
├── .github/
│   └── copilot-instructions.md                      # this file
└── README.md
```

---

## Database Schema

The Collector Service owns the following tables. Use Flyway for all schema migrations.

### Table 1: `inbound_event` — Raw Request Log

Stores every HTTP request as received, before any normalization. Used for audit trail and primary deduplication.

```sql
-- Raw inbound request log — every received event is persisted as-is before processing
CREATE TABLE inbound_event (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cloudevents_id   VARCHAR NOT NULL,
    source           VARCHAR NOT NULL,
    type             VARCHAR NOT NULL,               -- raw type as received (before normalization)
    spec_version     VARCHAR NOT NULL DEFAULT '1.0',
    subject          VARCHAR,                        -- patient_id (UPID)
    event_time       TIMESTAMPTZ,                    -- CloudEvents time attribute
    data_content_type VARCHAR DEFAULT 'application/fhir+json',
    facility_id      VARCHAR,
    correlation_id   VARCHAR,                        -- may be null if not provided by source
    source_event_id  VARCHAR,
    raw_payload      JSONB NOT NULL,                 -- full original request body as received
    status           VARCHAR NOT NULL DEFAULT 'received'
                     CHECK (status IN ('received', 'accepted', 'rejected', 'duplicate')),
    rejection_reason VARCHAR,
    received_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_inbound_event_id_source UNIQUE (cloudevents_id, source)
);

CREATE INDEX idx_inbound_event_subject     ON inbound_event (subject);
CREATE INDEX idx_inbound_event_source      ON inbound_event (source);
CREATE INDEX idx_inbound_event_status      ON inbound_event (status);
CREATE INDEX idx_inbound_event_received    ON inbound_event (received_at);
```

### Table 2: `event_log` — Normalized Events (Outbox)

Stores validated, normalized events that are ready for (or have been) published to Kafka. This is the **outbox table** — the authoritative record of what the Compliance Service receives. Monthly-partitioned for scalable querying.

```sql
-- Normalized event log — the outbox for Kafka publishing
-- Each row corresponds to exactly one Kafka message on cce.events.inbound
CREATE TABLE event_log (
    id                       UUID NOT NULL DEFAULT gen_random_uuid(),
    inbound_event_id         UUID NOT NULL,           -- FK to inbound_event
    cloudevents_id           VARCHAR NOT NULL,
    source                   VARCHAR NOT NULL,
    source_event_id          VARCHAR,                 -- secondary idempotency key (sourceeventid)
    subject                  VARCHAR NOT NULL,        -- patient_id (UPID) — Kafka partition key
    type                     VARCHAR NOT NULL,        -- normalized type (org.openphc.cce.*)
    event_time               TIMESTAMPTZ NOT NULL,    -- filled with received_at if absent from source
    received_at              TIMESTAMPTZ NOT NULL,
    correlation_id           VARCHAR NOT NULL,        -- generated by Collector if absent from source
    data                     JSONB NOT NULL,          -- FHIR R4 resource or application/json payload
    data_content_type        VARCHAR NOT NULL DEFAULT 'application/fhir+json',
    -- CCE extension attributes (optional routing hints from source)
    protocol_instance_id     UUID,                    -- explicit routing: protocolinstanceid
    protocol_definition_id   UUID,                    -- explicit routing: protocoldefinitionid
    action_id                VARCHAR,                 -- explicit matching: actionid
    facility_id              VARCHAR,                 -- facilityid
    -- Kafka publish tracking
    publish_status           VARCHAR NOT NULL DEFAULT 'pending'
                             CHECK (publish_status IN ('pending', 'published', 'failed')),
    published_at             TIMESTAMPTZ,             -- when successfully sent to Kafka
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
CREATE INDEX idx_event_log_publish      ON event_log (publish_status) WHERE publish_status != 'published';

-- Monthly partitions (create in advance or use pg_partman for auto-creation)
CREATE TABLE event_log_2026_01 PARTITION OF event_log
    FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');
CREATE TABLE event_log_2026_02 PARTITION OF event_log
    FOR VALUES FROM ('2026-02-01') TO ('2026-03-01');
CREATE TABLE event_log_2026_03 PARTITION OF event_log
    FOR VALUES FROM ('2026-03-01') TO ('2026-04-01');
-- ... create partitions quarterly in advance
```

### Table 3: `dead_letter_event` — Rejected/Failed Events

Stores events that failed validation or Kafka publishing, with retry support.

```sql
-- Dead-letter store for events that failed validation or processing
CREATE TABLE dead_letter_event (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    inbound_event_id UUID,                             -- FK to inbound_event (null if deserialization failed)
    cloudevents_id   VARCHAR,
    source           VARCHAR,
    type             VARCHAR,
    subject          VARCHAR,
    raw_payload      JSONB NOT NULL,                   -- full original request body
    rejection_reason VARCHAR NOT NULL
                     CHECK (rejection_reason IN (
                         'invalid_envelope', 'invalid_fhir', 'duplicate',
                         'missing_subject', 'unknown_source', 'payload_too_large',
                         'deserialization_error', 'kafka_publish_failure'
                     )),
    failure_stage    VARCHAR NOT NULL
                     CHECK (failure_stage IN ('validation', 'processing', 'kafka_publish')),
    error_details    TEXT,                              -- stack trace or validation messages
    correlation_id   VARCHAR,
    facility_id      VARCHAR,
    received_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    retry_count      INTEGER NOT NULL DEFAULT 0,
    next_retry_at    TIMESTAMPTZ,                       -- scheduled retry time
    resolved         BOOLEAN NOT NULL DEFAULT false,
    resolved_at      TIMESTAMPTZ
);

CREATE INDEX idx_dead_letter_reason     ON dead_letter_event (rejection_reason);
CREATE INDEX idx_dead_letter_source     ON dead_letter_event (source);
CREATE INDEX idx_dead_letter_received   ON dead_letter_event (received_at);
CREATE INDEX idx_dead_letter_unresolved ON dead_letter_event (next_retry_at) WHERE resolved = false;

-- Registered event sources (optional — for source allowlisting)
CREATE TABLE source_registration (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_uri       VARCHAR NOT NULL UNIQUE,           -- e.g., "rhie-mediator", "smartcare-emr"
    display_name     VARCHAR NOT NULL,
    description      TEXT,
    active           BOOLEAN NOT NULL DEFAULT true,
    api_key_hash     VARCHAR,                            -- hashed API key for authentication
    allowed_types    JSONB DEFAULT '[]',                 -- array of allowed event type patterns
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_source_registration_uri    ON source_registration (source_uri);
CREATE INDEX idx_source_registration_active ON source_registration (active);
```

### Schema Conventions

- All PKs are `UUID DEFAULT gen_random_uuid()`
- All timestamps are `TIMESTAMPTZ` (always stored/queried in UTC)
- Status/state columns use `CHECK` constraints with enumerated values
- JSONB columns use GIN indexes with `jsonb_path_ops` when queried
- Internal fields use `snake_case`
- CloudEvents extension attributes use `lowercase` (no separators) per CloudEvents spec
- Use Flyway versioned migrations (`V1__`, `V2__`, etc.) — never modify existing migrations

---

## REST API Endpoints

The Collector Service exposes its ingestion API to external systems. In production, the CCE Gateway terminates TLS and routes requests. For direct integrations, mTLS or API key authentication is used.

### Event Ingestion (Primary)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/v1/events` | API Key / mTLS | Ingest a single clinical event (CloudEvents envelope) |

### Dead Letter Management

| Method | Path | Scope | Description |
|--------|------|-------|-------------|
| GET | `/v1/dead-letters` | `collector:admin` | List dead-letter events (filter by reason, source, date range) |
| GET | `/v1/dead-letters/{id}` | `collector:admin` | Get a specific dead-letter event |
| POST | `/v1/dead-letters/{id}/retry` | `collector:admin` | Re-process a dead-letter event |

### Source Registration

| Method | Path | Scope | Description |
|--------|------|-------|-------------|
| POST | `/v1/sources` | `collector:admin` | Register a new event source |
| GET | `/v1/sources` | `collector:admin` | List registered sources |
| GET | `/v1/sources/{id}` | `collector:admin` | Get source details |
| PUT | `/v1/sources/{id}` | `collector:admin` | Update source registration |
| DELETE | `/v1/sources/{id}` | `collector:admin` | Deactivate a source |

### Response Envelope

```json
// Success — single event accepted
{
  "data": {
    "eventId": "evt-2026-03-15-001",
    "status": "accepted",
    "correlationId": "corr-abc-123-def-456",
    "publishedTopic": "cce.events.inbound",
    "receivedAt": "2026-03-15T10:30:01Z"
  }
}

// Error
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "Missing required CloudEvents field: 'type'",
    "details": { "field": "type" }
  }
}
```

Use cursor-based pagination (not offset-based) for dead-letter and source list endpoints.

---

## CloudEvents v1.0 Envelope Specification

### Inbound Event Structure

All inbound events MUST conform to CloudEvents v1.0 with CCE extensions:

```json
{
  "specversion": "1.0",
  "id": "evt-a1b2c3d4-1111-4000-8000-000000000001",
  "source": "rhie-mediator",
  "type": "org.openphc.cce.encounter",
  "subject": "260115-0001-7823",
  "time": "2026-01-15T08:30:00Z",
  "datacontenttype": "application/fhir+json",

  "facilityid": "0001",
  "correlationid": "corr-95fe0bdb-6462-5f36-ba91-c18caca81cd2",
  "sourceeventid": "enc-visit-20260129-001",

  "data": {
    "resourceType": "Encounter",
    "id": "9a8e5398-aaaa-4111-84a0-9e1e6e0a0001",
    "status": "finished",
    "class": { ... },
    "type": [ ... ],
    "subject": { "reference": "Patient/260115-0001-7823" }
  }
}
```

### Field Validation Rules

| Field | Required | Validation | Notes |
|-------|----------|------------|-------|
| `specversion` | **Yes** | Must be `"1.0"` | CloudEvents spec version |
| `id` | **Yes** | Non-empty string, max 256 chars | Globally unique event identifier |
| `source` | **Yes** | Non-empty string (URI or short identifier) | Must match a registered source if source allowlisting is enabled |
| `type` | **Yes** | Non-empty string, must match pattern `org.openphc.cce.<resource>` or `cce.<resource>.<action>` | Event type — drives Tier 1 structural matching in Compliance Service |
| `subject` | **Yes** (CCE requirement) | Non-empty string (patient UPID) | Partition key for Kafka; absent `subject` → reject |
| `time` | Recommended | ISO 8601 datetime | If absent, Collector fills with `received_at` |
| `datacontenttype` | Recommended | MIME type string | If `application/fhir+json`, FHIR validation is applied |
| `facilityid` | Recommended | Non-empty string (FOSA ID) | Healthcare facility identifier |
| `correlationid` | Recommended | UUID string | If absent, Collector generates a new UUID |
| `sourceeventid` | Optional | String | Source system's internal event ID |
| `data` | **Yes** (CCE requirement) | Must be a valid JSON object | If `datacontenttype` = `application/fhir+json`, must be a valid FHIR R4 resource |

### Event Type Conventions

Sources may use either naming pattern — the Collector normalizes to the `org.openphc.cce.*` format:

| Inbound Pattern | Normalized Form | Example |
|-----------------|-----------------|---------|
| `org.openphc.cce.<resource>` | Pass-through | `org.openphc.cce.encounter` |
| `cce.<resource>.created` | `org.openphc.cce.<resource>` | `cce.observation.created` → `org.openphc.cce.observation` |
| `cce.<resource>.updated` | `org.openphc.cce.<resource>` | `cce.encounter.updated` → `org.openphc.cce.encounter` |

### CCE Extension Attributes

Per the CloudEvents specification, extension attribute names are `lowercase` with no separators:

| Extension | Key | Purpose |
|-----------|-----|---------|
| Facility ID | `facilityid` | Healthcare facility where the event occurred |
| Correlation ID | `correlationid` | Distributed tracing ID across the RHIE/CCE ecosystem |
| Source Event ID | `sourceeventid` | Original event ID from the source system |
| Protocol Instance ID | `protocolinstanceid` | Pre-populated if source knows the target protocol instance |
| Protocol Definition ID | `protocoldefinitionid` | Pre-populated if source knows the target protocol |
| Action ID | `actionid` | Pre-populated if source knows the target action/step |

---

## Core Processing Algorithm

### Event Ingestion Flow (EventIngestionService)

```
1. Receive HTTP POST → parse request body
2. CloudEvents Envelope Validation
   a. Required fields: specversion, id, source, type, subject, data
   b. specversion must be "1.0"
   c. subject must be non-empty (patient UPID required by CCE)
   d. If validation fails → 400 response + persist to dead_letter_event
3. Source Validation (if source allowlisting enabled)
   a. Check source_registration table: source is registered AND active
   b. Validate API key if source requires it
   c. If unknown/inactive source → 403 response + persist to dead_letter_event
4. Persist to inbound_event table (status = 'received', raw_payload = original request body)
   — This is the first write: raw audit record before any transformation
5. Deduplication Check
   a. Redis fast-path: GET idempotency:{source}:{cloudevents_id}
      - If key exists → update inbound_event.status = 'duplicate', return 200 (idempotent)
   b. If not in Redis → proceed (DB unique constraint on event_log is the authoritative check)
6. Normalization
   a. Normalize event type to org.openphc.cce.* pattern
   b. Generate correlationid if absent (new UUID with "corr-" prefix)
   c. Fill time with server received_at if absent
   d. Map lowercase CloudEvents field names → camelCase for Kafka message
7. FHIR Payload Validation (if datacontenttype = application/fhir+json)
   a. Parse data via HAPI FHIR: fhirContext.newJsonParser().parseResource(json)
   b. Validate resourceType is a known FHIR R4 resource type
   c. Validate subject reference matches CloudEvents subject (patient ID consistency)
   d. If invalid → update inbound_event.status = 'rejected',
      persist to dead_letter_event (failure_stage = 'validation'), return 422
8. Update inbound_event.status = 'accepted'
9. Persist normalized event to event_log table (publish_status = 'pending')
   — This is the outbox record: the normalized CloudEventMessage ready for Kafka
10. Publish event_log record to Kafka topic cce.events.inbound
    a. Key = subject (patient_id) — guarantees per-patient message ordering
    b. Value = normalized CloudEventMessage JSON (built from event_log fields)
    c. On success: update event_log.publish_status = 'published',
       record kafka_topic, kafka_partition, kafka_offset, published_at
    d. On failure: event_log stays publish_status = 'pending' (retry on next attempt)
       + persist to dead_letter_event (failure_stage = 'kafka_publish')
11. Set Redis idempotency key: SET idempotency:{source}:{cloudevents_id} 1 EX 86400
12. Return HTTP response with ingestion receipt
```

### Kafka Publish Contract

The Collector publishes a `CloudEventMessage` JSON object to `cce.events.inbound`. Each Kafka message corresponds to exactly one `event_log` row (the outbox pattern):

```json
{
  "id": "evt-a1b2c3d4-1111-4000-8000-000000000001",
  "source": "rhie-mediator",
  "type": "org.openphc.cce.encounter",
  "specVersion": "1.0",
  "subject": "260115-0001-7823",
  "time": "2026-01-15T08:30:00Z",
  "dataContentType": "application/fhir+json",
  "correlationId": "corr-95fe0bdb-6462-5f36-ba91-c18caca81cd2",
  "sourceEventId": "enc-visit-20260129-001",
  "protocolInstanceId": null,
  "protocolDefinitionId": null,
  "actionId": null,
  "facilityId": "0001",
  "data": { ... }
}
```

> **CRITICAL:** The Kafka message uses **camelCase** field names (matching the `CloudEventMessage` Java class in the Compliance Service consumer). The inbound HTTP request uses **lowercase** attribute names per CloudEvents spec. The Collector must transform field names during normalization.

### Field Name Mapping (HTTP → Kafka)

| HTTP CloudEvents (lowercase) | Kafka CloudEventMessage (camelCase) |
|------------------------------|--------------------------------------|
| `specversion` | `specVersion` |
| `id` | `id` |
| `source` | `source` |
| `type` | `type` |
| `subject` | `subject` |
| `time` | `time` |
| `datacontenttype` | `dataContentType` |
| `facilityid` | `facilityId` |
| `correlationid` | `correlationId` |
| `sourceeventid` | `sourceEventId` |
| `protocolinstanceid` | `protocolInstanceId` |
| `protocoldefinitionid` | `protocolDefinitionId` |
| `actionid` | `actionId` |
| `data` | `data` |

---

## Kafka Integration

### Topics This Service Produces

| Topic | Key | Purpose |
|-------|-----|---------|
| `cce.events.inbound` | `subject` (patient_id) | Validated clinical events for Compliance Service |
| `cce.deadletter` | `correlationId` | Failed events for monitoring and retry |

### This Service Does NOT Consume Any Kafka Topics

The Collector is a **producer-only** Kafka participant. It receives events via HTTP, not Kafka.

### Kafka Producer Configuration

```yaml
spring.kafka:
  bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
  producer:
    key-serializer: org.apache.kafka.common.serialization.StringSerializer
    value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
    acks: all
    retries: 3
    properties:
      enable.idempotence: true
      max.in.flight.requests.per.connection: 5
      linger.ms: 5
      batch.size: 16384
```

| Setting | Value | Rationale |
|---------|-------|-----------|
| `acks` | `all` | Wait for all in-sync replicas to acknowledge |
| `retries` | `3` | Retry on transient failures |
| `enable.idempotence` | `true` | Exactly-once semantics within a partition |
| `linger.ms` | `5` | Small batching window for throughput |
| `batch.size` | `16384` | 16KB batch size (default) |

### Kafka Producer Implementation

```java
@Service
@RequiredArgsConstructor
public class InboundEventProducer {

    private final KafkaTemplate<String, CloudEventMessage> kafkaTemplate;

    @Value("${cce.kafka.topics.inbound}")
    private String inboundTopic;

    public CompletableFuture<RecordMetadata> publish(CloudEventMessage event) {
        String key = event.getSubject();  // Partition by patient_id (UPID)
        return kafkaTemplate.send(inboundTopic, key, event)
            .thenApply(result -> {
                RecordMetadata metadata = result.getRecordMetadata();
                log.info("Published event {} to {}[{}]@{}",
                    event.getId(), metadata.topic(), metadata.partition(), metadata.offset());
                return metadata;
            })
            .exceptionally(ex -> {
                log.error("Failed to publish event {} to Kafka", event.getId(), ex);
                throw new KafkaPublishException(event, ex);
            });
    }
}
```

### Topic Configuration

The `cce.events.inbound` topic:
- **12 partitions** — supports up to 12 parallel Compliance Service consumers
- **Replication factor: 3** (production) / 1 (local dev)
- **Retention: 7 days** — events are replayable for a week
- **Partition key:** `subject` (patient UPID) — guarantees per-patient ordering
- **Cleanup policy:** `delete`

---

## FHIR Handling

### FhirContext Configuration

```java
@Configuration
public class FhirConfig {
    @Bean
    public FhirContext fhirContext() {
        return FhirContext.forR4();  // Thread-safe singleton — expensive to create
    }
}
```

- **NEVER** hand-parse FHIR JSON — always use `fhirContext.newJsonParser().parseResource()`
- `FhirContext` is expensive to create — instantiate once as a Spring Bean
- Use `IParser.setPrettyPrint(false)` for Kafka messages (minimize payload size)

### FHIR Validation Strategy

The Collector performs **structural validation**, not clinical validation:

| Check | Level | Action on Failure |
|-------|-------|-------------------|
| `data` parses as valid JSON | Required | Reject with `invalid_fhir` |
| `data.resourceType` is present and non-empty | Required | Reject with `invalid_fhir` |
| HAPI FHIR can parse into an `IBaseResource` | Required | Reject with `invalid_fhir` |
| `data.resourceType` is a known FHIR R4 type | Warning | Accept but log warning |
| `data.subject.reference` contains patient ID matching `subject` | Warning | Accept but log warning (may be implicit) |
| FHIR profile conformance | Optional (future) | Accept but include validation warnings in response |

### Supported FHIR Resource Types

Events from existing CCE sources contain these resource types:

| Resource Type | Event Type | Example |
|---------------|------------|---------|
| `Encounter` | `org.openphc.cce.encounter` | Visit registration, consultation |
| `Observation` | `org.openphc.cce.observation` | Vital signs, lab results |
| `Condition` | `org.openphc.cce.condition` | Diagnoses |
| `MedicationRequest` | `org.openphc.cce.medicationrequest` | Prescriptions |
| `MedicationDispense` | `org.openphc.cce.medicationdispense` | Pharmacy dispensing |
| `ServiceRequest` | `org.openphc.cce.servicerequest` | Lab/imaging orders |
| `Procedure` | `org.openphc.cce.procedure` | Clinical procedures |
| `EpisodeOfCare` | `org.openphc.cce.episodeofcare` | ANC enrollment, program enrollment |

The Collector does not restrict resource types — any valid FHIR R4 resource is accepted.

---

## Deduplication Strategy

Deduplication uses a **two-layer** approach: Redis fast-path (24h window) + PostgreSQL unique constraints (permanent).

### Layer 1: Redis Key-Value Fast-Path

Avoids a DB round-trip for the vast majority of duplicate submissions (retries, at-least-once delivery):

```
Key:   idempotency:{source}:{cloudevents_id}
Value: 1
TTL:   24 hours (86400 seconds)
```

On event arrival:
1. `GET idempotency:{source}:{cloudevents_id}` — if key exists, return 200 (duplicate) immediately. No DB query needed.
2. If key does NOT exist, proceed with normal processing.
3. After successful Kafka publish, `SET idempotency:{source}:{cloudevents_id} 1 EX 86400`.

```java
public boolean isDuplicateViaRedis(String source, String cloudeventsId) {
    String key = "idempotency:" + source + ":" + cloudeventsId;
    return Boolean.TRUE.equals(redisTemplate.hasKey(key));
}

public void markAsProcessed(String source, String cloudeventsId) {
    String key = "idempotency:" + source + ":" + cloudeventsId;
    redisTemplate.opsForValue().set(key, "1", Duration.ofHours(24));
}
```

The 24-hour TTL covers the vast majority of duplicate submissions. Events older than 24h fall through to the DB constraint.

### Layer 2: PostgreSQL Unique Constraints (Authoritative)

Both tables have unique constraints that serve as the permanent deduplication layer:

```sql
-- Primary: (cloudevents_id, source) on both inbound_event and event_log
CONSTRAINT uq_inbound_event_id_source UNIQUE (cloudevents_id, source)   -- inbound_event
UNIQUE (cloudevents_id, source)                                         -- event_log

-- Secondary: (source, source_event_id) on event_log — catches re-sent events with new IDs
CREATE UNIQUE INDEX idx_event_log_source_sourceeventid
    ON event_log (source, source_event_id) WHERE source_event_id IS NOT NULL;
```

On duplicate insert, PostgreSQL raises a constraint violation → the service returns 200 with `status: "duplicate"` (idempotent response).

### Idempotency Contract

- Submitting the same event twice (same `id` + `source`) returns **200 OK** (not 409 Conflict)
- The response includes `status: "duplicate"` so callers know it was already processed
- The event is **not** re-published to Kafka on duplicate submission
- Secondary dedup: same `source` + `source_event_id` (different `id`) also detected as duplicate via `event_log` index
- This follows the standard idempotent POST pattern used by openHIM mediators

---

## Redis Usage (Collector Service Scope)

The Collector Service uses Redis for:

1. **Idempotency fast-path** — `idempotency:{source}:{cloudevents_id}` with 24h TTL (avoids DB round-trip for recent duplicates)
2. **Source registration cache** — cache active source registrations to avoid DB lookups per request

Redis is **optional** — the service MUST function correctly without Redis (fallback to DB-only dedup via unique constraints and DB-only source lookups).

---

## Normalization Rules

### Event Type Normalization

```java
public String normalizeEventType(String rawType) {
    if (rawType.startsWith("org.openphc.cce.")) {
        return rawType;  // Already normalized
    }
    // cce.observation.created → org.openphc.cce.observation
    // cce.encounter.updated → org.openphc.cce.encounter
    Matcher m = Pattern.compile("^cce\\.([a-z]+)\\.(?:created|updated|deleted)$").matcher(rawType);
    if (m.matches()) {
        return "org.openphc.cce." + m.group(1);
    }
    // Unknown pattern — pass through (Compliance Service will handle matching)
    return rawType;
}
```

### Correlation ID Generation

If the inbound event is missing `correlationid`, the Collector generates one:

```java
public String ensureCorrelationId(String existing) {
    return (existing != null && !existing.isBlank())
        ? existing
        : "corr-" + UUID.randomUUID();
}
```

### Time Fill

If the inbound event is missing `time`, fill with the server's received timestamp:

```java
public OffsetDateTime ensureEventTime(String rawTime) {
    return (rawTime != null && !rawTime.isBlank())
        ? OffsetDateTime.parse(rawTime)
        : OffsetDateTime.now(ZoneOffset.UTC);
}
```

---

## Authentication & Security

### API Key Authentication (Primary)

External sources authenticate using an API key in the `X-API-Key` header:

```java
@Component
public class ApiKeyFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, ...) {
        String apiKey = request.getHeader("X-API-Key");
        if (apiKey == null || !sourceRegistrationService.validateApiKey(apiKey)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        filterChain.doFilter(request, response);
    }
}
```

### mTLS (Production)

In production deployments behind an openHIM mediator, the Collector validates the client certificate presented by the mediator. TLS termination happens at the infrastructure layer (load balancer / service mesh).

### Rate Limiting

Per-source rate limiting to prevent a single source from overwhelming the system:

| Source Type | Default Limit | Window |
|-------------|---------------|--------|
| openHIM mediator | 1000 events/min | 1 minute sliding |
| Direct EMR integration | 200 events/min | 1 minute sliding |

Rate limits are configurable per source via `source_registration.allowed_types` or a dedicated config.

---

## Observability

### Metrics to Instrument

```java
// Events received by source and outcome
Counter.builder("cce.collector.events.received")
    .tag("source", source)
    .tag("status", "accepted")  // or rejected, duplicate
    .register(meterRegistry);

// FHIR validation outcomes
Counter.builder("cce.collector.fhir.validation")
    .tag("result", "valid")  // or invalid
    .tag("resourceType", "Encounter")
    .register(meterRegistry);

// Kafka publish latency
Timer.builder("cce.collector.kafka.publish.duration")
    .register(meterRegistry);

// Kafka publish failures
Counter.builder("cce.collector.kafka.publish.failures")
    .register(meterRegistry);

// Active dead-letter events
Gauge.builder("cce.collector.deadletters.active", deadLetterRepository, r -> r.countByRetriedFalse())
    .register(meterRegistry);

// Event ingestion latency (end-to-end: receive → publish)
Timer.builder("cce.collector.ingestion.duration")
    .register(meterRegistry);

// Event ingestion latency (end-to-end: receive → publish)
- `/actuator/health` — includes Kafka producer, PostgreSQL, Redis connectivity
- `/actuator/health/liveness` — basic liveness (JVM is running)
- `/actuator/health/readiness` — readiness (Kafka producer connected, DB accessible)
- `/actuator/prometheus` — Micrometer metrics for Prometheus scraping

---

## Code Generation Guidelines

### General Patterns

- Use **constructor injection** exclusively — no `@Autowired` on fields
- Use `@RequiredArgsConstructor` (Lombok) or explicit constructors
- Entity classes: JPA `@Entity` with Hibernate
- Use `@Column(columnDefinition = "jsonb")` for JSONB fields
- Use `@Enumerated(EnumType.STRING)` for enum columns
- Use `Optional<T>` for nullable return values from repositories
- Use Java records for DTOs and value objects where appropriate
- Validate inputs with Bean Validation (`@Valid`, `@NotNull`, `@NotBlank`)

### REST Controller Pattern

```java
@RestController
@RequestMapping("/v1/events")
@RequiredArgsConstructor
public class EventIngestionController {

    private final EventIngestionService ingestionService;

    @PostMapping
    public ResponseEntity<ApiResponse<EventIngestionResponse>> ingestEvent(
            @Valid @RequestBody EventIngestionRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {

        MDC.put("correlationId", correlationId);
        try {
            EventIngestionResponse response = ingestionService.ingest(request);
            HttpStatus status = response.isDuplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED;
            return ResponseEntity.status(status)
                .body(ApiResponse.success(response));
        } finally {
            MDC.clear();
        }
    }
```

### Transaction Boundaries

- Use `@Transactional` on service methods that write to the database
- The two-table design naturally implements the **outbox pattern**:
  1. **Transaction 1:** Insert raw record into `inbound_event` (status = 'received') — immediate audit trail
  2. **Transaction 2:** Validate, normalize, insert into `event_log` (publish_status = 'pending'), update `inbound_event.status = 'accepted'`
  3. **After commit:** Publish `event_log` record to Kafka
  4. **On Kafka success:** Update `event_log.publish_status = 'published'`, record Kafka metadata, set Redis idempotency key
  5. **On Kafka failure:** `event_log` stays `publish_status = 'pending'`, persist to `dead_letter_event` (failure_stage = 'kafka_publish')
- A **retry mechanism** (scheduled task or on-demand) scans `event_log WHERE publish_status = 'pending' AND received_at < now() - interval '30 seconds'` to re-publish failed events
- Keep transactions short — avoid holding DB locks during Kafka operations

### Error Handling

- `400 VALIDATION_ERROR` — invalid CloudEvents envelope, missing required fields
- `401 UNAUTHORIZED` — missing or invalid API key
- `403 FORBIDDEN` — source not registered or inactive
- `413 PAYLOAD_TOO_LARGE` — request body exceeds max size (1 MB)
- `422 UNPROCESSABLE_ENTITY` — FHIR payload validation failure
- `429 TOO_MANY_REQUESTS` — rate limit exceeded
- `500 INTERNAL_SERVER_ERROR` — unexpected failures
- `503 SERVICE_UNAVAILABLE` — Kafka producer or DB not reachable
- Use `@ControllerAdvice` with `@ExceptionHandler` for centralized error handling
- Always return the `{ "error": { "code", "message" } }` envelope

---

## Testing Strategy

### Unit Tests
- `CloudEventValidator` — envelope validation with valid/invalid permutations
- `EventNormalizer` — type normalization, correlation ID generation, time fill
- `FhirPayloadValidator` — FHIR parsing with valid/invalid/edge-case payloads
- `DeduplicationService` — duplicate detection logic

### Integration Tests
- Use **Testcontainers** for PostgreSQL, Kafka, and Redis
- Test full ingestion flow: HTTP POST → validation → DB persist → Kafka publish → verify message on topic
- Test deduplication: submit same event twice, verify idempotent response
- Test dead-lettering: invalid events persisted with correct rejection reasons

### API Tests
- Use `@WebMvcTest` + `MockMvc` for controller tests
- Verify response envelopes, HTTP status codes, error formats
- Test API key authentication (valid, invalid, missing)
- Test rate limiting (if implemented)

### Test Fixtures
- Maintain sample CloudEvents JSON files in `src/test/resources/fixtures/`
- Include: valid encounter event, valid observation event, invalid envelope (missing type), invalid FHIR payload, duplicate event
- Reference sample events from `artifacts/sample-kafka-events-ebuzima-visit.json` and `artifacts/sample-kafka-events-rhie.json` for realistic test data

---

## Configuration (application.yml)

```yaml
spring:
  application:
    name: cce-collector-service
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:cce_collector}
    username: ${DB_USER:cce_collector}
    password: ${DB_PASSWORD:cce_collector}
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
  jpa:
    hibernate:
      ddl-auto: validate  # Flyway manages schema
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
  flyway:
    enabled: true
    locations: classpath:db/migration
  kafka:
    bootstrap-servers: ${KAFKA_BROKERS:localhost:9092}
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
      acks: all
      retries: 3
      properties:
        enable.idempotence: true
        max.in.flight.requests.per.connection: 5
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}

cce:
  kafka:
    topics:
      inbound: cce.events.inbound
      dead-letter: cce.deadletter
  collector:
    max-payload-size: 1048576          # 1 MB for single event
    source-allowlisting: false         # Set true to enforce source registration
    fhir-validation:
      enabled: true
      strict-mode: false               # true = reject on any warning; false = warnings logged only
    dedup:
      redis-enabled: true              # Enable Redis key-value fast-path dedup (24h TTL)
    outbox:
      retry-interval-seconds: 30       # How often to scan event_log for unpublished records
      retry-max-age-minutes: 60        # Stop retrying after this age

server:
  port: 8080

management:
  endpoints:
    web:
      exposure:
        include: health,prometheus,info
  endpoint:
    health:
      probes:
        enabled: true
      show-details: always
  metrics:
    tags:
      application: cce-collector-service
```

---

## Docker & Local Development

### docker-compose.yml (local dev)

Include PostgreSQL 16, Kafka (KRaft, single broker), and Redis 7 for local development. Flyway runs automatically on application startup.

```yaml
version: '3.8'

services:
  cce-collector-postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: cce_collector
      POSTGRES_USER: cce_collector
      POSTGRES_PASSWORD: cce_collector
    ports:
      - "5433:5432"
    volumes:
      - collector-pgdata:/var/lib/postgresql/data

  cce-kafka:
    image: apache/kafka:3.7.0
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@localhost:9093
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_INTER_BROKER_LISTENER_NAME: PLAINTEXT
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_LOG_DIRS: /tmp/kraft-combined-logs
    ports:
      - "9092:9092"

  cce-redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"

volumes:
  collector-pgdata:
```

Note: If running alongside the Compliance Service locally, share the same Kafka and Redis containers. The Collector uses a **separate PostgreSQL database** (`cce_collector` on port 5433) from the Compliance Service (`cce` on port 5432).

### Dockerfile

```dockerfile
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/cce-collector-service-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

Use multi-stage build for production: builder stage with Maven + JDK, runtime stage with JRE-alpine only.

---

## Deployment Notes

- **Stateless** — no in-memory state between requests. All state is in PostgreSQL.
- **Horizontally scalable** — add instances behind a load balancer
- **Initial deployment:** 2 replicas behind an L4/L7 load balancer
- Resource allocation: 0.5 CPU request, 512 MB memory request per instance
- Collector runs on port **8080** (same port as other CCE services; Gateway routes by hostname)
- PgBouncer in transaction-mode pooling recommended for connection management
- In production, the CCE Gateway routes `/v1/events*` to the Collector Service
- API key management: store hashed keys in `source_registration` table; rotate via admin API

---

## Interaction Contract with Compliance Service

The Collector and Compliance Service have a **loose coupling** via Kafka. The Collector's output is the Compliance Service's input.

### What the Compliance Service Expects

The Compliance Service consumes `CloudEventMessage` objects from `cce.events.inbound` with these expectations:

1. **`subject` is always present** — the Compliance Service uses it to look up active protocol instances for the patient
2. **`type` follows the `org.openphc.cce.<resource>` pattern** — the Compliance Service uses it for Tier 1 structural matching in `trigger_index`
3. **`data` contains a valid FHIR R4 resource** — parsed via `fhirContext.newJsonParser().parseResource(json)` in the Compliance Service
4. **Field names are camelCase** — the `CloudEventMessage` Java class uses camelCase (`specVersion`, `dataContentType`, `correlationId`, etc.)
5. **Kafka key is `subject`** — ensures per-patient ordering across partitions
6. **`correlationId` is always present** — used for distributed tracing (MDC) in the Compliance Service
7. **Each Kafka message corresponds to an `event_log` row** — the Collector's `event_log` table is the authoritative source of truth for what was published

### What the Compliance Service Does NOT Expect

- The Collector does NOT need to populate `protocolInstanceId`, `protocolDefinitionId`, or `actionId` — the Compliance Service resolves these from the event data
- The Collector does NOT need to know about PlanDefinitions or protocol steps — it is purely an ingestion gateway
- The Collector does NOT need to verify that the patient exists or is enrolled — the Compliance Service handles zero-match cases gracefully

---

## Out of Scope (Explicit Exclusions)

- Protocol matching, step completion, or compliance tracking (Compliance Service)
- Time-based transitions (Scheduler Service)
- OAuth/JWT token validation (Gateway Service handles this upstream)
- FHIR profile conformance validation (structural parse only — clinical validation is out of scope)
- Event transformation or enrichment beyond normalization (no external lookups, no patient registry calls)
- Event routing to multiple topics (all events go to `cce.events.inbound`)
- WebSocket or streaming event ingestion (REST-only for now)
- Brownfield event backfill / historical data migration
