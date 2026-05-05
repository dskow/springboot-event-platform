# Multi-stage build — built from the repo root so the parent POM is reachable.
# Build:  docker build -f event-gateway/Dockerfile .
# Pinned by digest so a base-image swap upstream cannot silently change our
# build environment. Dependabot's docker ecosystem opens a PR when a new
# digest is published — the tag in the comment is the human-readable name
# Dependabot keeps in sync.
FROM maven:3-eclipse-temurin-26@sha256:2afabfe1611261e4cc04d49c7819309c07653997b78157f44d86acc200a414f1 AS build
WORKDIR /workspace
COPY pom.xml .
COPY event-gateway/pom.xml event-gateway/pom.xml
COPY event-ingest/pom.xml event-ingest/pom.xml
COPY event-processor/pom.xml event-processor/pom.xml
RUN mvn -B -pl event-gateway -am dependency:go-offline -DskipTests
COPY event-gateway/src event-gateway/src
RUN mvn -B -pl event-gateway -am package -DskipTests

FROM eclipse-temurin:25-jre-alpine@sha256:c707c0d18cb9e8556380719f80d96a7529d0746fbb42143893949b98ed2f8943
WORKDIR /app
RUN apk add --no-cache curl \
 && addgroup -S spring && adduser -S spring -G spring
USER spring:spring
COPY --from=build /workspace/event-gateway/target/event-gateway-*.jar app.jar
EXPOSE 8080
# /actuator lives on the management port (9080), not the user-facing 8080.
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=3 \
  CMD curl -fsS http://localhost:9080/actuator/health | grep -q '"status":"UP"' || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
