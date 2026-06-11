FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app

COPY pom.xml ./
COPY agent-ops/pom.xml ./agent-ops/

RUN mvn dependency:go-offline -pl agent-ops -am --no-transfer-progress -DskipTests=true

COPY . .
RUN mvn package -pl agent-ops -am -DskipTests=true --no-transfer-progress

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

COPY --from=build /app/agent-ops/target/agent-ops-*.jar app.jar

EXPOSE 8083

USER appuser

ENTRYPOINT ["java", "-jar", "app.jar"]