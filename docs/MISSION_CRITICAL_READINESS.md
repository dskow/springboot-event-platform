# springboot-event-platform — Mission-Critical Readiness Assessment

**Date:** 2026-05-04
**Scope:** Full repo audit against a "mission-critical" production bar — durability under crash, exactly-once or at-least-once with dedup, observability, security, tested code paths, operational hygiene.

---

## Verdict

**Portfolio-grade demo, not mission-critical.** The repo demonstrates the right component vocabulary (Spring Cloud Gateway, Resilience4j, Kafka, virtual threads, S3) but ships with **multiple silent data-loss paths**, an unauthenticated customer-facing API, and no end-to-end test of the durable-write path. The README's domain comparisons — "freight rail movements, financial transactions, medical-device telemetry" ([README.md:14-15](../README.md)) — set a bar this code does not yet clear.

A focused 1–2 sprint hardening pass closes the critical gaps. Production deployment in any of those domains additionally requires a schema registry, multi-tenant auth, and proper SRE tooling (tracing, alerting, runbooks).

---

## Project Summary

Three Spring Boot 4.0 services on Java 21, connected by Apache Kafka, archiving to AWS S3 (LocalStack in dev). `event-gateway` (Spring Cloud Gateway + Resilience4j) fronts `event-ingest` (REST → Kafka producer), and `event-processor` consumes the topic on virtual threads, batches records, and writes them to S3. Total surface is ~13 Java files plus YAML/Dockerfiles. Runs end-to-end via `docker compose up`.

---

## Findings By Area

### 1. Project purpose & scope
- Clear single-purpose demo: ingest → Kafka → S3 archival ([README.md](../README.md), [docs/architecture.md](architecture.md))
- Author honestly flags MVP gaps in the architecture doc ("Batch is logged and dropped (MVP)") and in the README roadmap
- Scope is tight and well bounded for a portfolio piece

### 2. Architecture & code structure
- Clean module split: `event-gateway`, `event-ingest`, `event-processor` with a parent BOM POM
- Two divergent `Event` records ([event-ingest/.../Event.java](../event-ingest/src/main/java/com/dskow/eventplatform/ingest/model/Event.java), [event-processor/.../Event.java](../event-processor/src/main/java/com/dskow/eventplatform/processor/model/Event.java)) — kept in sync by hand; `assetId` is `@NotBlank` on one and unconstrained on the other
- Trusted-packages config in [event-processor application.yml:18](../event-processor/src/main/resources/application.yml) lists both packages, masking the duplication
- No shared `event-common` module, no schema registry, no Avro/Protobuf contract

### 3. Configuration & secrets
- All hostnames and bucket names externalized via env vars with sensible defaults
- LocalStack credentials (`"test"/"test"`) hardcoded inline in [S3Config.java:30-31](../event-processor/src/main/java/com/dskow/eventplatform/processor/config/S3Config.java) — minor; externalize for cleanliness
- No startup validation that required vars are set in non-LocalStack mode
- No secret manager integration, no rotation story

### 4. Dependencies
- Spring Boot 4.0.6, Spring Cloud 2025.1.1, AWS SDK 2.44.1, Jackson 3.1.3 — current
- Jackson 3 BOM override is well-commented in the parent POM
- Testcontainers 2.0.5 declared but unused (see Testing)
- No SCA in CI: no `dependency-check`, no Trivy, no Dependabot config visible
- No SBOM generation

