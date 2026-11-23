# One Dockerfile for every module: docker compose passes MODULE as a build arg.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY . .
ARG MODULE
RUN mvn -B -q -pl ${MODULE} -am package -DskipTests

FROM eclipse-temurin:21-jre
# wget is used by the compose healthchecks
RUN apt-get update && apt-get install -y --no-install-recommends wget \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --create-home app
ARG MODULE
COPY --from=build /build/${MODULE}/target/${MODULE}-*.jar /app/app.jar
USER app
WORKDIR /app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
