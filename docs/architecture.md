# CCE Collector Service — Architecture

## 1. Purpose

The Collector Service is the **single point of entry** for all clinical events into the Care Coordination Engine (CCE) platform. No external system publishes directly to Kafka — every event flows through the Collector.

It receives clinical events from external EHR/RHIE systems (via openHIM mediators or direct integrations), validates and normalizes them into CloudEvents v1.0 envelopes with FHIR R4 payloads, and publishes them to Kafka for downstream processing by the Compliance Service.

## 2. Responsibilities

| # | Responsibility | Description |
|---|---------------|-------------|
| 1 | **Receive clinical events** | REST API ingestion from openHIM RHIE mediators, EMR direct push, CHW apps |
| 2 | **Validate CloudEvents envelope** | Mandatory fields (`specversion`, `id`, `source`, `type`, `subject`), extensions, structure |
| 3 | **Validate FHIR R4 payloads** | Structural validation of `data` when `datacontenttype` = `application/fhir+json` |
| 4 | **Normalize event metadata** | Standardize event types, extract patient IDs, generate missing fields |
| 5 | **Deduplicate inbound events** | Reject/mark duplicates using `(id, source)` compound key via PostgreSQL with configurable lookback window |
| 6 | **Publish to Kafka** | Topic `cce.events.inbound` with `subject` (patient_id) as partition key |
| 7 | **Dead-letter invalid events** | Persist rejected events with failure reasons for audit and retry |
| 8 | **Health/readiness endpoints** | For Kubernetes orchestration |

### Explicit Exclusions

- Protocol matching, step completion, or compliance tracking → **Compliance Service**
- Time-based state transitions → **Scheduler Service**
- OAuth token management, routing, rate limiting → **Gateway Service**
- Analytics or reporting → **Analytics Service**
- FHIR profile conformance validation (structural parse only)
- Event transformation or enrichment beyond normalization
- Event routing to multiple topics

## 3. System Context

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

## 4. Technology Stack

| Concern | Technology | Version |
|---------|------------|---------|
| Language | Java | 21 (LTS) |
| Framework | Spring Boot | 3.4.x |
| Build tool | Maven | 3.9+ |
| Database | PostgreSQL | 16+ |
| Message broker | Apache Kafka | 3.7+ (KRaft mode) |
| FHIR library | HAPI FHIR | 7.4.0 |
| DB access | Spring Data JPA + Hibernate | (Spring Boot managed) |
| DB migration | Flyway | (Spring Boot managed) |
| Connection pool | HikariCP | (Spring Boot default) |
| Observability | Micrometer + Prometheus | (Spring Boot managed) |
| Testing | JUnit 5, Testcontainers, MockMvc | |

## 5. Package Structure

