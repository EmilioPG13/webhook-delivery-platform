# Build with the JDK, run with the JRE. One image serves both roles: the SPRING_PROFILES_ACTIVE
# environment variable decides whether a container is also a worker.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /build
COPY .mvn .mvn
COPY mvnw pom.xml ./
# Dependencies first, so editing source does not download them again.
RUN ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q -DskipTests package

FROM eclipse-temurin:25-jre
# The JRE image ships neither curl nor wget, and the healthcheck needs one of them.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system app \
    && useradd --system --gid app --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=build --chown=app:app /build/target/webhooks-*.jar app.jar
USER app
EXPOSE 8080
# Liveness only: the endpoint answers once the context is up, which includes a working database connection.
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=3 \
    CMD curl --fail --silent --show-error http://localhost:8080/actuator/health || exit 1
# The heap takes 75% of the container's memory limit, the rest is for metaspace, thread stacks and the JIT.
# An OutOfMemoryError ends the process so the orchestrator restarts it instead of leaving a half-dead JVM.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
