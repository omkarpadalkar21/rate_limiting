FROM eclipse-temurin:21-jdk-jammy

WORKDIR /app

COPY /target/rate_limiting-0.0.1-SNAPSHOT.jar /app/rate_limiting.jar

EXPOSE 8080

CMD ["java", "-jar", "rate_limiting.jar"]