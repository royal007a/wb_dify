FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY . .
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:17-jre-jammy
RUN groupadd --system hify && useradd --system --gid hify hify
WORKDIR /app
COPY --from=build /workspace/hify-app/target/hify-app-0.1.0-SNAPSHOT.jar app.jar
USER hify
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
