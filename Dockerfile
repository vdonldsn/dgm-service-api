# ============================================================
#  Multi-stage Dockerfile — dgm-service-api (Gradle)
#  Stage 1: build the fat jar with Gradle wrapper
#  Stage 2: run with a minimal JRE image
# ============================================================

# ── Stage 1: Build ───────────────────────────────────────────
FROM eclipse-temurin:17-jdk-alpine AS builder

WORKDIR /build

# Copy Gradle wrapper files first — layer is cached until
# build.gradle.kts or settings.gradle.kts changes.
COPY gradlew .
COPY gradle ./gradle
COPY build.gradle.kts .
COPY settings.gradle.kts .

RUN chmod +x gradlew

# Download dependencies only (no source yet) — cached layer
RUN ./gradlew dependencies --no-daemon -q

# Copy source and build the fat jar
COPY src ./src
RUN ./gradlew bootJar --no-daemon -q

# ── Stage 2: Runtime ─────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine AS runtime

WORKDIR /app

# Non-root user for security
RUN addgroup -S dgm && adduser -S dgm -G dgm

# Pull the jar from stage 1
COPY --from=builder /build/build/libs/dgm-service-api-*.jar app.jar

RUN chown dgm:dgm app.jar
USER dgm

EXPOSE 8080

ENTRYPOINT ["java", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "app.jar"]
