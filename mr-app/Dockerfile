FROM maven:3.9.6-eclipse-temurin-8 AS builder

WORKDIR /workspace

COPY pom.xml ./pom.xml
COPY mr-core/pom.xml ./mr-core/pom.xml
COPY mr-app/pom.xml ./mr-app/pom.xml
COPY src ./src

RUN mvn -f pom.xml -pl mr-app -am -DskipTests package

FROM eclipse-temurin:8-jre

WORKDIR /opt/mr-app

ENV TZ=Asia/Shanghai

COPY --from=builder /workspace/mr-app/target/mr-app-1.0-SNAPSHOT.jar /opt/mr-app/app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/opt/mr-app/app.jar"]

