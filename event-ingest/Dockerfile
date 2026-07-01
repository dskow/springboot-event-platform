# Multi-stage build — built from the repo root so the parent POM is reachable.
# Build:  docker build -f event-ingest/Dockerfile .
# See event-gateway/Dockerfile for digest-pinning rationale.
FROM maven:3-eclipse-temurin-26@sha256:1fc9415e0626a5893bbc352149d25a413e334a7ac5cd514bd99a2828fb082071 AS build
WORKDIR /workspace
COPY pom.xml .
COPY event-gateway/pom.xml event-gateway/pom.xml
COPY event-ingest/pom.xml event-ingest/pom.xml
COPY event-processor/pom.xml event-processor/pom.xml
RUN mvn -B -pl event-ingest -am dependency:go-offline -DskipTests
COPY event-ingest/src event-ingest/src
RUN mvn -B -pl event-ingest -am package -DskipTests

FROM eclipse-temurin:25-jre-alpine@sha256:c707c0d18cb9e8556380719f80d96a7529d0746fbb42143893949b98ed2f8943
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
