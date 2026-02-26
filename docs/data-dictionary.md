# CCE Collector Service — Data Dictionary

Consolidated reference for all database tables, columns, enums, CloudEvents fields, and Kafka message fields.

---

## 1. Database Tables

### 1.1 `inbound_event` — Raw Request Audit Log

Every HTTP request is persisted **as-is** before any normalization or processing. Used for audit trail and primary deduplication.

**Migration:** `V1__create_inbound_event.sql`

| Column | Type | Nullable | Default | Description |
|--------|------|----------|---------|-------------|
| `id` | `UUID` | No | `gen_random_uuid()` | Primary key |
| `cloudevents_id` | `VARCHAR` | No | — | CloudEvents `id` from the source system |
| `source` | `VARCHAR` | No | — | CloudEvents `source` (e.g., `rhie-mediator`, `ebuzima/kigali-south`) |
| `type` | `VARCHAR` | No | — | CloudEvents `type` (raw, before normalization) |
| `spec_version` | `VARCHAR` | No | `'1.0'` | CloudEvents spec version |
| `subject` | `VARCHAR` | Yes | — | Patient UPID (e.g., `260225-0002-5501`) |
| `event_time` | `TIMESTAMPTZ` | Yes | — | Source-provided event time |
| `data_content_type` | `VARCHAR` | Yes | `'application/fhir+json'` | MIME type of `data` payload |
| `facility_id` | `VARCHAR` | Yes | — | Healthcare facility FOSA ID |
| `correlation_id` | `VARCHAR` | Yes | — | Distributed tracing ID |
| `source_event_id` | `VARCHAR` | Yes | — | Source system's internal event ID |
| `raw_payload` | `JSONB` | No | — | Full original request body (immutable) |
| `status` | `VARCHAR` | No | `'RECEIVED'` | Processing status (see `InboundStatus` enum) |
| `rejection_reason` | `VARCHAR` | Yes | — | Rejection reason code (if status = `REJECTED`) |
| `received_at` | `TIMESTAMPTZ` | No | `now()` | Server-side receipt timestamp (UTC) |

**Constraints:**
- `PK`: `id`
- `UNIQUE`: `(cloudevents_id, source)` — primary deduplication key

**Indexes:**
| Index | Columns | Purpose |
|-------|---------|---------|
| `uq_inbound_event_id_source` | `(cloudevents_id, source)` | Deduplication (unique constraint) |
| `idx_inbound_event_subject` | `subject` | Patient-scoped queries |
| `idx_inbound_event_source` | `source` | Source-filtered queries |
| `idx_inbound_event_status` | `status` | Status-based filtering |
| `idx_inbound_event_received` | `received_at` | Time-range queries, lookback dedup |

---

### 1.2 `event_log` — Normalized Event Outbox

Each row corresponds to exactly one Kafka message on `cce.events.inbound`. This is the outbox table — the authoritative source of truth for published events.

**Migration:** `V2__create_event_log.sql`  
**Partitioned by:** `RANGE (received_at)` — monthly partitions

| Column | Type | Nullable | Default | Description |
|--------|------|----------|---------|-------------|
| `id` | `UUID` | No | `gen_random_uuid()` | Primary key |
| `inbound_event_id` | `UUID` | No | — | FK reference to `inbound_event.id` |
| `cloudevents_id` | `VARCHAR` | No | — | CloudEvents `id` (from source) |
| `source` | `VARCHAR` | No | — | CloudEvents `source` |
| `source_event_id` | `VARCHAR` | Yes | — | Source system's internal event ID |
| `subject` | `VARCHAR` | No | — | Patient UPID — also used as Kafka partition key |
| `type` | `VARCHAR` | No | — | **Normalized** event type (`org.openphc.cce.<resource>`) |
| `event_time` | `TIMESTAMPTZ` | No | — | Event time (original or server-generated) |
| `received_at` | `TIMESTAMPTZ` | No | — | Server receipt timestamp (UTC) |
| `correlation_id` | `VARCHAR` | No | — | Correlation ID (original or generated `corr-<uuid>`) |
| `data` | `JSONB` | No | — | FHIR R4 resource payload |
| `data_content_type` | `VARCHAR` | No | `'application/fhir+json'` | MIME type |
| `protocol_instance_id` | `UUID` | Yes | — | Pre-populated by source if known (usually null) |
| `protocol_definition_id` | `UUID` | Yes | — | Pre-populated by source if known (usually null) |
| `action_id` | `VARCHAR` | Yes | — | Pre-populated by source if known (usually null) |
| `facility_id` | `VARCHAR` | Yes | — | Healthcare facility FOSA ID |
| `publish_status` | `VARCHAR` | No | `'PENDING'` | Kafka publish state (see `PublishStatus` enum) |
| `published_at` | `TIMESTAMPTZ` | Yes | — | Timestamp of successful Kafka publish |
| `kafka_topic` | `VARCHAR` | Yes | — | Kafka topic name after publish |
| `kafka_partition` | `INT` | Yes | — | Kafka partition number after publish |
| `kafka_offset` | `BIGINT` | Yes | — | Kafka offset within partition after publish |

