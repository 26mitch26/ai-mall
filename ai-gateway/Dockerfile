FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app

COPY pom.xml ./
COPY mall-core/pom.xml ./mall-core/
COPY mall-core/mall-common/pom.xml ./mall-core/mall-common/
COPY mall-core/mall-mbg/pom.xml ./mall-core/mall-mbg/
COPY mall-core/mall-security/pom.xml ./mall-core/mall-security/
COPY ai-gateway/pom.xml ./ai-gateway/

RUN mvn dependency:go-offline -pl ai-gateway -am --no-transfer-progress -DskipTests=true

COPY . .
RUN mvn package -pl ai-gateway -am -DskipTests=true --no-transfer-progress

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

COPY --from=build /app/ai-gateway/target/ai-gateway-*.jar app.jar

EXPOSE 8080

USER appuser

ENTRYPOINT ["java", "-jar", "app.jar"]