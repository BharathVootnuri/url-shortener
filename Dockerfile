# Backend only. The Angular UI is not part of this image.
# Build:  docker build -t url-shortener:0.0.1 .
# Run:    docker run --rm -p 8080:8080 --env-file .env url-shortener:0.0.1
# The image defaults to the prod profile so it cannot silently start on in-memory H2.

FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY src src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --home /app --shell /usr/sbin/nologin app

COPY --from=build --chown=app:app /src/target/url-shortener.jar /app/app.jar

USER 10001
EXPOSE 8080

ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -Dfile.encoding=UTF-8"

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
