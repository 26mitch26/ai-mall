FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app

COPY pom.xml ./
COPY mall-core/pom.xml ./mall-core/
COPY mall-core/mall-common/pom.xml ./mall-core/mall-common/
COPY mall-core/mall-mbg/pom.xml ./mall-core/mall-mbg/
COPY mall-core/mall-portal/pom.xml ./mall-core/mall-portal/

RUN mvn dependency:go-offline -pl mall-core/mall-portal -am --no-transfer-progress -DskipTests=true

COPY . .
RUN mvn package -pl mall-core/mall-portal -am -DskipTests=true --no-transfer-progress

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

COPY --from=build /app/mall-core/mall-portal/target/mall-portal-*.jar app.jar

EXPOSE 8081

USER appuser

ENTRYPOINT ["java", "-jar", "app.jar"]