# ---- build stage: compile and package the application -------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies first, in their own layer, so editing source code does not re-download the internet.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
# Tests run in CI (they start their own throwaway PostgreSQL); the image build only packages.
RUN mvn -B -q -DskipTests package && cp target/holdlatch-*.jar app.jar

# ---- runtime stage: a small JRE image, running as a normal user -----------------------------
FROM eclipse-temurin:21-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --no-create-home holdlatch

WORKDIR /app
COPY --from=build /build/app.jar app.jar
USER holdlatch

# Size the heap from the container's memory limit; die loudly (and get restarted) rather than limp on after an OOM.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8081

HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=5 \
    CMD curl -fs http://localhost:8081/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