**Constraints:**
- `UNIQUE`: `(cloudevents_id, source, received_at)` — authoritative dedup (includes partition key)
- `UNIQUE (conditional)`: `(source, source_event_id, received_at)` WHERE `source_event_id IS NOT NULL` — secondary idempotency

**Indexes:**
| Index | Columns | Condition | Purpose |
|-------|---------|-----------|---------|
| `idx_event_log_source_sourceeventid` | `(source, source_event_id, received_at)` | `source_event_id IS NOT NULL` | Secondary dedup |
| `idx_event_log_subject` | `subject` | — | Patient-scoped queries |
| `idx_event_log_type` | `type` | — | Event type queries |
| `idx_event_log_correlation` | `correlation_id` | — | Distributed tracing |
| `idx_event_log_facility` | `facility_id` | `facility_id IS NOT NULL` | Facility-scoped queries |
| `idx_event_log_publish` | `publish_status` | `publish_status != 'PUBLISHED'` | Outbox retry scan |

**Partitions (pre-created):**
| Partition | Range |
|-----------|-------|
| `event_log_2026_01` | `2026-01-01` to `2026-02-01` |
| `event_log_2026_02` | `2026-02-01` to `2026-03-01` |
| `event_log_2026_03` | `2026-03-01` to `2026-04-01` |
| `event_log_2026_04` | `2026-04-01` to `2026-05-01` |
| `event_log_2026_05` | `2026-05-01` to `2026-06-01` |
| `event_log_2026_06` | `2026-06-01` to `2026-07-01` |

> New partitions must be added before the current range is exhausted. See `deployment-guide.md` § Adding New Partitions.

---

### 1.3 `dead_letter_event` — Rejected/Failed Events

Events that fail validation or Kafka publishing are persisted here for investigation and retry.

**Migration:** `V3__create_dead_letter_event.sql`

| Column | Type | Nullable | Default | Description |
|--------|------|----------|---------|-------------|
| `id` | `UUID` | No | `gen_random_uuid()` | Primary key |
| `inbound_event_id` | `UUID` | Yes | — | FK to `inbound_event.id` (null if deserialization failed before persist) |
| `cloudevents_id` | `VARCHAR` | Yes | — | CloudEvents `id` (may be null if unparseable) |
| `source` | `VARCHAR` | Yes | — | CloudEvents `source` |
| `type` | `VARCHAR` | Yes | — | CloudEvents `type` |
| `subject` | `VARCHAR` | Yes | — | Patient UPID |
| `raw_payload` | `JSONB` | No | — | Full original request body |
| `rejection_reason` | `VARCHAR` | No | — | Reason code (see `RejectionReason` enum) |
| `failure_stage` | `VARCHAR` | No | — | Pipeline stage where failure occurred (see below) |
| `error_details` | `TEXT` | Yes | — | Stack trace or validation error messages |
| `correlation_id` | `VARCHAR` | Yes | — | Correlation ID |
| `facility_id` | `VARCHAR` | Yes | — | Healthcare facility FOSA ID |
| `received_at` | `TIMESTAMPTZ` | No | `now()` | Timestamp |
| `retry_count` | `INTEGER` | No | `0` | Number of retry attempts |
| `next_retry_at` | `TIMESTAMPTZ` | Yes | — | Scheduled next retry time |
| `resolved` | `BOOLEAN` | No | `false` | Whether the dead letter has been resolved |
| `resolved_at` | `TIMESTAMPTZ` | Yes | — | Resolution timestamp |

**Indexes:**
| Index | Columns | Condition | Purpose |
|-------|---------|-----------|---------|
| `idx_dead_letter_reason` | `rejection_reason` | — | Reason-based queries |
| `idx_dead_letter_source` | `source` | — | Source-filtered queries |
| `idx_dead_letter_received` | `received_at` | — | Time-range queries |
| `idx_dead_letter_unresolved` | `next_retry_at` | `resolved = false` | Retry queue scan |

