# Security Policy

## Reporting a vulnerability

If you discover a security issue in this project, please report it
privately to **david@dskow.com**. Please do **not** open a public
GitHub issue for security-impacting findings.

When reporting, include where you can:

- A description of the issue and the impact you observed.
- Reproduction steps or a minimal proof-of-concept.
- The commit or release tag the finding applies to.
- Any logs, traces, or HTTP responses that help triage.

You can expect:

- An acknowledgement within **5 business days**.
- A triage decision (accepted / not-applicable / duplicate) within
  **10 business days**.
- A target fix window proportional to severity:
  - Critical / High: 30 days
  - Medium: 60 days
  - Low: best-effort

I'll credit reporters in the release notes for any accepted finding,
unless you prefer to remain anonymous.

## Supported versions

This is a single-author portfolio repo. Only `main` is supported;
fixes are not backported to historical commits. Pin a specific commit
SHA if you need a stable target.

## Out-of-scope

The following are documented limitations and not security findings:

- Plaintext between services and to Kafka in the local Docker Compose
  stack — see [docs/MISSION_CRITICAL_READINESS.md](docs/MISSION_CRITICAL_READINESS.md).
- LocalStack credentials (`test`/`test`) inline in
  [event-processor/.../S3Config.java](event-processor/src/main/java/com/dskow/eventplatform/processor/config/S3Config.java) — only used when
  `app.s3.endpoint` is set to a LocalStack URL.
- Demo API key (`demo-key`) shipped in `docker-compose.yml` for the
  `docker compose up` quickstart.

## See also

- [docs/STANDARDS_COMPLIANCE.md](docs/STANDARDS_COMPLIANCE.md) —
  OWASP / NIST / SLSA / CISA posture.
- [docs/MISSION_CRITICAL_READINESS.md](docs/MISSION_CRITICAL_READINESS.md)
  — internal durability audit and sprint history.
