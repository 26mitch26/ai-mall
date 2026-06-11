FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app

COPY pom.xml ./
COPY agent-customer/pom.xml ./agent-customer/

RUN mvn dependency:go-offline -pl agent-customer -am --no-transfer-progress -DskipTests=true

COPY . .
RUN mvn package -pl agent-customer -am -DskipTests=true --no-transfer-progress

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

COPY --from=build /app/agent-customer/target/agent-customer-*.jar app.jar

EXPOSE 8082

USER appuser

ENTRYPOINT ["java", "-jar", "app.jar"]