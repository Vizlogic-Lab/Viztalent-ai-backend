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

# Create a non-root user
RUN useradd -m -u 1000 appuser

WORKDIR /app

# Copy the built JAR from the builder stage
COPY --from=builder /build/target/backend-classic-0.1.0.jar /app/app.jar

# Change ownership to the non-root user
RUN chown -R appuser:appuser /app

USER appuser

# Expose the application port
EXPOSE 8000

# Health check
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://localhost:8000/actuator/health || exit 1

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]
