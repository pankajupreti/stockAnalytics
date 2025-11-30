# ----------- Build stage -----------
FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /app

# Copy Maven descriptor first (improves build cache)
COPY pom.xml .
# If you are using Maven Wrapper, uncomment:
# COPY mvnw .
# COPY .mvn .mvn

# Download dependencies (optional but speeds up future builds)
RUN mvn -q -DskipTests dependency:go-offline

# Copy all application source
COPY src ./src

# Build the project (jar lands in /app/target/*.jar)
RUN mvn -q clean package -DskipTests

# ----------- Runtime stage -----------
FROM eclipse-temurin:17-jre

WORKDIR /app

# Copy the built JAR (works regardless of jar file name)
COPY --from=build /app/target/*.jar app.jar

# Render injects PORT dynamically; fallback 8083 for local dev
ENV PORT=8083

# Expose local dev port (Render handles real routing)
EXPOSE 8083

ENTRYPOINT ["java", "-jar", "app.jar"]
