# Architecture

## Component diagram

```mermaid
flowchart LR
    Client[Client / curl / load test] -->|POST /api/events| Gateway

    subgraph Gateway[event-gateway :8080]
      direction TB
      Routes[Spring Cloud Gateway routes]
      CB[Resilience4j circuit breaker + retry]
      Fallback[/fallback/events/]
      Routes --> CB
      CB -.open.-> Fallback
    end

    Gateway -->|forwarded request| Ingest

    subgraph Ingest[event-ingest :8081]
      direction TB
      Controller[EventController]
      Producer[KafkaTemplate producer]
      Controller --> Producer
    end

    Producer -->|topic: events| Kafka[(Apache Kafka)]

    Kafka -->|@KafkaListener| Consumer

    subgraph Processor[event-processor :8082]
      direction TB
      Consumer[EventConsumer]
      VT[Virtual-thread executor]
      Buffer[BlockingQueue batch buffer]
      Archiver[S3Archiver]
      Consumer --> VT
      VT --> Buffer
      Buffer --> Archiver
    end

    Archiver -->|PutObject| S3[(AWS S3 / LocalStack)]
```

## Request flow

1. Client posts a JSON event to `POST http://localhost:8080/api/events`
2. **event-gateway** matches the `/api/events/**` route, applies the `CircuitBreaker` and `Retry` filters, and forwards to **event-ingest**
3. **event-ingest** validates the payload, stamps `id` and `timestamp` if missing, and asynchronously publishes to Kafka topic `events`
4. **event-processor** consumes records on a virtual-thread executor, batches them into a `BlockingQueue`, and flushes batches to S3 either when the buffer fills (`app.archive-batch-size`) or on a scheduled interval (`app.archive-flush-ms`)

## Failure modes the design addresses

| Failure | Mitigation |
|---|---|
| `event-ingest` is down or slow | Gateway retries with exponential backoff; circuit breaker opens after sustained failure and serves `/fallback/events` |
| Kafka briefly unavailable | Producer's idempotent send retries internally; consumer resumes from committed offset on reconnect |
| S3 transient failure | Batch is logged and dropped (MVP); next batch proceeds. **Production hardening:** dead-letter topic + DLQ replay |
| Slow S3 PutObject blocks consumer | Each record runs on its own virtual thread, so blocking I/O does not stall the listener container |

## Tunable knobs

| Property | Default | Purpose |
|---|---|---|
| `app.archive-batch-size` | 100 | Events per S3 object |
| `app.archive-flush-ms` | 5000 | Max age of a partial batch before flush |
| `resilience4j.circuitbreaker.instances.ingestCB.failureRateThreshold` | 50 | % failures before circuit opens |
| `resilience4j.circuitbreaker.instances.ingestCB.waitDurationInOpenState` | 10s | How long the circuit stays open |
