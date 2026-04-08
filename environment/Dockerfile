# =============================================================================
# Satori Learning Platform — Multi-stage Docker build
#
# Build context: project ROOT (run from repo root):
#   docker build -f environment/Dockerfile -t satori-backend .
#
# The original file used ../ paths which Docker does not allow (cannot exit
# the build context). All paths below are relative to the project root.
# =============================================================================

# ==================== Build Stage ====================
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

WORKDIR /app

# Copy parent + all module pom.xml first to maximise layer caching.
# Docker only re-downloads dependencies when a pom changes, not when source changes.
COPY pom.xml ./
COPY common/pom.xml common/
COPY infrastructure/pom.xml infrastructure/
COPY modules/achievement/pom.xml modules/achievement/
COPY modules/assignment/pom.xml modules/assignment/
COPY modules/class/pom.xml modules/class/
COPY modules/conversation/pom.xml modules/conversation/
COPY modules/course/pom.xml modules/course/
COPY modules/curriculum-import/pom.xml modules/curriculum-import/
COPY modules/monitoring/pom.xml modules/monitoring/
COPY modules/notification/pom.xml modules/notification/
COPY modules/practice/pom.xml modules/practice/
COPY modules/pronunciation/pom.xml modules/pronunciation/
COPY modules/roleplay/pom.xml modules/roleplay/
COPY modules/user/pom.xml modules/user/
COPY start/pom.xml start/

# Pre-fetch all dependencies including plugin dependencies
RUN mvn dependency:go-offline dependency:resolve-plugins -B

# Copy all source code
COPY common/src common/src
COPY infrastructure/src infrastructure/src
COPY modules/achievement/src modules/achievement/src
COPY modules/assignment/src modules/assignment/src
COPY modules/class/src modules/class/src
COPY modules/conversation/src modules/conversation/src
COPY modules/course/src modules/course/src
COPY modules/curriculum-import/src modules/curriculum-import/src
COPY modules/monitoring/src modules/monitoring/src
COPY modules/notification/src modules/notification/src
COPY modules/practice/src modules/practice/src
COPY modules/pronunciation/src modules/pronunciation/src
COPY modules/roleplay/src modules/roleplay/src
COPY modules/user/src modules/user/src
COPY start/src start/src

# Build — skip tests (tests run in CI, not in image build)
RUN mvn clean package -DskipTests -B

# ==================== Runtime Stage ====================
# Use Debian-based image instead of Alpine to avoid musl libc incompatibility
# with native libraries (e.g. grpc-netty-tcnative used by Firebase/Google Cloud SDK).
FROM eclipse-temurin:21-jre-jammy AS runtime

WORKDIR /app

# Install wget for healthcheck and create non-root user
RUN apt-get update && apt-get install -y --no-install-recommends wget \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -r satori && useradd -r -g satori satori

# Copy only the assembled jar from the builder stage
COPY --from=builder /app/start/target/*.jar app.jar

RUN chown -R satori:satori /app

USER satori

EXPOSE 8080

# Liveness probe used by docker-compose and Kubernetes
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget -q --spider http://localhost:8080/actuator/health/liveness || exit 1

# UseContainerSupport: respects cgroup memory limits set by Docker/k8s
# MaxRAMPercentage: use up to 75% of the container memory limit for heap
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
