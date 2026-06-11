FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app

COPY pom.xml ./
COPY agent-test/pom.xml ./agent-test/

RUN mvn dependency:go-offline -pl agent-test -am --no-transfer-progress -DskipTests=true

COPY . .
RUN mvn package -pl agent-test -am -DskipTests=true --no-transfer-progress

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

COPY --from=build /app/agent-test/target/agent-test-*.jar app.jar

EXPOSE 8085

USER appuser

ENTRYPOINT ["java", "-jar", "app.jar"]