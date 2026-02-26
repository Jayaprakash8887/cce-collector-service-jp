# CCE Collector Service — Kafka Events

Detailed reference for all Kafka topics, message schemas, publishing contracts, and sample payloads.

---

## 1. Topics

### 1.1 `cce.events.inbound` — Primary Event Topic

| Property | Value |
|----------|-------|
| **Topic** | `cce.events.inbound` |
| **Direction** | Produced by Collector, consumed by Compliance Service |
| **Message Key** | `subject` (patient UPID) — guarantees per-patient ordering |
| **Message Value** | `CloudEventMessage` JSON (camelCase fields) |
| **Serialization** | Key: `StringSerializer`, Value: `JsonSerializer` |
| **Guarantees** | Exactly-once semantics (idempotent producer + outbox pattern) |
| **Ordering** | Per-patient ordering within a partition (key = patient UPID) |

### 1.2 `cce.deadletter` — Dead Letter Topic

| Property | Value |
|----------|-------|
| **Topic** | `cce.deadletter` |
| **Direction** | Produced by Collector (optional, for Kafka-based monitoring) |
| **Message Key** | `subject` (patient UPID, if available) |
| **Message Value** | Dead letter event JSON |
| **Purpose** | Downstream alerting/monitoring of rejected events |

---

## 2. Producer Configuration

```yaml
spring:
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
        linger.ms: 5
        batch.size: 16384
```

| Setting | Value | Rationale |
|---------|-------|-----------|
| `acks` | `all` | Wait for all in-sync replicas — no data loss |
| `retries` | `3` | Retry transient failures |
| `enable.idempotence` | `true` | Prevent duplicate messages on retry |
| `max.in.flight.requests.per.connection` | `5` | Max with idempotence enabled (Kafka requirement) |
| `linger.ms` | `5` | Slight batching delay for throughput |
| `batch.size` | `16384` | 16 KB batch size |

---

## 3. CloudEventMessage Schema

The Kafka message value is a `CloudEventMessage` JSON object. Field names are **camelCase** (translated from CloudEvents lowercase during normalization).

```json
{
  "id": "evt-eb010001-0001-4000-8000-000000000001",
  "source": "rhie-mediator",
  "type": "org.openphc.cce.encounter",
  "specVersion": "1.0",
  "subject": "260225-0002-5501",
  "time": "2026-02-25T08:00:00Z",
  "dataContentType": "application/fhir+json",
  "correlationId": "corr-1343872c-636d-506f-b041-1e571d426932",
  "sourceEventId": "enc-visit-20260225-0001",
  "protocolInstanceId": null,
  "protocolDefinitionId": null,
  "actionId": null,
  "facilityId": "0002",
  "data": {
    "resourceType": "Encounter",
    "id": "enc-uuid-visit-kicukiro-001",
    "status": "in-progress",
    "...": "..."
  }
}
```

### Field Reference

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | `String` | Yes | CloudEvents event identifier (from source) |
| `source` | `String` | Yes | Event source (e.g., `rhie-mediator`, `ebuzima/kigali-south`) |
| `type` | `String` | Yes | Normalized event type (`org.openphc.cce.<resource>`) |
| `specVersion` | `String` | Yes | Always `"1.0"` |
| `subject` | `String` | Yes | Patient UPID — also the Kafka message key |
| `time` | `ISO-8601 datetime` | Yes | Event time (source-provided or server-generated) |
| `dataContentType` | `String` | Yes | MIME type (typically `application/fhir+json`) |
| `correlationId` | `String` | Yes | Distributed tracing ID (source-provided or generated `corr-<uuid>`) |
| `sourceEventId` | `String` | No | Source system's internal event ID |
| `protocolInstanceId` | `String` | No | Protocol instance UUID (usually null — Compliance Service resolves) |
| `protocolDefinitionId` | `String` | No | Protocol definition UUID (usually null — Compliance Service resolves) |
| `actionId` | `String` | No | Action/step ID (usually null — Compliance Service resolves) |
| `facilityId` | `String` | No | Healthcare facility FOSA ID |
| `data` | `Object` | Yes | FHIR R4 resource JSON (structurally validated) |

> **Note:** `null` fields are omitted from the JSON output (`@JsonInclude(NON_NULL)`).

---

## 4. Outbox Pattern

The Collector uses the **transactional outbox pattern** to guarantee at-least-once Kafka delivery:

```
HTTP Request → PostgreSQL (event_log, publish_status=PENDING) → Kafka Publish → Update (publish_status=PUBLISHED)
```

### How It Works

