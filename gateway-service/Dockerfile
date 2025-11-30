# ----------- Build stage -----------
FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /app

# Copy Maven descriptor (from repo root -> gateway-service folder)
COPY gateway-service/pom.xml .

# Pre-fetch dependencies (optional)
RUN mvn -q -DskipTests dependency:go-offline

# Copy source
COPY gateway-service/src ./src

# Build the Spring Boot jar
RUN mvn -q clean package -DskipTests

# ----------- Runtime stage -----------
FROM eclipse-temurin:17-jre

WORKDIR /app

# Copy the built jar (whatever the name is)
COPY --from=build /app/target/*.jar app.jar

# Gateway default port (Render injects PORT env var too)
ENV PORT=8082

EXPOSE 8082

ENTRYPOINT ["java", "-jar", "app.jar"]