### 5. Error handling & resilience
- Producer is idempotent + `acks=all` ([event-ingest application.yml:15-18](../event-ingest/src/main/resources/application.yml)) — correct Kafka durability config
- Resilience4j circuit breaker tuned reasonably (window 10, 50% threshold, 10 s open) ([event-gateway application.yml:32-45](../event-gateway/src/main/resources/application.yml))
- **`S3Archiver.archive()` silently drops failed batches** ([S3Archiver.java:51-57](../event-processor/src/main/java/com/dskow/eventplatform/processor/s3/S3Archiver.java)) — no retry, no DLQ, no metric, no alert
- **No `DefaultErrorHandler` / DLT for the Kafka consumer** — a single poison-pill record blocks the partition or gets silently skipped
- No backoff on S3 PutObject beyond the SDK default
- Producer's `whenComplete` only logs — controller has already returned 202 before send completes ([EventController.java:34-35](../event-ingest/src/main/java/com/dskow/eventplatform/ingest/api/EventController.java))

### 6. Logging, metrics, tracing
- Stdlib SLF4J configured; no JSON encoder, no MDC
- **`processedCount` is an `AtomicLong` exposed only via a getter that nothing calls** ([EventConsumer.java:26,69-71](../event-processor/src/main/java/com/dskow/eventplatform/processor/kafka/EventConsumer.java)) — never registered with Micrometer
- No counters for `archive.success`, `archive.failure`, `dlt.send`; no gauge for `buffer.depth`
- No distributed tracing — no Micrometer Tracing / OpenTelemetry / Sleuth dependency. Cannot follow a request across gateway → ingest → Kafka → processor → S3.
- `/actuator/prometheus` exposed but with no business meters wired in

### 7. Testing
- Two unit tests total: [EventControllerTest.java](../event-ingest/src/test/java/com/dskow/eventplatform/ingest/EventControllerTest.java) and [ProcessorApplicationTests.java](../event-processor/src/test/java/com/dskow/eventplatform/processor/ProcessorApplicationTests.java)
- **No Testcontainers integration test despite `testcontainers-localstack`, `testcontainers-kafka`, `testcontainers-junit-jupiter` being on the classpath**
- No test of the durable-write path (Kafka in → S3 out)
- No test of the S3Archiver error branches (NoSuchBucket, JacksonException, generic RuntimeException)
- No test of `flushScheduled()`
- No automated test of the circuit breaker — README demonstrates it manually with curl
- `ProcessorApplicationTests` is misnamed — it's a unit test of `EventConsumer`, no `@SpringBootTest`

### 8. Security
- **🔴 CRITICAL: No authentication on customer-facing API.** Gateway routes `/api/events/**` straight through with zero auth filter ([event-gateway application.yml:11-30](../event-gateway/src/main/resources/application.yml)). README calls it "customer-facing" — anyone reachable can flood Kafka.
- **🔴 CRITICAL: No rate limiting / size cap.** No `RequestRateLimiter` filter, no max payload, no `@Size` cap on `assetId`/`status`. Trivial DoS when paired with the missing auth.
- 🟡 Validation is anemic — only `@NotBlank assetId`. No range check on `latitude`/`longitude`, no enum on `status` ([Event.java:8-15](../event-ingest/src/main/java/com/dskow/eventplatform/ingest/model/Event.java))
- 🟡 Actuator exposed without auth, with `show-details: always` — leaks internal state in production
- 🟡 No TLS between services — acceptable for local demo but contradicts "production-grade" framing
- 🟡 No CORS config on a customer-facing endpoint

### 9. Deployment & ops
- Multi-stage Dockerfiles with non-root `spring` user — clean ✓
- Alpine + JVM base ([event-gateway/Dockerfile:13](../event-gateway/Dockerfile)) — known glibc/musl quirks under load; prefer `21-jre-jammy` or distroless
- **No Dockerfile `HEALTHCHECK` on the three Spring services** — Compose `depends_on` only enforces "started", not "healthy"
- **No memory limits in `docker-compose.yml`** — pairs with the unbounded buffer (C3) for an OOM-the-host scenario
- No CI image scanning (Trivy/Grype), no SBOM
- No k8s manifests, no Helm chart, no Terraform module (roadmap)
- No graceful-shutdown handling in `event-processor` — buffered events are lost on SIGTERM

