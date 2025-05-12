# syntax=docker/dockerfile:1.4

### Stage 1: Build with Maven and Java 21 + .m2 cache
FROM maven:3.9.6-eclipse-temurin-21 AS builder

WORKDIR /app

# Copy only the POM first to leverage cache layers
COPY pom.xml .

# Download dependencies to cache them via BuildKit
RUN --mount=type=cache,target=/root/.m2 \
    mvn dependency:go-offline

# Copy full source and build
COPY src ./src

RUN --mount=type=cache,target=/root/.m2 \
    mvn clean package -DskipTests

### Stage 2: Runtime with non-root user
FROM eclipse-temurin:21-jre

# Add non-root user
RUN groupadd -r appuser && useradd -r -g appuser -d /app -s /sbin/nologin appuser

WORKDIR /app
COPY --from=builder /app/target/knowledge-enrichment-*.jar app.jar
RUN chown -R appuser:appuser /app

USER appuser

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
