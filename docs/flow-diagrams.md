# CCE Collector Service — Flow Diagrams

Visual diagrams of the Collector Service's processing flows using Mermaid.

---

## 1. Event Ingestion Flow (Main Processing Pipeline)

```mermaid
flowchart TD
    A[HTTP POST /v1/events] --> B{CloudEvents<br/>Envelope Valid?}
    B -->|No| C[400 Bad Request]
    C --> C1[Dead Letter<br/>INVALID_ENVELOPE]
    B -->|Yes| D{Duplicate?<br/>source + id in<br/>lookback window}
    D -->|Yes| E[200 OK<br/>status: duplicate]
    D -->|No| F[Persist to<br/>inbound_event<br/>status: RECEIVED]
    F --> G[Normalize<br/>• event type → org.openphc.cce.*<br/>• generate correlationId<br/>• fill time if absent]
    G --> H{FHIR Payload<br/>Valid?}
    H -->|No| I[422 Unprocessable]
    I --> I1[Update inbound_event<br/>status: REJECTED]
    I1 --> I2[Dead Letter<br/>INVALID_FHIR]
    H -->|Yes| J[Update inbound_event<br/>status: ACCEPTED]
    J --> K[Persist to event_log<br/>publish_status: PENDING]
    K --> L{Kafka Publish<br/>Successful?}
    L -->|Yes| M[Update event_log<br/>publish_status: PUBLISHED<br/>+ kafka metadata]
    L -->|No| N[Dead Letter<br/>KAFKA_PUBLISH_FAILURE<br/>event_log stays PENDING]
    M --> O[200 OK<br/>Ingestion Receipt]
    N --> O

    style A fill:#4a90d9,color:#fff
    style E fill:#f0ad4e,color:#000
    style O fill:#5cb85c,color:#fff
    style C fill:#d9534f,color:#fff
    style I fill:#d9534f,color:#fff
    style C1 fill:#d9534f,color:#fff
    style I2 fill:#d9534f,color:#fff
    style N fill:#d9534f,color:#fff
```

---

## 2. Sequence Diagram — Successful Event Ingestion

```mermaid
sequenceDiagram
    participant Client as External System<br/>(openHIM / EMR)
    participant Controller as EventIngestionController
    participant Validator as CloudEventValidator
    participant Dedup as DeduplicationService
    participant Repo as InboundEventRepository
    participant Normalizer as EventNormalizer
    participant FHIR as FhirPayloadValidator
    participant EventLog as EventLogRepository
    participant Publisher as EventPublisher
    participant Kafka as Kafka Broker

    Client->>Controller: POST /v1/events (CloudEvents JSON)
    Controller->>Validator: validate(request)
    Validator-->>Controller: ✓ valid

    Controller->>Dedup: isDuplicate(source, id)
    Dedup-->>Controller: false

    Controller->>Repo: save(inboundEvent, status=RECEIVED)
    Repo-->>Controller: inboundEvent

    Controller->>Normalizer: normalizeEventType(type)
    Normalizer-->>Controller: org.openphc.cce.encounter
    Controller->>Normalizer: ensureCorrelationId(correlationid)
    Normalizer-->>Controller: corr-<uuid>
    Controller->>Normalizer: ensureEventTime(time)
    Normalizer-->>Controller: OffsetDateTime

    Controller->>FHIR: validate(request)
    FHIR-->>Controller: ✓ valid FHIR R4

    Controller->>Repo: save(inboundEvent, status=ACCEPTED)

    Controller->>EventLog: save(eventLog, publish_status=PENDING)
    EventLog-->>Controller: eventLog

    Controller->>Publisher: publish(eventLog)
    Publisher->>Kafka: send(topic, key=subject, value=CloudEventMessage)
    Kafka-->>Publisher: RecordMetadata (topic, partition, offset)
    Publisher->>EventLog: save(publish_status=PUBLISHED, kafka metadata)
    Publisher-->>Controller: CloudEventMessage

    Controller-->>Client: 200 OK {eventId, status: accepted, correlationId, publishedTopic}
```

---

## 3. Sequence Diagram — Validation Failure (FHIR)

```mermaid
sequenceDiagram
    participant Client as External System
    participant Controller as EventIngestionController
    participant Validator as CloudEventValidator
    participant Dedup as DeduplicationService
    participant Repo as InboundEventRepository
    participant Normalizer as EventNormalizer
    participant FHIR as FhirPayloadValidator
    participant DL as DeadLetterService

    Client->>Controller: POST /v1/events (invalid FHIR data)
    Controller->>Validator: validate(request)
    Validator-->>Controller: ✓ envelope valid

    Controller->>Dedup: isDuplicate(source, id)
    Dedup-->>Controller: false

    Controller->>Repo: save(inboundEvent, status=RECEIVED)
    Controller->>Normalizer: normalize fields
    Normalizer-->>Controller: normalized values

    Controller->>FHIR: validate(request)
    FHIR-->>Controller: ✗ FhirValidationException

    Controller->>Repo: save(inboundEvent, status=REJECTED)
    Controller->>DL: persistValidationFailure(INVALID_FHIR)

    Controller-->>Client: 422 Unprocessable Entity {error details}
```

