# Multi-stage build — built from the repo root so the parent POM is reachable.
# Build:  docker build -f event-ingest/Dockerfile .
# See event-gateway/Dockerfile for digest-pinning rationale.
FROM maven:3-eclipse-temurin-26@sha256:d5617b9a6307e1b51dc7c55edf09bacb66f1c91fb861949c34a3a0d4e16bd241 AS build
WORKDIR /workspace
COPY pom.xml .
COPY event-gateway/pom.xml event-gateway/pom.xml
COPY event-ingest/pom.xml event-ingest/pom.xml
COPY event-processor/pom.xml event-processor/pom.xml
RUN mvn -B -pl event-ingest -am dependency:go-offline -DskipTests
COPY event-ingest/src event-ingest/src
RUN mvn -B -pl event-ingest -am package -DskipTests

FROM eclipse-temurin:25-jre-alpine@sha256:28db6fdf60e38945e43d840c0333aeaec66c15943070104f7586fd3c9d1665b0
WORKDIR /app
RUN apk add --no-cache curl \
 && addgroup -S spring && adduser -S spring -G spring
USER spring:spring
COPY --from=build /workspace/event-ingest/target/event-ingest-*.jar app.jar
EXPOSE 8081
# /actuator lives on the management port (9081), not 8081.
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=3 \
  CMD curl -fsS http://localhost:9081/actuator/health | grep -q '"status":"UP"' || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