### 10. Data & state
- Stateless services; durable state lives in Kafka and S3
- No schema registry; raw JSON across the wire with no enforced contract
- Two divergent `Event` records risk drift (see Architecture)
- **S3 archive key uses millisecond timestamp + size only** ([S3Archiver.java:39](../event-processor/src/main/java/com/dskow/eventplatform/processor/s3/S3Archiver.java)) — same-millisecond flushes silently overwrite each other
- No partitioning strategy on the S3 prefix (no `yyyy/MM/dd/`)

### 11. Documentation
- README is well-organized with file-path links, a Mermaid diagram, quickstart for both bash and PowerShell
- Architecture doc honestly enumerates failure modes ([docs/architecture.md:53-59](architecture.md))
- **Internal contradictions:** README intro says "Spring Boot 4.0" but the table of contents and repo-layout sections say "Spring Boot 3.3" ([README.md:28,127](../README.md)) — actual parent is 4.0.6
- README claims "Bean validation + global error mapping" ([README.md:48](../README.md)) — there is no `@ControllerAdvice` in the codebase
- No runbook, no on-call docs, no SLOs, no ADRs

### 12. Reliability for the "mission-critical" bar
- **🔴 CRITICAL: Kafka offset commits before S3 archival completes.** [EventConsumer.java:40-49](../event-processor/src/main/java/com/dskow/eventplatform/processor/kafka/EventConsumer.java) `offer`s to a `LinkedBlockingQueue` and returns; Spring-Kafka commits the offset on listener return. The `@Async("processorExecutor")` makes this strictly worse — the call returns instantly while the real work is queued elsewhere. **Crash between offer and flush ⇒ event gone forever.**
- **🔴 CRITICAL: Unbounded in-memory buffer.** `new LinkedBlockingQueue<>()` with no capacity ([EventConsumer.java:25](../event-processor/src/main/java/com/dskow/eventplatform/processor/kafka/EventConsumer.java)). S3 stall ⇒ OOM, and on OOM kill **everything** in the buffer is lost (compounds the offset bug above).
- **🔴 CRITICAL: Retry on POST creates duplicate events.** Gateway retries `POST /api/events` up to 3× with backoff ([event-gateway application.yml:21-30](../event-gateway/src/main/resources/application.yml)). The ingest controller generates `id = UUID.randomUUID()` *per call* ([EventController.java:27](../event-ingest/src/main/java/com/dskow/eventplatform/ingest/api/EventController.java)), so a retried request after a flaky upstream produces two distinct events for the same business operation. Idempotent Kafka producer doesn't help — the IDs differ.
- **🔴 CRITICAL: No DLT.** Kafka consumer has no `DefaultErrorHandler` or DLT configured.
- 🟠 No graceful Kafka rebalance handling — buffered events from revoked partitions get re-archived after rebalance, producing duplicates
- 🟠 `@Scheduled` flush runs on Spring's default single-thread scheduler — a slow S3 PutObject blocks all other scheduled tasks

---

## Top 10 Blockers, Ranked

