##### Build stage
FROM amazoncorretto:21 as builder
WORKDIR /app
COPY mvnw mvnw.cmd ./
COPY .mvn .mvn
COPY pom.xml .
COPY src ./src
RUN chmod +x ./mvnw && ./mvnw package -DskipTests


##### Run stage
FROM amazoncorretto:21
WORKDIR /app
COPY --from=builder /app/target/rinha-back-end-2024-01-1.0.0-SNAPSHOT-fat.jar .
CMD ["java", "-XX:MaxRAMPercentage=75", "-jar", "rinha-back-end-2024-01-1.0.0-SNAPSHOT-fat.jar"]