1. Normalized event is persisted to `event_log` with `publish_status = 'PENDING'`
2. `EventPublisher.publish()` sends the message to Kafka
3. On success: `publish_status` → `PUBLISHED`, Kafka metadata recorded (`kafka_topic`, `kafka_partition`, `kafka_offset`, `published_at`)
4. On failure: `publish_status` → `FAILED`, dead letter created
5. A scheduled retry (`@Scheduled`) scans for `PENDING`/`FAILED` records and retries

### Retry Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `cce.collector.outbox.retry-interval-seconds` | `30` | How often to scan for unpublished records |
| `cce.collector.outbox.retry-max-age-minutes` | `60` | Stop retrying records older than this |

### Retry Query

```sql
-- Records eligible for retry
SELECT * FROM event_log
WHERE publish_status IN ('PENDING', 'FAILED')
  AND received_at < now() - interval '30 seconds'   -- skip very recent (in-flight)
  AND received_at > now() - interval '60 minutes';   -- skip too old (max age)
```

---

## 5. Partitioning & Ordering

### Message Key

Every Kafka message uses `subject` (patient UPID) as the message key:

```java
kafkaTemplate.send(inboundTopic, event.getSubject(), event);
```

This guarantees:
- **Per-patient ordering** — all events for the same patient go to the same partition
- **Compliance Service can process events in order** — critical for protocol step matching

### Example Keys

| Patient | Key | Effect |
|---------|-----|--------|
| Marie-Claire KAYITESI | `260225-0002-5501` | All her events → same partition |
| Jean-Baptiste HABIMANA | `260225-0002-5502` | All his events → same partition |

---

## 6. Compliance Service Consumer Contract

The Compliance Service consumes from `cce.events.inbound` with these guarantees from the Collector:

| # | Guarantee | Description |
|---|-----------|-------------|
| 1 | `subject` always present | Used for patient protocol instance lookup |
| 2 | `type` follows `org.openphc.cce.<resource>` | Used for Tier 1 structural matching in `trigger_index` |
| 3 | `data` contains valid FHIR R4 resource | Parseable via HAPI FHIR `fhirContext.newJsonParser()` |
| 4 | Field names are camelCase | Matching the `CloudEventMessage` Java class |
| 5 | Kafka key = `subject` | Per-patient ordering |
| 6 | `correlationId` always present | For distributed tracing |
| 7 | Each message maps to an `event_log` row | Authoritative source of truth |

**What the Collector does NOT populate:**
- `protocolInstanceId` — Compliance Service resolves this from its `protocol_instance` table
- `protocolDefinitionId` — Compliance Service resolves this from its `protocol_definition` table
- `actionId` — Compliance Service determines the matching action/step

---

## 7. Sample Events

### 7.1 Visit Registration (Encounter)

**Kafka Key:** `260225-0002-5501`  
**Kafka Topic:** `cce.events.inbound`

```json
{
  "id": "evt-eb010001-0001-4000-8000-000000000001",
  "source": "rhie-mediator",
  "type": "org.openphc.cce.encounter",
  "specVersion": "1.0",
  "subject": "260225-0002-5501",
  "time": "2026-02-25T08:00:00Z",
  "dataContentType": "application/fhir+json",
  "correlationId": "corr-1343872c-636d-506f-b041-1e571d426932",
  "sourceEventId": "enc-visit-20260225-0001",
  "facilityId": "0002",
  "data": {
    "resourceType": "Encounter",
    "id": "enc-uuid-visit-kicukiro-001",
    "status": "in-progress",
    "class": {
      "system": "http://terminology.hl7.org/CodeSystem/v3-ActCode",
      "code": "AMB",
      "display": "ambulatory"
    },
    "type": [
      {
        "coding": [
          {
            "system": "http://openphc.org/encounter-types",
            "code": "VISIT_ENCOUNTER",
            "display": "Visit Encounter"
          }
        ]
      }
    ],
    "subject": {
      "reference": "Patient/260225-0002-5501",
      "display": "Marie-Claire KAYITESI"
    },
    "period": {
      "start": "2026-02-25T08:00:00Z"
    }
  }
}
```

### 7.2 Vital Signs (Observation)

**Kafka Key:** `260225-0002-5501`  
**Kafka Topic:** `cce.events.inbound`