---

## 2. Enum Values

### 2.1 `InboundStatus`

Status of an `inbound_event` record as it moves through the pipeline.

| Value | Description |
|-------|-------------|
| `RECEIVED` | Initial state — event persisted, not yet processed |
| `ACCEPTED` | Validation passed, event published (or queued for publish) |
| `REJECTED` | Validation failed — see `rejection_reason` for cause |
| `DUPLICATE` | Event already seen (same `cloudevents_id` + `source`) |

### 2.2 `PublishStatus`

Kafka publish state of an `event_log` record (outbox pattern).

| Value | Description |
|-------|-------------|
| `PENDING` | Awaiting Kafka publish (may be initial or post-failure) |
| `PUBLISHED` | Successfully published to Kafka — `kafka_topic`, `kafka_partition`, `kafka_offset`, `published_at` are populated |
| `FAILED` | Publish attempted but failed — eligible for retry |

### 2.3 `RejectionReason`

Reason an event was rejected and dead-lettered.

| Value | Failure Stage | Description |
|-------|---------------|-------------|
| `INVALID_ENVELOPE` | `VALIDATION` | Missing or invalid CloudEvents required fields |
| `INVALID_FHIR` | `VALIDATION` | FHIR R4 payload failed structural validation |
| `DUPLICATE` | `PROCESSING` | Duplicate `(id, source)` detected within lookback window |
| `MISSING_SUBJECT` | `VALIDATION` | `subject` field missing (required by CCE for patient routing) |
| `PAYLOAD_TOO_LARGE` | `VALIDATION` | Request body exceeds `max-payload-size` (default 1 MB) |
| `DESERIALIZATION_ERROR` | `VALIDATION` | Request body could not be parsed as JSON |
| `KAFKA_PUBLISH_FAILURE` | `KAFKA_PUBLISH` | Kafka broker unavailable or publish timed out |

### 2.4 `failure_stage`

Pipeline stage where the failure occurred (CHECK constraint on `dead_letter_event`).

| Value | Description |
|-------|-------------|
| `VALIDATION` | CloudEvents envelope or FHIR payload validation |
| `PROCESSING` | Deduplication, normalization, or persistence |
| `KAFKA_PUBLISH` | Kafka producer send failure |

---

## 3. CloudEvents Fields (Inbound HTTP)

Inbound requests use **lowercase** field names per the CloudEvents v1.0 specification.

### 3.1 Required Fields

| Field | Type | Validation | Example |
|-------|------|------------|---------|
| `specversion` | `string` | Must be `"1.0"` | `"1.0"` |
| `id` | `string` | Non-empty, max 256 chars | `"evt-eb010001-0001-4000-8000-000000000001"` |
| `source` | `string` | Non-empty URI or short identifier | `"rhie-mediator"` |
| `type` | `string` | Non-empty, normalized to `org.openphc.cce.<resource>` | `"cce.encounter.created"` |
| `subject` | `string` | Non-empty patient UPID | `"260225-0002-5501"` |
| `data` | `object` | Valid JSON; if FHIR, must parse via HAPI | `{ "resourceType": "Encounter", ... }` |

### 3.2 Recommended Fields

| Field | Type | Default if Absent | Example |
|-------|------|-------------------|---------|
| `time` | `string` | Server `received_at` | `"2026-02-25T08:00:00Z"` |
| `datacontenttype` | `string` | `"application/fhir+json"` | `"application/fhir+json"` |
| `facilityid` | `string` | — | `"0002"` |
| `correlationid` | `string` | Generated `corr-<uuid>` | `"corr-1343872c-636d-506f-b041-1e571d426932"` |

### 3.3 Optional Extension Fields

| Field | Type | Description |
|-------|------|-------------|
| `sourceeventid` | `string` | Source system's internal event ID |
| `protocolinstanceid` | `string` | Pre-populated if source knows the target protocol instance |
| `protocoldefinitionid` | `string` | Pre-populated if source knows the target protocol |
| `actionid` | `string` | Pre-populated if source knows the target action/step |

---

## 4. Kafka Message Fields (`CloudEventMessage`)

Published to `cce.events.inbound` using **camelCase** field names matching the Compliance Service consumer contract.

