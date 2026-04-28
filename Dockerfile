FROM eclipse-temurin:23-jre

WORKDIR /app

COPY target/protogemcouch.jar /app/protogemcouch.jar

EXPOSE 40405
EXPOSE 8081

ENTRYPOINT ["java", "-jar", "/app/protogemcouch.jar"]