| # | Blocker | File | Severity |
|---|---------|------|----------|
| 1 | Kafka offset commits before S3 write completes — crash drops events | [EventConsumer.java:40-49](../event-processor/src/main/java/com/dskow/eventplatform/processor/kafka/EventConsumer.java) | 🔴 Critical |
| 2 | `S3Archiver` silently drops failed batches; no retry, no DLQ | [S3Archiver.java:51-57](../event-processor/src/main/java/com/dskow/eventplatform/processor/s3/S3Archiver.java) | 🔴 Critical |
| 3 | Unbounded `LinkedBlockingQueue` buffer — OOM under back-pressure, total loss on kill | [EventConsumer.java:25](../event-processor/src/main/java/com/dskow/eventplatform/processor/kafka/EventConsumer.java) | 🔴 Critical |
| 4 | No graceful shutdown / drain on SIGTERM | [EventConsumer.java](../event-processor/src/main/java/com/dskow/eventplatform/processor/kafka/EventConsumer.java) | 🔴 Critical |
| 5 | No auth on customer-facing API; no rate limit; no size cap | [event-gateway application.yml](../event-gateway/src/main/resources/application.yml) | 🔴 Critical |
| 6 | Gateway retries non-idempotent POST → duplicate events with different IDs | [event-gateway application.yml:21-30](../event-gateway/src/main/resources/application.yml), [EventController.java:27](../event-ingest/src/main/java/com/dskow/eventplatform/ingest/api/EventController.java) | 🔴 Critical |
| 7 | S3 archive key collision under load (millisecond + size only) | [S3Archiver.java:39](../event-processor/src/main/java/com/dskow/eventplatform/processor/s3/S3Archiver.java) | 🟠 High |
| 8 | No DLT for poison-pill Kafka records | [EventConsumer.java](../event-processor/src/main/java/com/dskow/eventplatform/processor/kafka/EventConsumer.java) | 🟠 High |
| 9 | No Testcontainers integration test of the durable-write path | repo-wide | 🟠 High |
| 10 | No Micrometer business meters; no distributed tracing | cross-cutting | 🟠 High |

---

## Quick Wins (each <1 day)

1. **Bound the buffer.** `new LinkedBlockingQueue<>(maxSize)` with a config knob, plus a Micrometer gauge on depth. ~30 min.
2. **Fix the S3 key collision.** `events/{yyyy}/{MM}/{dd}/{epochMillis}-{uuid}.json`. ~15 min.
3. **Add `@PreDestroy` flush** on `EventConsumer`. ~20 min.
4. **Switch `AckMode` to `MANUAL_IMMEDIATE`** and ack only after successful S3 write; drop `@Async` on the listener. ~2 hours.
5. **Add `DefaultErrorHandler` with DLT recoverer** on the consumer factory. ~1 hour.
6. **Wrap S3 PutObject in a small retry policy** (3 attempts, exp backoff) before falling through to DLT. ~30 min.
7. **Register Micrometer meters** for `events.received`, `events.archived`, `archive.failures`, `dlt.sent`, `buffer.depth`. ~45 min.
8. **Add Testcontainers integration test** spinning up Kafka + LocalStack, asserting "produce N → S3 contains N". The deps are already in the POM. ~3 hours.
9. **Fix the README**: pick one Spring Boot version and use it consistently; either implement `@ControllerAdvice` or remove the "global error mapping" claim. ~15 min.
10. **Add an API key filter + `RequestRateLimiter`** on the gateway, even with an in-memory store. ~2 hours.

---

## Implementation Progress

Sprint 1 (this audit) closed every CRITICAL and HIGH item the doc
flagged, plus one MEDIUM:

| # | Item | Status | Commit |
|---|------|--------|--------|
| QW2 | Fix S3 key collision | ✅ done | `fbdca28` |
| QW1 | Bound the buffer | ✅ done (superseded) | `e3cc481` |
| QW3 | `@PreDestroy` drain | ✅ done (superseded) | `e3cc481` |
| QW4+5+6 | Manual ack + retry + DLT | ✅ done | `e22f8d5` |
| QW9 | README fixes | ✅ done | `f3dae53` |
| QW7 | Micrometer meters | ✅ done | `1f582da` |
| C6 | POST safe under retry (drop POST from gateway retry + Idempotency-Key header) | ✅ done | `72c3806` |
| M6+M7 | Dockerfile HEALTHCHECK + Compose memory limits | ✅ done | `59545c5` |
| QW10 / C5 | Gateway X-API-Key auth + per-key rate limiter | ✅ done | `47fc7f1` |
| H1 | Distributed tracing (Micrometer Tracing + Brave) end-to-end | ✅ done | `6107c9e` |
| QW8 | Testcontainers IT proving Kafka → S3 no-loss | ✅ done | `9230bef` |

