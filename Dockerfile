# Stage 1: Build the application
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /workspace

# Copy the Gradle wrapper and configuration files
COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts .
COPY settings.gradle.kts .
COPY src src

# Make the wrapper executable and build the jar
RUN chmod +x gradlew
RUN ./gradlew bootJar --no-daemon

# Stage 2: Run the application
FROM eclipse-temurin:21-jre
WORKDIR /app

# Create directory for the DB
RUN mkdir /app/data

# Copy the built jar from the builder stage
COPY --from=builder /workspace/build/libs/*.jar app.jar

EXPOSE 8080

# Activate the prod Spring profile by default (Cloudflare Zero Trust JWT validation).
# Can be overridden at runtime via SPRING_PROFILES_ACTIVE environment variable.
ENV SPRING_PROFILES_ACTIVE=prod

# Start the JVM directly (Betfair SSL certs are not needed in prod — live API is disabled)
ENTRYPOINT ["java", "-jar", "app.jar"]
