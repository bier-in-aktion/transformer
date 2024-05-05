FROM maven:3.8.6-openjdk-8-slim as builder

WORKDIR /usr/src/app

COPY pom.xml .
COPY src ./src

RUN --mount=type=cache,target=/root/.m2 \
    mvn clean package -DskipTests

FROM openjdk:8-jre-slim

WORKDIR /usr/src/app

COPY --from=builder /usr/src/app/target/transformer-*-jar-with-dependencies.jar ./app.jar

CMD ["java", "-jar", "app.jar" ]
