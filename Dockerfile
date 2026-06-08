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

# Create directories for the DB and the SSL certs
RUN mkdir /app/data
RUN mkdir /app/ssl

# Copy the built jar from the builder stage
COPY --from=builder /workspace/build/libs/*.jar app.jar

EXPOSE 8080

# Decode the certificates from environment variables into files, then start the JVM
ENTRYPOINT ["/bin/sh", "-c", "echo \"$BETFAIR_CERT_B64\" | base64 -d > /app/ssl/client-2048.crt && echo \"$BETFAIR_KEY_B64\" | base64 -d > /app/ssl/client-2048.key && java -jar app.jar"]