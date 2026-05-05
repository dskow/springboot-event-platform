# Multi-stage build — built from the repo root so the parent POM is reachable.
# Build:  docker build -f event-gateway/Dockerfile .
FROM maven:3-eclipse-temurin-26 AS build
WORKDIR /workspace
COPY pom.xml .
COPY event-gateway/pom.xml event-gateway/pom.xml
COPY event-ingest/pom.xml event-ingest/pom.xml
COPY event-processor/pom.xml event-processor/pom.xml
RUN mvn -B -pl event-gateway -am dependency:go-offline -DskipTests
COPY event-gateway/src event-gateway/src
RUN mvn -B -pl event-gateway -am package -DskipTests

FROM eclipse-temurin:25-jre-alpine
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
