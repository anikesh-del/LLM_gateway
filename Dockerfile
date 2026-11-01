# ==========================================
# Stage 1: Build Application with Pre-installed Maven
# ==========================================
FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder

WORKDIR /build

# Copy POM first to leverage Docker layer caching
COPY pom.xml .

# Copy application source code
COPY src src

# Package the runnable fat JAR
RUN mvn clean package -DskipTests -B

# ==========================================
# Stage 2: Minimal Production Runtime (~180MB)
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
