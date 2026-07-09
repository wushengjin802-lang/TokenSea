FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/control-plane-0.1.0.jar /app/app.jar
EXPOSE 39211
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
