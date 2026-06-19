# Multi-stage build for Oddsmaker Control Service
# Stage 1: Build the application
FROM gradle:8.10-jdk21 AS build

WORKDIR /workspace

# Copy build files first for better caching
COPY build.gradle.kts settings.gradle.kts ./
COPY services/control-service/build.gradle.kts ./services/control-service/

# Download dependencies (cached if build files unchanged)
RUN gradle dependencies --no-daemon || true

# Copy source code
COPY services/control-service ./services/control-service
COPY schema ./schema

# Build the application
RUN gradle :services:control-service:bootJar --no-daemon -x test

# Stage 2: Create the runtime image
FROM eclipse-temurin:21-jre-alpine

# Add metadata
LABEL maintainer="Oddsmaker Team"
LABEL description="Oddsmaker Control Service - Gaming Analytics Platform"
LABEL version="1.0.0"
LABEL org.opencontainers.image.source="https://github.com/oddsmaker/control-service"

# Set environment variables
ENV JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC -XX:+UseStringDeduplication -XX:MaxRAMPercentage=75.0"
ENV SPRING_PROFILES_ACTIVE="prod"
ENV SERVER_PORT="8085"
ENV MANAGEMENT_SERVER_PORT="8086"

# Install wget for health check
RUN apk add --no-cache wget

# Create non-root user
RUN addgroup -S oddsmaker && adduser -S oddsmaker -G oddsmaker

# Create directories
RUN mkdir -p /app/logs /app/config /app/data /app/db/migration && \
    chown -R oddsmaker:oddsmaker /app

# Copy the built jar
COPY --from=build /workspace/services/control-service/build/libs/*.jar /app/app.jar

# Copy migration files
COPY services/control-service/src/main/resources/db/migration /app/db/migration

# Copy default configuration
COPY services/control-service/src/main/resources/application.yaml /app/config/application.yaml

# Set ownership
RUN chown -R oddsmaker:oddsmaker /app

# Switch to non-root user
USER oddsmaker

# Expose ports
EXPOSE ${SERVER_PORT}
EXPOSE ${MANAGEMENT_SERVER_PORT}

# Health check using Spring Boot Actuator
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:${MANAGEMENT_SERVER_PORT}/actuator/health || exit 1

# Volume for logs and data
VOLUME ["/app/logs", "/app/data"]

# Run the application
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /app/app.jar --spring.config.additional-location=file:/app/config/"]
