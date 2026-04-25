# ----------- Build stage -----------
FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /app

# Copy Maven descriptor
COPY pom.xml .

# Pre-fetch dependencies
RUN mvn -q -DskipTests dependency:go-offline

# Copy source
COPY src ./src

# Build the Spring Boot jar
RUN mvn -q clean package -DskipTests

# ----------- Runtime stage -----------
FROM eclipse-temurin:17-jre

WORKDIR /app

# Copy the built jar
COPY --from=build /app/target/*.jar app.jar

ENV PORT=8087

EXPOSE 8087

ENTRYPOINT ["java", "-jar", "app.jar"]
