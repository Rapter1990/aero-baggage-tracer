# Build from the repository root: docker build -f config-server/Dockerfile .

# Step #1: Start the build stage from a Maven image that ships JDK 25.
FROM maven:3-eclipse-temurin-25 AS build

# Step #2: Set the working directory for the build.
WORKDIR /src

# Step #3: Copy the whole repository (the parent POM lists every module).
COPY . .

# Step #4: Package only the config-server module, caching ~/.m2 between builds.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -pl config-server -DskipTests -Dspotless.check.skip=true package

# Step #5: Start the runtime stage from a slim JRE-only image.
FROM eclipse-temurin:25-jre

# Step #6: Install curl (used by the health check) and create a non-root user.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 config

# Step #7: Run as the non-root user.
USER config

# Step #8: Set the working directory for the running app.
WORKDIR /app

# Step #9: Copy the packaged jar from the build stage.
COPY --from=build /src/config-server/target/config-server-*.jar app.jar

# Step #10: Document the Config port.
EXPOSE 8888

# Step #11: Mark the container healthy only when the actuator health endpoint answers.
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=5 \
  CMD curl -fsS http://localhost:8888/actuator/health || exit 1

# Step #12: Start the Config server.
ENTRYPOINT ["java", "-jar", "app.jar"]
