# Multi-stage: build with the full JDK + Gradle, ship only a JRE and the jar.
# A single-stage image would carry the JDK, the Gradle distribution, and the
# whole dependency cache — roughly 800MB to run a 60MB application.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

# Copy the wrapper and build files FIRST, resolve dependencies, and only then
# copy source. Docker caches each layer, so editing a .java file re-runs just
# the compile step instead of re-downloading Spring Boot every time.
COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle settings.gradle ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true

COPY src ./src
# -x test because CI already ran the suite. Running it again here would double
# build time to re-prove something a green pipeline established.
RUN ./gradlew bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre
WORKDIR /app

# Run as a non-root user. If the process is ever compromised, it shouldn't also
# hold root inside the container.
RUN useradd --create-home --shell /bin/bash ledgerlens
USER ledgerlens

COPY --from=build --chown=ledgerlens:ledgerlens /build/build/libs/*.jar app.jar

EXPOSE 8080

# Actuator already exposes /health and is unauthenticated by design, so Docker
# can use it without credentials.
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=5 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
