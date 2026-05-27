# -----------------------------------
# STAGE 1 — BUILD JAVA APP
# -----------------------------------

FROM maven:3.9.6-eclipse-temurin-17 AS builder

WORKDIR /app

COPY pom.xml .

COPY src ./src

RUN mvn clean package -DskipTests

# -----------------------------------
# STAGE 2 — RUN APP
# -----------------------------------

FROM eclipse-temurin:17

WORKDIR /app

COPY --from=builder /app/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]