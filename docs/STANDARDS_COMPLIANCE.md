# springboot-event-platform — Industry-Standards Compliance Assessment

**Date:** 2026-05-05
**Companion to:** [`MISSION_CRITICAL_READINESS.md`](MISSION_CRITICAL_READINESS.md)

The readiness doc audits this repo against an internal "production
durability" bar. This doc applies a different lens — published
industry / standards-body specifications — and tracks where each one
is met, partial, or open.

---

## Scope

The README compares the domain to "freight rail movements, financial
transactions, or medical-device telemetry"
([README.md](../README.md)). That framing pulls in PCI-DSS / HIPAA /
FDA-style bars *if* real data of those kinds were processed. No PII,
PHI, or PAN is touched here, so the in-scope standards are those that
apply to a generic public-internet ingest API + Kafka pipeline + S3
archive:

- [OWASP API Security Top 10 (2023)](https://owasp.org/API-Security/editions/2023/en/0x11-t10/)
- [OWASP ASVS 4.0.3](https://owasp.org/www-project-application-security-verification-standard/) (selected controls)
- [OWASP Top 10 CI/CD Security Risks (2022)](https://owasp.org/www-project-top-10-ci-cd-security-risks/)
- [CIS Docker Benchmark](https://www.cisecurity.org/benchmark/docker) (image-level controls only)
- [NIST SP 800-218 SSDF v1.1](https://csrc.nist.gov/pubs/sp/800/218/final)
- [CISA Secure-by-Design pledge](https://www.cisa.gov/securebydesign/pledge) (2024)
- [SLSA v1.0](https://slsa.dev/spec/v1.0/) (build provenance)
- [The Twelve-Factor App](https://12factor.net/)
- [W3C Trace Context](https://www.w3.org/TR/trace-context/) + [OpenTelemetry semantic conventions](https://opentelemetry.io/docs/specs/semconv/)
- [IETF draft-ietf-httpapi-idempotency-key-header](https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/)
- [RFC 9457 — Problem Details for HTTP APIs](https://www.rfc-editor.org/rfc/rfc9457.html)

Out of scope (no triggering data type or deployment surface): PCI-DSS,
HIPAA, GDPR, FDA 21 CFR Part 11, ISO/IEC 27001 control set.

---

## [OWASP API Security Top 10 (2023)](https://owasp.org/API-Security/editions/2023/en/0x11-t10/)

| Item | Status | Evidence |
|---|---|---|
| [API1 — Broken Object Level Authorization](https://owasp.org/API-Security/editions/2023/en/0xa1-broken-object-level-authorization/) | N/A | No object-level resources; ingest-only POST. |
| [API2 — Broken Authentication](https://owasp.org/API-Security/editions/2023/en/0xa2-broken-authentication/) | ✅ | API-key required, fail-closed on empty allowlist ([`ApiKeyAuthFilter.java`](../event-gateway/src/main/java/com/dskow/eventplatform/gateway/security/ApiKeyAuthFilter.java)). |
| [API3 — Broken Object Property Level Auth](https://owasp.org/API-Security/editions/2023/en/0xa3-broken-object-property-level-authorization/) | N/A | Single flat record. |
| [API4 — Unrestricted Resource Consumption](https://owasp.org/API-Security/editions/2023/en/0xa4-unrestricted-resource-consumption/) | 🟡 | Token-bucket per-key + 64 KiB request-size cap. **Gap:** no per-IP limit when key supplied; no concurrency cap. |
| [API5 — Broken Function Level Authorization](https://owasp.org/API-Security/editions/2023/en/0xa5-broken-function-level-authorization/) | ✅ | Single function endpoint; only `/actuator` and `/fallback` exempt; actuator on internal-only port. |
| [API6 — Sensitive Business Flows](https://owasp.org/API-Security/editions/2023/en/0xa6-unrestricted-access-to-sensitive-business-flows/) | N/A | |
| [API7 — SSRF](https://owasp.org/API-Security/editions/2023/en/0xa7-server-side-request-forgery/) | ✅ | No outbound URL based on user input. |
| [API8 — Security Misconfiguration](https://owasp.org/API-Security/editions/2023/en/0xa8-security-misconfiguration/) | 🟡 | Actuator hardened. **Gap:** no security headers (HSTS, CSP, X-Content-Type-Options); plaintext between services. |
| [API9 — Improper Inventory Management](https://owasp.org/API-Security/editions/2023/en/0xa9-improper-inventory-management/) | 🟡 | OpenAPI descriptor now published at `/v3/api-docs`. **Gap:** no URL versioning. |
| [API10 — Unsafe Consumption of APIs](https://owasp.org/API-Security/editions/2023/en/0xaa-unsafe-consumption-of-apis/) | ✅ | Authenticated SDK clients for Kafka and S3. |

## [OWASP ASVS 4.0.3](https://owasp.org/www-project-application-security-verification-standard/) (selected)

| Section | Status | Notes |
|---|---|---|
| V1 Architecture / Threat Modeling | 🟡 | Failure-mode discussion in [architecture.md](architecture.md); no STRIDE / attack-tree. |
| V2 Authentication | 🟡 | Shared bearer secret with no rotation mechanism, no key-id concept. |
| V4 Access Control | ✅ | Single-role surface, default-deny. |
| V5 Validation, Sanitization, Encoding | ✅ | Bean-validation on `Event`; idempotency-key whitelist; explicit `Double.isFinite` defense. |
| V7 Error Handling and Logging | ✅ | Auth-reject audit log with redacted prefix; RFC 9457 Problem Details on validation failures. |
| V8 Data Protection | 🟢 | No PII/PHI/PAN in scope. |
| V9 Communications | 🔴 | Plaintext between services and to Kafka. Deferred — needs cert infrastructure. |
| V11 Business Logic | ✅ | Idempotency-Key plumbed; rate-limit token bucket. |
| V13 API and Web Service | ✅ | OpenAPI spec; correct REST verbs; fallback narrowed. |
| V14 Configuration | 🟡 | LocalStack creds inline ([S3Config.java:30-31](../event-processor/src/main/java/com/dskow/eventplatform/processor/config/S3Config.java)) — dev-only, documented. |

## [OWASP Top 10 CI/CD Security Risks (2022)](https://owasp.org/www-project-top-10-ci-cd-security-risks/)

| Risk | Status | Notes |
|---|---|---|
| [CICD-SEC-1 Insufficient Flow Control](https://owasp.org/www-project-top-10-ci-cd-security-risks/CICD-SEC-01-Insufficient-Flow-Control-Mechanisms) | ✅ | Single workflow on push/PR to main. |
| [CICD-SEC-2 Inadequate Identity & Access](https://owasp.org/www-project-top-10-ci-cd-security-risks/CICD-SEC-02-Inadequate-Identity-and-Access-Management) | ✅ | Least-privilege workflow tokens. |
| [CICD-SEC-3 Dependency Chain Abuse](https://owasp.org/www-project-top-10-ci-cd-security-risks/CICD-SEC-03-Dependency-Chain-Abuse) | ✅ | Dependabot + Trivy fs scan + CodeQL SAST. |
| [CICD-SEC-4 Poisoned Pipeline Execution](https://owasp.org/www-project-top-10-ci-cd-security-risks/CICD-SEC-04-Poisoned-Pipeline-Execution) | ✅ | No untrusted code execution paths. |
| [CICD-SEC-5 Insufficient PBAC](https://owasp.org/www-project-top-10-ci-cd-security-risks/CICD-SEC-05-Insufficient-PBAC) | N/A | Solo author repo. |
| [CICD-SEC-6 Insufficient Credential Hygiene](https://owasp.org/www-project-top-10-ci-cd-security-risks/CICD-SEC-06-Insufficient-Credential-Hygiene) | ✅ | No secrets in workflow. |
| [CICD-SEC-7 Insecure System Configuration](https://owasp.org/www-project-top-10-ci-cd-security-risks/CICD-SEC-07-Insecure-System-Configuration) | 🟡 | Actions pinned by major version; commit-SHA pinning is a future hardening step. |
| [CICD-SEC-8 Ungoverned 3rd-Party Services](https://owasp.org/www-project-top-10-ci-cd-security-risks/CICD-SEC-08-Ungoverned-Usage-of-3rd-Party-Services) | ✅ | Reputable actions only. |
| [CICD-SEC-9 Improper Artifact Integrity](https://owasp.org/www-project-top-10-ci-cd-security-risks/CICD-SEC-09-Improper-Artifact-Integrity-Validation) | ✅ | CycloneDX SBOM generated; Cosign keyless signing on tagged releases. |
| [CICD-SEC-10 Insufficient Logging](https://owasp.org/www-project-top-10-ci-cd-security-risks/CICD-SEC-10-Insufficient-Logging-and-Visibility) | 🟡 | DAST + SAST artifacts surfaced; no aggregator (ASOC). |

## [CIS Docker Benchmark](https://www.cisecurity.org/benchmark/docker) (image-level)

Same pattern across all three Dockerfiles.

| Control | Status | Notes |
|---|---|---|
| 4.1 Non-root user | ✅ | `spring:spring`. |
| 4.2 Trusted base images | ✅ | Eclipse Temurin pinned by digest. |
| 4.3 No unnecessary packages | 🟡 | `curl` for HEALTHCHECK only — distroless candidate for future. |
| 4.6 HEALTHCHECK present | ✅ | Status-code + JSON-body assertion. |
| 4.7 Update-only instructions | ✅ | `apk add --no-cache`. |
| 4.9 COPY not ADD | ✅ | |
| 4.10 No secrets in image | ✅ | All via env. |
| Multi-stage build | ✅ | Build / runtime split. |

## [NIST SP 800-218 SSDF v1.1](https://csrc.nist.gov/pubs/sp/800/218/final)

| Practice | Status | Notes |
|---|---|---|
| PO Prepare | 🟡 | Single-author repo. |
| PS.1 Protect Code | ✅ | Public Git. |
| PS.2 Verifying Integrity | ✅ | Cosign signing on release tags; SBOM attached. |
| PS.3 Archive Releases | 🟡 | Tagging convention not yet established. |
| PW.4 Reuse Secure Software | ✅ | Spring Boot 4.0.6, Spring Cloud 2025.1.1, AWS SDK 2.44.1. |
| PW.5 Secure Coding | ✅ | Bean validation, isFinite, regex whitelists, log-injection-safe redact. |
| PW.7 Code Analysis | ✅ | CodeQL SAST in CI. |
| PW.8 Test Executable Code | ✅ | 19 tests including Testcontainers IT. |
| PW.9 Secure Defaults | ✅ | Auth fail-closed, idempotent retry, manual offset ack, narrowed Jackson trusted-packages. |
| RV Vulnerability Response | ✅ | [SECURITY.md](../SECURITY.md) defines disclosure path. |

## [CISA Secure-by-Design Pledge](https://www.cisa.gov/securebydesign/pledge) (2024)

| Pillar | Status | Notes |
|---|---|---|
| 1 MFA | N/A | No human authn surface. |
| 2 Default passwords | ✅ | Fail-closed on empty allowlist. |
| 3 Reduce vuln classes | ✅ | Memory-safe Java; deserialization narrowed; SQLi N/A. |
| 4 Security patches | ✅ | Dependabot weekly. |
| 5 Disclosure policy | ✅ | [SECURITY.md](../SECURITY.md). |
| 6 CVE transparency | N/A | No CVEs filed. |
| 7 Evidence of intrusions | 🟡 | Auth-reject audit log; rate-limit rejections silent. |

## [SLSA v1.0](https://slsa.dev/spec/v1.0/)

**Current level: L2** once the release workflow runs. L1 satisfied by
the build provenance attestation; L2 satisfied by GitHub-hosted runner
+ keyless OIDC signing of the provenance and image.

To reach L3: dedicated hardened runners (out of scope for a
portfolio repo).

## [Twelve-Factor App](https://12factor.net/)

11/12. Logging now JSON-structured with MDC trace correlation; the
remaining gap is dev/prod parity (LocalStack ≠ AWS S3 semantics under
load).

## [W3C Trace Context](https://www.w3.org/TR/trace-context/) + [OTel](https://opentelemetry.io/docs/specs/semconv/)

- `traceparent` propagation end-to-end via Micrometer Tracing + Brave.
- Kafka producer `template.observation-enabled` + consumer
  `listener.observation-enabled` link spans across the broker hop.
- Business meters: `events.archived`, `archive.batches`,
  `archive.failures`, `kafka.deserialization.failures`.
- **Open:** no OTLP exporter — spans sampled but not collected. A
  separate PR will wire `opentelemetry-exporter-otlp` and a
  Jaeger/Tempo-shaped collector endpoint.

## [IETF Idempotency-Key draft](https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/)

| Requirement | Status |
|---|---|
| Header accepted | ✅ |
| Malformed key rejected | ✅ |
| Same key → same observable effect | 🟡 — held at the Kafka layer (idempotent producer + dedup on `event.id`); the S3 archive does not deduplicate, so a retried POST after Kafka acceptance can land twice in different S3 objects. Tracked separately. |

## [RFC 9457 — Problem Details for HTTP APIs](https://www.rfc-editor.org/rfc/rfc9457.html)

✅ `@ControllerAdvice` returns `application/problem+json` with `type`,
`title`, `status`, `detail`, `instance`, plus a `errors` array for
field-level validation failures.

---

## Top open items

| # | Standard | Gap | Why deferred |
|---|---|---|---|
| 1 | ASVS V9.1.1 | Plaintext between services + to Kafka | Cert infra; needs separate sprint |
| 2 | OTel | No OTLP exporter — spans sampled, not collected | Needs a backend choice (Tempo / Jaeger / vendor) |
| 3 | IETF Idempotency | S3 archive does not dedupe on event.id | Needs consumer-side dedup or S3 conditional write |
| 4 | OWASP API4 | No per-IP rate limit when key present | Multi-instance limiter is the real fix |
| 5 | ASVS V2.10.4 | API-key rotation mechanism | Needs key-id concept, KMS or secret-manager wiring |
| 6 | CICD-SEC-7 | Pin actions by SHA, not major version | Hygiene; defer until next dependency pass |
| 7 | OWASP API9 | URL versioning (`/v1/events`) | Will collide with current consumers; future contract change |

---

## Progress

This file landed alongside a focused compliance pass — see the PR
description for the commit map (RFC 9457 handler, OpenAPI spec,
SECURITY.md, JSON logging, base-image digest pinning, Trivy + CodeQL
+ SBOM + Cosign in CI).
