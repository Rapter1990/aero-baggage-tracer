# Build from the repository root: docker build -f discovery-server/Dockerfile .
FROM maven:3-eclipse-temurin-25 AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -pl discovery-server -DskipTests -Dspotless.check.skip=true package

FROM eclipse-temurin:25-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 eureka
USER eureka
WORKDIR /app
COPY --from=build /src/discovery-server/target/discovery-server-*.jar app.jar
EXPOSE 8761
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=5 \
  CMD curl -fsS http://localhost:8761/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