```
org.openphc.cce.collector/
├── CollectorServiceApplication.java       # Spring Boot entry point
├── config/                                # Spring configuration beans
│   ├── KafkaProducerConfig.java           #   Kafka producer factory, templates, topic creation
│   ├── FhirConfig.java                    #   FhirContext.forR4() singleton bean
│   ├── JpaConfig.java                     #   Enables JPA repositories & transactions
│   ├── SecurityConfig.java                #   Stateless security, CSRF disabled
│   └── WebConfig.java                     #   CORS configuration
├── domain/
│   ├── model/                             # JPA entities
│   │   ├── InboundEvent.java              #   Raw inbound request record (audit/dedup)
│   │   ├── EventLog.java                  #   Normalized event outbox (Kafka publish source)
│   │   ├── DeadLetterEvent.java           #   Rejected/failed event record
│   │   ├── SourceRegistration.java        #   Registered event sources
│   │   └── enums/
│   │       ├── InboundStatus.java         #   RECEIVED, ACCEPTED, REJECTED, DUPLICATE
│   │       ├── PublishStatus.java         #   PENDING, PUBLISHED, FAILED
│   │       ├── RejectionReason.java       #   Validation/processing failure reasons
│   │       └── FailureStage.java          #   VALIDATION, PROCESSING, KAFKA_PUBLISH
│   └── repository/                        # Spring Data JPA repositories
│       ├── InboundEventRepository.java
│       ├── EventLogRepository.java
│       ├── DeadLetterEventRepository.java
│       └── SourceRegistrationRepository.java
├── service/                               # Core business logic
│   ├── EventIngestionService.java         #   Main orchestrator: validate → normalize → persist → publish
│   ├── CloudEventValidator.java           #   CloudEvents v1.0 envelope validation
│   ├── FhirPayloadValidator.java          #   FHIR R4 structural validation via HAPI
│   ├── EventNormalizer.java               #   Type normalization, correlation ID, time fill
│   ├── DeduplicationService.java          #   DB dedup with configurable lookback window
│   ├── EventPublisher.java                #   Outbox publisher + scheduled retry
│   ├── SourceRegistrationService.java     #   Source CRUD + API key validation
│   └── DeadLetterService.java             #   Dead-letter persistence and query
├── kafka/
│   └── InboundEventProducer.java          # Kafka publish to cce.events.inbound
├── api/
│   ├── controller/
│   │   ├── EventIngestionController.java  #   POST /v1/events
│   │   ├── DeadLetterController.java      #   Dead-letter CRUD
│   │   └── SourceController.java          #   Source registration CRUD
│   ├── dto/                               # Request/response data transfer objects
│   │   ├── ApiResponse.java               #   { "data": ... } envelope
│   │   ├── ApiError.java                  #   { "error": { "code", "message" } }
│   │   ├── EventIngestionRequest.java     #   CloudEvents envelope (inbound DTO)
│   │   ├── EventIngestionResponse.java    #   Accepted/rejected receipt
│   │   ├── CloudEventMessage.java         #   Kafka message DTO (camelCase fields)
│   │   ├── DeadLetterDto.java             #   Dead-letter response DTO
│   │   └── SourceRegistrationDto.java     #   Source registration request DTO
│   └── exception/
│       ├── GlobalExceptionHandler.java    #   @ControllerAdvice centralized error handling
│       ├── CloudEventValidationException.java
│       ├── FhirValidationException.java
│       ├── KafkaPublishException.java
│       ├── UnknownSourceException.java
│       └── DuplicateEventException.java
└── fhir/
    ├── FhirResourceParser.java            # HAPI FHIR parse + type detection
    └── FhirResourceValidator.java         # Structural validation + subject cross-check
```

## 6. Core Processing Algorithm

### Single Event Ingestion Flow

```
 1. Receive HTTP POST → parse request body
 2. CloudEvents Envelope Validation
    a. Required fields: specversion, id, source, type, subject, data
    b. specversion must be "1.0"
    c. subject must be non-empty (patient UPID required by CCE)
    d. If validation fails → 400 + persist to dead_letter_event
 3. Source Validation (if source allowlisting enabled)
    a. Check source_registration table: source is registered AND active
    b. If unknown/inactive → 403 + persist to dead_letter_event
 4. Persist to inbound_event (status = 'RECEIVED', raw_payload = original body)
 5. Deduplication Check
    a. Query PostgreSQL: check if (source, cloudevents_id) exists within lookback window
       - If exists → update status = 'DUPLICATE', return 200 (idempotent)
    b. If not found → proceed (DB unique constraint is authoritative)
 6. Normalization
    a. Normalize event type to org.openphc.cce.* pattern
    b. Generate correlationid if absent (UUID with "corr-" prefix)
    c. Fill time with server received_at if absent
 7. FHIR Payload Validation (if datacontenttype = application/fhir+json)
    a. Parse data via HAPI FHIR
    b. Validate resourceType is present and parseable
    c. Cross-check subject reference (warning only)
    d. If invalid → status = 'REJECTED', dead-letter, return 422
 8. Update inbound_event.status = 'ACCEPTED'
 9. Persist normalized event to event_log (publish_status = 'PENDING')
10. Publish event_log record to Kafka
    a. Key = subject (patient_id) — per-patient ordering
    b. On success: update publish_status = 'PUBLISHED', record Kafka metadata
    c. On failure: stays 'PENDING'/'FAILED', dead-letter created
11. Return HTTP 202 Accepted with ingestion receipt
```

## 7. Database Schema

Four tables owned by this service, managed by Flyway:

| Table | Purpose | Partitioned |
|-------|---------|-------------|
| `inbound_event` | Raw request audit log; primary dedup via `UNIQUE(cloudevents_id, source)` | No |
| `event_log` | Normalized event outbox for Kafka; monthly-partitioned by `received_at` | Yes (RANGE) |
| `dead_letter_event` | Rejected/failed events with retry support | No |
| `source_registration` | Registered event sources for allowlisting | No |

### Entity Relationship

```
inbound_event  1 ──── 0..1  event_log          (inbound_event_id FK)
inbound_event  1 ──── 0..*  dead_letter_event   (inbound_event_id FK)
```

### Deduplication Constraints

