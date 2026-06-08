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

# Create a directory for the H2 database file to live
RUN mkdir /app/data

# Copy the built jar from the builder stage
COPY --from=builder /workspace/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
