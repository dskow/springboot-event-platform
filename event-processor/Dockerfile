# Multi-stage build — built from the repo root so the parent POM is reachable.
# Build:  docker build -f event-processor/Dockerfile .
FROM maven:3-eclipse-temurin-26 AS build
WORKDIR /workspace
COPY pom.xml .
COPY event-gateway/pom.xml event-gateway/pom.xml
COPY event-ingest/pom.xml event-ingest/pom.xml
COPY event-processor/pom.xml event-processor/pom.xml
RUN mvn -B -pl event-processor -am dependency:go-offline -DskipTests
COPY event-processor/src event-processor/src
RUN mvn -B -pl event-processor -am package -DskipTests

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
RUN apk add --no-cache curl \
 && addgroup -S spring && adduser -S spring -G spring
USER spring:spring
COPY --from=build /workspace/event-processor/target/event-processor-*.jar app.jar
EXPOSE 8082
# /actuator lives on the management port (9082), not 8082.
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=3 \
  CMD curl -fsS http://localhost:9082/actuator/health | grep -q '"status":"UP"' || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
