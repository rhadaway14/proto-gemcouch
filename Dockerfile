# ---- build stage ----
FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /app

COPY pom.xml .
COPY src ./src

RUN mvn clean package -DskipTests

# ---- runtime stage ----
FROM eclipse-temurin:17-jre

WORKDIR /app

RUN addgroup --system protogemcouch && adduser --system --ingroup protogemcouch protogemcouch

COPY --from=build /app/target/protogemcouch.jar /app/protogemcouch.jar

USER protogemcouch

EXPOSE 40405

ENV SHIM_PORT=40405

ENTRYPOINT ["java", "-jar", "/app/protogemcouch.jar"]