```json
{
  "id": "evt-eb010001-0002-4000-8000-000000000002",
  "source": "rhie-mediator",
  "type": "org.openphc.cce.observation",
  "specVersion": "1.0",
  "subject": "260225-0002-5501",
  "time": "2026-02-25T08:05:00Z",
  "dataContentType": "application/fhir+json",
  "correlationId": "corr-1343872c-636d-506f-b041-1e571d426932",
  "sourceEventId": "obs-bp-20260225-0001",
  "facilityId": "0002",
  "data": {
    "resourceType": "Observation",
    "id": "obs-uuid-bp-001",
    "status": "final",
    "category": [
      {
        "coding": [
          {
            "system": "http://terminology.hl7.org/CodeSystem/observation-category",
            "code": "vital-signs",
            "display": "Vital Signs"
          }
        ]
      }
    ],
    "code": {
      "coding": [
        {
          "system": "http://loinc.org",
          "code": "85354-9",
          "display": "Blood Pressure"
        }
      ]
    },
    "subject": {
      "reference": "Patient/260225-0002-5501"
    },
    "effectiveDateTime": "2026-02-25T08:05:00Z",
    "component": [
      {
        "code": {
          "coding": [{ "system": "http://loinc.org", "code": "8480-6", "display": "Systolic" }]
        },
        "valueQuantity": { "value": 120, "unit": "mmHg" }
      },
      {
        "code": {
          "coding": [{ "system": "http://loinc.org", "code": "8462-4", "display": "Diastolic" }]
        },
        "valueQuantity": { "value": 80, "unit": "mmHg" }
      }
    ]
  }
}
```

### 7.3 Diagnosis (Condition)

**Kafka Key:** `260225-0002-5501`  
**Kafka Topic:** `cce.events.inbound`

```json
{
  "id": "evt-eb010001-0006-4000-8000-000000000006",
  "source": "rhie-mediator",
  "type": "org.openphc.cce.condition",
  "specVersion": "1.0",
  "subject": "260225-0002-5501",
  "time": "2026-02-25T08:25:00Z",
  "dataContentType": "application/fhir+json",
  "correlationId": "corr-1343872c-636d-506f-b041-1e571d426932",
  "sourceEventId": "cond-diag-20260225-0001",
  "facilityId": "0002",
  "data": {
    "resourceType": "Condition",
    "id": "cond-uuid-malaria-001",
    "clinicalStatus": {
      "coding": [{ "system": "http://terminology.hl7.org/CodeSystem/condition-clinical", "code": "active" }]
    },
    "verificationStatus": {
      "coding": [{ "system": "http://terminology.hl7.org/CodeSystem/condition-ver-status", "code": "confirmed" }]
    },
    "code": {
      "coding": [
        {
          "system": "http://hl7.org/fhir/sid/icd-10",
          "code": "B54",
          "display": "Unspecified malaria"
        }
      ]
    },
    "subject": {
      "reference": "Patient/260225-0002-5501"
    },
    "recordedDate": "2026-02-25T08:25:00Z"
  }
}
```

### 7.4 Medication Prescription (MedicationRequest)

**Kafka Key:** `260225-0002-5501`  
**Kafka Topic:** `cce.events.inbound`

```json
{
  "id": "evt-eb010001-0007-4000-8000-000000000007",
  "source": "rhie-mediator",
  "type": "org.openphc.cce.medicationrequest",
  "specVersion": "1.0",
  "subject": "260225-0002-5501",
  "time": "2026-02-25T08:30:00Z",
  "dataContentType": "application/fhir+json",
  "correlationId": "corr-1343872c-636d-506f-b041-1e571d426932",
  "sourceEventId": "rx-act-20260225-0001",
  "facilityId": "0002",
  "data": {
    "resourceType": "MedicationRequest",
    "id": "rx-uuid-act-001",
    "status": "active",
    "intent": "order",
    "medicationCodeableConcept": {
      "coding": [
        {
          "system": "http://www.nlm.nih.gov/research/umls/rxnorm",
          "code": "825466",
          "display": "Artemether/Lumefantrine 20/120mg"
        }
      ]
    },
    "subject": {
      "reference": "Patient/260225-0002-5501"
    },
    "authoredOn": "2026-02-25T08:30:00Z"
  }
}
```

---

## 8. Metrics

The Collector records these Kafka-related metrics via Micrometer:

| Metric | Tags | Description |
|--------|------|-------------|
| `cce.collector.events.received` | `source`, `status` | Events received by source and outcome (`accepted`, `rejected`, `duplicate`) |
| `cce.collector.ingestion.duration` | — | End-to-end ingestion latency (includes Kafka publish) |
| Spring Kafka `kafka.producer.*` | — | Standard Kafka producer metrics (record-send-rate, record-error-rate, etc.) |
