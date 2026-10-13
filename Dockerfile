# Stage 1: Build
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /build

# Copy the pom.xml and download dependencies (cache layer)
COPY pom.xml .

# Copy the entire project
COPY src/ ./src/
COPY docs/ ./docs/

# Build the application, skipping tests for Docker builds
# (tests run separately in CI)
RUN mvn clean package -DskipTests -q

# Stage 2: Runtime
FROM eclipse-temurin:21-jre

# curl is needed by the HEALTHCHECK below (the JRE image ships neither curl
# nor wget; a failing check makes Docker/Dokploy restart the container).
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Create a non-root user (UID 1000 is taken by the image's built-in "ubuntu" user)
RUN useradd -m -u 10001 appuser

WORKDIR /app

# Production by default (turns on ProductionSecretsGuard). Uploaded resumes/JDs
# live in /app/storage — mount a persistent volume there.
ENV SPRING_PROFILES_ACTIVE=prod \
    STORAGE_DIR=/app/storage

# Copy the built JAR from the builder stage
COPY --from=builder /build/target/backend-classic-0.1.0.jar /app/app.jar

# Change ownership to the non-root user
RUN mkdir -p /app/storage && chown -R appuser:appuser /app

USER appuser

# Expose the application port
EXPOSE 8000

# Health check
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -fsS http://localhost:8000/actuator/health || exit 1

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]