---

## 4. Sequence Diagram — Duplicate Detection

```mermaid
sequenceDiagram
    participant Client as External System
    participant Controller as EventIngestionController
    participant Validator as CloudEventValidator
    participant Dedup as DeduplicationService

    Client->>Controller: POST /v1/events (same id + source as before)
    Controller->>Validator: validate(request)
    Validator-->>Controller: ✓ valid

    Controller->>Dedup: isDuplicate(source, id)
    Note over Dedup: Query inbound_event WHERE<br/>cloudevents_id = ? AND source = ?<br/>AND received_at > now() - lookback
    Dedup-->>Controller: true (duplicate found)

    Controller-->>Client: 200 OK {eventId, status: duplicate}
    Note over Client: Idempotent — no side effects
```

---

## 5. Outbox Retry Flow

```mermaid
flowchart TD
    A["@Scheduled retryPendingPublishes()<br/>every 30 seconds"] --> B[Query event_log<br/>WHERE publish_status IN<br/>PENDING, FAILED]
    B --> C{Records<br/>found?}
    C -->|No| D[Sleep until<br/>next interval]
    C -->|Yes| E{Age ><br/>max-age?}
    E -->|Yes| F[Skip record<br/>log warning]
    E -->|No| G[Publish to Kafka]
    G --> H{Success?}
    H -->|Yes| I[Update<br/>publish_status: PUBLISHED<br/>+ kafka metadata]
    H -->|No| J[Log warning<br/>retry on next cycle]
    I --> K{More<br/>records?}
    J --> K
    F --> K
    K -->|Yes| E
    K -->|No| D

    style A fill:#4a90d9,color:#fff
    style I fill:#5cb85c,color:#fff
    style F fill:#f0ad4e,color:#000
    style J fill:#d9534f,color:#fff
```

---

## 6. System Context — Data Flow

```mermaid
flowchart LR
    subgraph External["External Systems"]
        EMR1[eBUZIMA EMR]
        EMR2[SmartCare]
        CHW[CHW App]
        LAB[Lab Systems]
    end

    subgraph RHIE["RHIE Layer"]
        OH[openHIM Mediator]
    end

    subgraph CCE["CCE Platform"]
        GW[CCE Gateway<br/>auth + routing]
        CS[Collector Service<br/>validate → normalize<br/>→ dedup → publish]
        PG[(PostgreSQL<br/>inbound_event<br/>event_log<br/>dead_letter_event)]
        KF[Kafka<br/>cce.events.inbound]
        COMP[Compliance Service]
    end

    EMR1 --> OH
    EMR2 --> OH
    CHW --> OH
    LAB --> OH
    OH -->|HTTP POST<br/>CloudEvents| GW
    GW -->|authenticated| CS
    CS -->|persist| PG
    CS -->|publish| KF
    KF -->|consume| COMP

    style CS fill:#4a90d9,color:#fff
    style KF fill:#5cb85c,color:#fff
    style PG fill:#f0ad4e,color:#000
```

---

## 7. Database Entity Relationships

```mermaid
erDiagram
    inbound_event {
        UUID id PK
        VARCHAR cloudevents_id
        VARCHAR source
        VARCHAR type
        VARCHAR subject
        JSONB raw_payload
        VARCHAR status
        TIMESTAMPTZ received_at
    }

    event_log {
        UUID id PK
        UUID inbound_event_id FK
        VARCHAR cloudevents_id
        VARCHAR source
        VARCHAR subject
        VARCHAR type
        JSONB data
        VARCHAR publish_status
        VARCHAR kafka_topic
        INT kafka_partition
        BIGINT kafka_offset
        TIMESTAMPTZ received_at
    }

    dead_letter_event {
        UUID id PK
        UUID inbound_event_id FK
        VARCHAR cloudevents_id
        VARCHAR source
        JSONB raw_payload
        VARCHAR rejection_reason
        VARCHAR failure_stage
        BOOLEAN resolved
    }

    inbound_event ||--o| event_log : "accepted events"
    inbound_event ||--o{ dead_letter_event : "rejected/failed events"
```

---

## 8. Field Transformation Pipeline

```mermaid
flowchart LR
    subgraph Input["HTTP Request (lowercase)"]
        A1["specversion: '1.0'"]
        A2["type: 'cce.encounter.created'"]
        A3["correlationid: null"]
        A4["time: null"]
        A5["facilityid: '0002'"]
    end

    subgraph Normalize["EventNormalizer"]
        N1["type → org.openphc.cce.encounter"]
        N2["correlationid → corr-<uuid>"]
        N3["time → server received_at"]
    end

    subgraph Output["Kafka Message (camelCase)"]
        B1["specVersion: '1.0'"]
        B2["type: 'org.openphc.cce.encounter'"]
        B3["correlationId: 'corr-abc-123'"]
        B4["time: '2026-02-25T08:00:00Z'"]
        B5["facilityId: '0002'"]
    end

    A1 --> B1
    A2 --> N1 --> B2
    A3 --> N2 --> B3
    A4 --> N3 --> B4
    A5 --> B5

    style Normalize fill:#4a90d9,color:#fff
```
