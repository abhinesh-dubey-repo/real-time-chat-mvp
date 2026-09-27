# Multi-stage build: compile with Maven+JDK, run on a slim JRE-only image
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B clean package -DskipTests

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /build/target/chat-backend.jar app.jar
# Persist the H2 file-based DB across container restarts when this is mounted as a volume
VOLUME ["/app/data"]
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
