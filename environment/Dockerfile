# ==================== Build Stage ====================
FROM eclipse-temurin:23-jdk-alpine AS builder

WORKDIR /app

# Copy Maven wrapper and pom files first (for caching)
COPY mvnw ./
COPY ../.mvn .mvn
COPY ../pom.xml ./
COPY ../common/pom.xml common/
COPY ../infrastructure/pom.xml infrastructure/
COPY ../modules/user/pom.xml modules/user/
COPY ../start/pom.xml start/

# Download dependencies (cached layer)
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B

# Copy source code
COPY ../common/src common/src
COPY ../infrastructure/src infrastructure/src
COPY ../modules/user/src modules/user/src
COPY ../start/src start/src

# Build the application
RUN ./mvnw clean package -DskipTests -B

# ==================== Runtime Stage ====================
FROM eclipse-temurin:23-jre-alpine AS runtime

WORKDIR /app

# Add non-root user for security
RUN addgroup -S satori && adduser -S satori -G satori

# Copy the built jar
COPY --from=builder /app/start/target/*.jar app.jar

# Set ownership
RUN chown -R satori:satori /app

USER satori

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget -q --spider http://localhost:8080/actuator/health || exit 1

# JVM options for containerized environment
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0"

# Run the application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
