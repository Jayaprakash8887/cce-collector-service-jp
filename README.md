# CCE Collector Service

The **Collector Service** is the event ingestion gateway of the Care Coordination Engine (CCE). It receives clinical events from external EHR/RHIE systems, validates and normalizes them into CloudEvents v1.0 envelopes with FHIR R4 payloads, and publishes them to Kafka for downstream processing by the Compliance Service.

## Architecture Overview

```
External Systems (eBUZIMA, SmartCare, CHW Apps)
        │
        ▼
openHIM / RHIE Mediator Layer
        │  HTTP POST (CloudEvents)
        ▼
★ CCE Collector Service ★
  Validate → Normalize → Deduplicate → Publish to Kafka
        │
        ▼  Kafka: cce.events.inbound
CCE Compliance Service
```

## Technology Stack

| Component | Technology |
|-----------|-----------|
| Language | Java 21 |
| Framework | Spring Boot 3.4.x |
| Build | Maven |
| Database | PostgreSQL 16 |
| Message Broker | Apache Kafka 3.7+ (KRaft) |
| FHIR | HAPI FHIR 7.4.0 |
| DB Migration | Flyway |

## Quick Start

### Prerequisites

- Java 21+
- Maven 3.9+
- Docker & Docker Compose

### 1. Start Infrastructure

```bash
docker-compose up -d
```

This starts PostgreSQL (port 5433) and Kafka (port 9092).

### 2. Build & Run

```bash
mvn clean package -DskipTests
java -jar target/cce-collector-service-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
```

Or run with Maven:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

### 3. Test Event Ingestion

```bash
curl -X POST http://localhost:8080/v1/events \
  -H "Content-Type: application/json" \
  -d '{
    "specversion": "1.0",
    "id": "test-event-001",
    "source": "rhie-mediator",
    "type": "org.openphc.cce.encounter",
    "subject": "260225-0002-5501",
    "time": "2026-02-25T08:00:00Z",
    "datacontenttype": "application/fhir+json",
    "facilityid": "0002",
    "correlationid": "corr-test-001",
    "data": {
      "resourceType": "Encounter",
      "id": "enc-test-001",
      "status": "in-progress",
      "class": {
        "system": "http://terminology.hl7.org/CodeSystem/v3-ActCode",
        "code": "AMB"
      },
      "subject": {
        "reference": "Patient/260225-0002-5501"
      }
    }
  }'
```

## API Endpoints

### Event Ingestion

| Method | Path | Description |
|--------|------|-------------|
| POST | `/v1/events` | Ingest a single CloudEvents event |

### Dead Letter Management

| Method | Path | Description |
|--------|------|-------------|
| GET | `/v1/dead-letters` | List dead-letter events |
| GET | `/v1/dead-letters/{id}` | Get specific dead-letter |
| POST | `/v1/dead-letters/{id}/retry` | Re-process a dead-letter |

### Health & Observability

| Path | Description |
|------|-------------|
| `/actuator/health` | Health check (Kafka, DB) |
| `/actuator/health/liveness` | Kubernetes liveness probe |
| `/actuator/health/readiness` | Kubernetes readiness probe |
| `/actuator/prometheus` | Prometheus metrics |

## Processing Flow

1. **Receive** HTTP POST with CloudEvents envelope
2. **Validate** CloudEvents envelope (specversion, id, source, type, subject, data)
3. **Deduplicate** via PostgreSQL with configurable lookback window + DB unique constraints
4. **Persist** raw event to `inbound_event` table (audit trail)
5. **Normalize** event type, correlation ID, timestamps
6. **Validate FHIR** payload (if `datacontenttype = application/fhir+json`)
7. **Persist** normalized event to `event_log` table (outbox)
8. **Publish** to Kafka topic `cce.events.inbound`
9. **Return** ingestion receipt

## Database Tables

- `inbound_event` — Raw request audit log
- `event_log` — Normalized event outbox (partitioned monthly)
- `dead_letter_event` — Rejected/failed events

## Configuration

Key environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `DB_HOST` | localhost | PostgreSQL host |
| `DB_PORT` | 5433 | PostgreSQL port |
| `DB_NAME` | cce_collector | Database name |
| `DB_USER` | cce_collector | Database user |
| `DB_PASSWORD` | cce_collector | Database password |
| `KAFKA_BROKERS` | localhost:9092 | Kafka brokers |

## Project Structure

```
src/main/java/org/openphc/cce/collector/
├── CollectorServiceApplication.java
├── config/        — Spring configuration (Kafka, FHIR, Security, JPA, Web)
├── domain/
│   ├── model/     — JPA entities and enums
│   └── repository/ — Spring Data JPA repositories
├── service/       — Business logic (ingestion, validation, normalization, dedup, publish)
├── kafka/         — Kafka producer
├── api/
│   ├── controller/ — REST controllers
│   ├── dto/       — Request/response DTOs
│   └── exception/ — Exception handlers
└── fhir/          — FHIR R4 parsing and validation
```
