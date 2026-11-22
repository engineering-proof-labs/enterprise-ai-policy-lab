FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
RUN mvn --batch-mode --no-transfer-progress dependency:go-offline
COPY src/main src/main
RUN mvn --batch-mode --no-transfer-progress -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/enterprise-ai-policy-lab-0.1.0-SNAPSHOT.jar app.jar
USER 10001
ENTRYPOINT ["java", "-jar", "app.jar"]