| Constraint | Table | Purpose |
|-----------|-------|---------|
| `UNIQUE(cloudevents_id, source)` | `inbound_event` | Primary dedup |
| `UNIQUE(cloudevents_id, source)` | `event_log` | Authoritative dedup |
| `UNIQUE(source, source_event_id) WHERE source_event_id IS NOT NULL` | `event_log` | Secondary dedup |

## 8. Deduplication Strategy

PostgreSQL-based deduplication with a configurable lookback window (default: 30 days) + unique constraints (permanent).

### Lookback Query

On event arrival, the service queries `inbound_event` for records matching `(source, cloudevents_id)` within the configured lookback window. This limits the query scope instead of scanning the entire database.

### PostgreSQL Unique Constraints (Authoritative)

Unique constraints on both `inbound_event` and `event_log` tables serve as the permanent deduplication layer.

### Idempotency Contract

- Same event submitted twice → **200 OK** with `status: "duplicate"` (not 409)
- Event is **not** re-published to Kafka on duplicate
- Standard idempotent POST pattern used by openHIM mediators

## 9. Kafka Integration

### Topics Produced

| Topic | Key | Purpose |
|-------|-----|---------|
| `cce.events.inbound` | `subject` (patient UPID) | Validated events for Compliance Service |
| `cce.deadletter` | `correlationId` | Failed events for monitoring |

### Producer Configuration

| Setting | Value | Rationale |
|---------|-------|-----------|
| `acks` | `all` | Wait for all in-sync replicas |
| `retries` | `3` | Retry on transient failures |
| `enable.idempotence` | `true` | Exactly-once within a partition |
| `linger.ms` | `5` | Small batching window |
| Partitions | 12 | Supports 12 parallel consumers |

### Outbox Pattern

The `event_log` table serves as the transactional outbox:
1. Event is written to `event_log` with `publish_status = PENDING`
2. After DB commit, Kafka publish is attempted
3. On success: `publish_status = PUBLISHED` + Kafka metadata recorded
4. On failure: stays `PENDING`/`FAILED`; a **scheduled retry** (every 30s) re-attempts

## 10. Field Name Mapping

The inbound HTTP request uses **lowercase** per CloudEvents spec. The Kafka message uses **camelCase** matching the Compliance Service consumer.

| HTTP (lowercase) | Kafka (camelCase) |
|------------------|-------------------|
| `specversion` | `specVersion` |
| `datacontenttype` | `dataContentType` |
| `facilityid` | `facilityId` |
| `correlationid` | `correlationId` |
| `sourceeventid` | `sourceEventId` |
| `protocolinstanceid` | `protocolInstanceId` |
| `protocoldefinitionid` | `protocolDefinitionId` |
| `actionid` | `actionId` |

## 11. FHIR Handling

### Strategy: Structural Validation Only

| Check | Level | Action on Failure |
|-------|-------|-------------------|
| `data` parses as valid JSON | Required | Reject (`invalid_fhir`) |
| `data.resourceType` present and non-empty | Required | Reject (`invalid_fhir`) |
| HAPI FHIR can parse into `IBaseResource` | Required | Reject (`invalid_fhir`) |
| `data.subject.reference` matches `subject` | Warning | Accept, log warning |

### Supported Resource Types

| Resource Type | Event Type |
|---------------|------------|
| `Encounter` | `org.openphc.cce.encounter` |
| `Observation` | `org.openphc.cce.observation` |
| `Condition` | `org.openphc.cce.condition` |
| `MedicationRequest` | `org.openphc.cce.medicationrequest` |
| `MedicationDispense` | `org.openphc.cce.medicationdispense` |
| `ServiceRequest` | `org.openphc.cce.servicerequest` |
| `Procedure` | `org.openphc.cce.procedure` |
| `EpisodeOfCare` | `org.openphc.cce.episodeofcare` |

The Collector does not restrict resource types — any valid FHIR R4 resource is accepted.

## 12. Compliance Service Contract

The Compliance Service consumes `CloudEventMessage` objects from `cce.events.inbound` with these guarantees from the Collector:

1. **`subject` is always present** — used for patient protocol instance lookup
2. **`type` follows `org.openphc.cce.<resource>`** — used for Tier 1 structural matching
3. **`data` contains a valid FHIR R4 resource** — parseable via HAPI FHIR
4. **Field names are camelCase** — matching the `CloudEventMessage` Java class
5. **Kafka key is `subject`** — per-patient ordering
6. **`correlationId` is always present** — for distributed tracing
7. **Each message maps to an `event_log` row** — authoritative source of truth

The Collector does NOT populate `protocolInstanceId`, `protocolDefinitionId`, or `actionId` — the Compliance Service resolves these independently.
