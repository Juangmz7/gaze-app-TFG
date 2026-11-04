# Local development / testing image.
# JAVA_VERSION must match <java.version> in pom.xml.
ARG JAVA_VERSION=25

# Stage 1: Build
FROM maven:3.9-eclipse-temurin-${JAVA_VERSION} AS builder
WORKDIR /app
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B clean package -DskipTests

# Stage 2: Run
FROM eclipse-temurin:${JAVA_VERSION}-jre
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
