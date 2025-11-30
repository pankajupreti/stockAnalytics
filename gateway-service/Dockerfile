# ----------- Build stage -----------
FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /app

# Copy Maven descriptor first (better caching)
COPY pom.xml .
# If you use Maven wrapper, you can also:
# COPY mvnw .
# COPY .mvn .mvn

# Pre-fetch dependencies (optional, speeds up future builds)
RUN mvn -q -DskipTests dependency:go-offline

# Copy source
COPY src ./src

# Build the Spring Boot jar
RUN mvn -q clean package -DskipTests

# ----------- Runtime stage -----------
FROM eclipse-temurin:17-jre

WORKDIR /app

# Copy the built jar (works regardless of jar file name)
COPY --from=build /app/target/*.jar app.jar

# Gateway default port (local). Render will override PORT.
ENV PORT=8082

EXPOSE 8082

ENTRYPOINT ["java", "-jar", "app.jar"]