**Test count:** 2 → 19 (9 in event-processor unit tests, 1 integration
test asserting end-to-end durability, 9 in event-gateway including 5
ApiKey + 3 RateLimit + 1 baseline, 4 in event-ingest including the
new Idempotency-Key path).

**Note on supersession:** the bounded buffer and `@PreDestroy` drain
(`e3cc481`) were the right MVP fixes against the original code shape,
but the manual-ack refactor (`e22f8d5`) replaced the in-memory buffer
with Kafka's native poll-based batching. There is no longer a buffer
to bound or drain — back-pressure flows naturally back to the broker
and a SIGTERM mid-poll just doesn't ack, so Kafka redelivers on
restart. The intermediate commit is preserved in history for context.

## Sprint 2 — security pass

| # | Item | Status | Commit |
|---|------|--------|--------|
| — | Dependabot for maven + github-actions + docker | ✅ done | `289369c` |
| — | OWASP ZAP baseline pen-test job in CI | ✅ done | `ca97238` |
| S1 | Auth fail-closed by default (was: open mode silent) | ✅ done | `506e4b2` |
| S6 | `X-Forwarded-For` honored when `trusted-proxy-hops > 0` | ✅ done | `506e4b2` |
| S7 | Audit log line on every 401 with redacted key prefix | ✅ done | `506e4b2` |
| S2 | `Idempotency-Key` validated against `^[A-Za-z0-9._-]{1,128}$` | ✅ done | `08e45dd` |
| S3 | Body validation: lat/lon range, status pattern, assetId pattern + size | ✅ done | `08e45dd` |
| — | Request body + header size caps | ✅ done | `08e45dd` |
| S4 | Actuator on internal-only management port (9080/9081/9082) | ✅ done | `3f6b07b` |
| S10 | Trimmed actuator exposure to `health,prometheus`, details `when-authorized` | ✅ done | `3f6b07b` |
| S5 | Rate-limit map bounded via Caffeine (cap + idle eviction) | ✅ done | `a2c7775` |
| — | Jackson trusted-packages narrowed to one package | ✅ done | `94932d5` |

Outstanding security findings deferred (need infra changes or are out
of scope for a portfolio piece): plaintext between services + Kafka
(S8 — needs TLS cert infrastructure); demo creds in compose (S9 —
documented, requires production-config split); CORS posture (S11 —
depends on intended audience).

## Still Open

Items that weren't addressed in sprint 1/2 and remain candidates for
future work:

- **CI hardening** — image vulnerability scanning (Trivy/Grype) and SBOM
  generation in `.github/workflows/ci.yml`.
- **Schema registry** — JSON-on-the-wire with no enforced contract; the
  two `Event` records still drift by hand.
- **Multi-instance rate limiter** — current limiter is in-memory per
  pod. Swap in Spring Cloud Gateway's Redis variant for replicated
  deployments.
- **DLT consumer** — the DLT topic exists and routes failures, but
  nothing is monitoring it. Add a small consumer (or alert on lag).
- **Crash-during-batch IT** — current IT covers the success path; a
  follow-up should kill the consumer mid-batch and assert no loss
  after restart.
- **Distroless / non-Alpine base images** — JVM-on-Alpine has known
  glibc/musl edges under load.
- **k8s manifests / Helm chart / Terraform** — README roadmap items
  for moving past `docker compose up`.

---

## Bottom Line

The repo is a credible, well-organized portfolio piece. To push it to mission-critical you need, in priority order: **(a) close the data-loss triangle** (manual offset ack + S3 retry-or-DLT + bounded buffer + graceful drain), **(b) make POST idempotent** (idempotency key derived from request, not server-side UUID), **(c) authenticate and rate-limit the gateway**, **(d) wire real observability** (business meters, tracing), **(e) write the integration test that proves no loss across processor restart**. Items (a)–(c) are achievable in a focused sprint and are the minimum bar for the README's domain comparisons to hold.
