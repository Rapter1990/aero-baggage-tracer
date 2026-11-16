# Build from the repository root: docker build -f risk-streams-service/Dockerfile .

# Step #1: Start the build stage from a Maven image that ships JDK 25.
FROM maven:3-eclipse-temurin-25 AS build

# Step #2: Set the working directory for the build.
WORKDIR /src

# Step #3: Copy the whole repository (the parent POM lists every module).
COPY . .

# Step #4: Package risk-streams-service and common-module, caching ~/.m2 between builds.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -pl risk-streams-service -am -DskipTests -Dspotless.check.skip=true package

# Step #5: Start the runtime stage from a slim JRE-only image.
FROM eclipse-temurin:25-jre

# Step #6: Install curl (used by the health check) and create a non-root user.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 risk

# Step #7: Create the Kafka Streams state directory (RocksDB), owned by the app user. A named
# volume mounted here (see docker-compose.yml) keeps the state across container recreation.
RUN mkdir -p /app/state && chown risk:risk /app/state
ENV RISK_STREAMS_STATE_DIR=/app/state

# Step #8: Run as the non-root user.
USER risk

# Step #9: Set the working directory for the running app.
WORKDIR /app

# Step #10: Copy the packaged jar from the build stage.
COPY --from=build /src/risk-streams-service/target/risk-streams-service-*-exec.jar app.jar

# Step #11: Document the risk-streams-service port.
EXPOSE 1114

# Step #12: Mark the container healthy only when the actuator health endpoint answers.
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
  CMD curl -fsS http://localhost:1114/actuator/health || exit 1

# Step #13: Start risk-streams-service.
ENTRYPOINT ["java", "-jar", "app.jar"]
