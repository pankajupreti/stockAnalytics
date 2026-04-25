# ----------- Build stage -----------
FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /app

# Copy Maven descriptor first (improves caching)
COPY pom.xml .

# Pre-fetch dependencies (optional optimization)
RUN mvn -q -DskipTests dependency:go-offline

# Copy application source
COPY src ./src

# Build the application jar
RUN mvn -q clean package -DskipTests

# ----------- Runtime stage -----------
FROM eclipse-temurin:17-jre

WORKDIR /app

# Copy the built jar (auto-detect the actual jar name)
COPY --from=build /app/target/*.jar app.jar

# Render sets PORT dynamically. Keep local fallback = 8088
ENV PORT=8088

EXPOSE 8088

ENTRYPOINT ["java", "-jar", "app.jar"]