| Field | Type | Nullable | Source |
|-------|------|----------|--------|
| `id` | `String` | No | `event_log.cloudevents_id` |
| `source` | `String` | No | `event_log.source` |
| `type` | `String` | No | `event_log.type` (normalized) |
| `specVersion` | `String` | No | Always `"1.0"` |
| `subject` | `String` | No | `event_log.subject` — also the Kafka message key |
| `time` | `OffsetDateTime` | No | `event_log.event_time` |
| `dataContentType` | `String` | No | `event_log.data_content_type` |
| `correlationId` | `String` | No | `event_log.correlation_id` |
| `sourceEventId` | `String` | Yes | `event_log.source_event_id` |
| `protocolInstanceId` | `String` | Yes | `event_log.protocol_instance_id` (UUID → String) |
| `protocolDefinitionId` | `String` | Yes | `event_log.protocol_definition_id` (UUID → String) |
| `actionId` | `String` | Yes | `event_log.action_id` |
| `facilityId` | `String` | Yes | `event_log.facility_id` |
| `data` | `Map<String, Object>` | No | `event_log.data` (FHIR R4 resource) |

---

## 5. Field Name Mapping (HTTP → Kafka)

The Collector translates CloudEvents lowercase field names to camelCase for the Kafka message:

| HTTP Request (lowercase) | Kafka Message (camelCase) | Database Column |
|--------------------------|---------------------------|-----------------|
| `specversion` | `specVersion` | `spec_version` |
| `id` | `id` | `cloudevents_id` |
| `source` | `source` | `source` |
| `type` | `type` | `type` |
| `subject` | `subject` | `subject` |
| `time` | `time` | `event_time` |
| `datacontenttype` | `dataContentType` | `data_content_type` |
| `facilityid` | `facilityId` | `facility_id` |
| `correlationid` | `correlationId` | `correlation_id` |
| `sourceeventid` | `sourceEventId` | `source_event_id` |
| `protocolinstanceid` | `protocolInstanceId` | `protocol_instance_id` |
| `protocoldefinitionid` | `protocolDefinitionId` | `protocol_definition_id` |
| `actionid` | `actionId` | `action_id` |
| `data` | `data` | `data` / `raw_payload` |

---

## 6. Event Type Normalization

The Collector normalizes inbound event types to a standard pattern before publishing to Kafka:

| Inbound Pattern | Normalized Form | Example |
|-----------------|-----------------|---------|
| `org.openphc.cce.<resource>` | Pass-through (already normalized) | `org.openphc.cce.encounter` |
| `cce.<resource>.created` | `org.openphc.cce.<resource>` | `cce.observation.created` → `org.openphc.cce.observation` |
| `cce.<resource>.updated` | `org.openphc.cce.<resource>` | `cce.encounter.updated` → `org.openphc.cce.encounter` |
| `cce.<resource>.deleted` | `org.openphc.cce.<resource>` | `cce.medicationrequest.deleted` → `org.openphc.cce.medicationrequest` |
| Other patterns | Pass-through (no normalization) | `custom.event.type` |

---

## 7. FHIR Resource Types

The Collector accepts any valid FHIR R4 resource. These are the resource types commonly used in the CCE clinical workflow:

| Resource Type | Event Type | Clinical Context |
|---------------|------------|------------------|
| `Encounter` | `org.openphc.cce.encounter` | Visit registration, consultations |
| `Observation` | `org.openphc.cce.observation` | Vital signs, lab results |
| `Condition` | `org.openphc.cce.condition` | Diagnoses, chief complaints |
| `MedicationRequest` | `org.openphc.cce.medicationrequest` | Prescriptions |
| `MedicationDispense` | `org.openphc.cce.medicationdispense` | Pharmacy dispensing |
| `ServiceRequest` | `org.openphc.cce.servicerequest` | Lab orders, referrals |
| `Procedure` | `org.openphc.cce.procedure` | Clinical procedures |
| `EpisodeOfCare` | `org.openphc.cce.episodeofcare` | Care episodes |
| `DiagnosticReport` | `org.openphc.cce.diagnosticreport` | Lab and imaging reports |
| `Immunization` | `org.openphc.cce.immunization` | Vaccinations |
| `AllergyIntolerance` | `org.openphc.cce.allergyintolerance` | Allergy records |
| `CarePlan` | `org.openphc.cce.careplan` | Treatment plans |
| `Patient` | `org.openphc.cce.patient` | Patient demographics |

---

## 8. Migrations

| Version | File | Description |
|---------|------|-------------|
| V1 | `V1__create_inbound_event.sql` | `inbound_event` table with dedup unique constraint |
| V2 | `V2__create_event_log.sql` | `event_log` table, partitioned by month (Jan–Jun 2026) |
| V3 | `V3__create_dead_letter_event.sql` | `dead_letter_event` table with retry support |
