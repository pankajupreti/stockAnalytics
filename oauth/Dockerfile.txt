# ----------- Build stage -----------
FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /app

# Copy Maven descriptor first (better caching)
COPY pom.xml .
# If you have any extra Maven wrapper files, you can copy them too
# COPY mvnw . 
# COPY .mvn .mvn

# Download dependencies (optional optimization)
RUN mvn -q -DskipTests dependency:go-offline

# Now copy source
COPY src ./src

# Build the jar
RUN mvn -q clean package -DskipTests

# ----------- Runtime stage -----------
FROM eclipse-temurin:17-jre

WORKDIR /app

# Copy the built jar from the build stage
COPY --from=build /app/target/demo-0.0.1-SNAPSHOT.jar app.jar

# Render sets PORT env var; Spring uses server.port=${PORT:8080}
ENV PORT=8080

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
