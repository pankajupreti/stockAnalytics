# ----------- Build stage -----------
FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /app

# Copy Maven descriptor first (improves build caching)
COPY pom.xml .
# If you use Maven wrapper, you could also:
# COPY mvnw .
# COPY .mvn .mvn

# Pre-fetch dependencies (optional but speeds up subsequent builds)
RUN mvn -q -DskipTests dependency:go-offline

# Copy source code
COPY src ./src

# Build the Spring Boot jar
RUN mvn -q clean package -DskipTests

# ----------- Runtime stage -----------
FROM eclipse-temurin:17-jre

WORKDIR /app

# Copy the built jar (auto-detect jar file name)
COPY --from=build /app/target/*.jar app.jar

# Local default port for this service; Render overrides PORT
ENV PORT=8085

EXPOSE 8085

ENTRYPOINT ["java", "-jar", "app.jar"]
