FROM maven:3.9.11-eclipse-temurin-25@sha256:407c4423cec0cf2981055bc2c6c0dc211d9605b6669279b95997f2d1c7e91e2c AS build
WORKDIR /workspace
COPY pom.xml ./pom.xml
COPY backend ./backend
RUN mvn --batch-mode --no-transfer-progress -f backend/pom.xml \
    -pl resilientpay-service -am -DskipTests package

FROM eclipse-temurin:25-jre@sha256:f9e65324a37f28209ce7dd0e5149a7aa954520ed936fb87813cf6ded2400a112
RUN useradd --system --uid 10001 --create-home resilientpay
WORKDIR /app
COPY --from=build /workspace/backend/resilientpay-service/target/resilientpay-service-*.jar /app/application.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Djava.security.egd=file:/dev/urandom", "-jar", "/app/application.jar"]
