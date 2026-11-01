# ==========================================
# Stage 1: Build Application with Maven Wrapper
# ==========================================
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /build

# Copy Maven Wrapper and POM first to leverage Docker layer caching
COPY mvnw mvnw
COPY .mvn .mvn
COPY pom.xml pom.xml

# Ensure POSIX line endings and executable permission on mvnw
RUN chmod +x mvnw

# Copy application source code
COPY src src

# Package the runnable fat JAR
RUN ./mvnw clean package -DskipTests

# ==========================================
# Stage 2: Minimal Production Runtime
# ==========================================
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Run as a non-privileged user for container security
RUN addgroup -S gatewaygroup && adduser -S gatewayuser -G gatewaygroup
USER gatewayuser

# Copy only the compiled JAR from the builder stage
COPY --from=builder /build/target/llm-gateway-*.jar app.jar

# Dynamic port binding from host environment (Render provides $PORT)
ENV PORT=8080
EXPOSE 8080

# Production JVM flags with container memory awareness
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]

