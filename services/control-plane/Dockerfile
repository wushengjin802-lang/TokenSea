FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
# Docker Desktop 网络环境可能优先解析 IPv6，强制 Maven 使用 IPv4 访问中央仓库。
ENV MAVEN_OPTS="-Djava.net.preferIPv4Stack=true"
COPY maven-settings.xml /root/.m2/settings.xml
RUN mvn -q -s /root/.m2/settings.xml -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -s /root/.m2/settings.xml -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/control-plane-0.1.0.jar /app/app.jar
EXPOSE 39211
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
