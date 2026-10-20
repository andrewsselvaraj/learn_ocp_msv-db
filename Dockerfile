# ---- Stage 1: build the jar with Maven ----
FROM docker.io/library/maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# ---- Stage 2: small runtime image ----
# UBI OpenJDK images run fine under OpenShift's random, non-root UID.
FROM registry.access.redhat.com/ubi9/openjdk-17-runtime:latest
WORKDIR /deployments
COPY --from=build /app/target/db-console.jar /deployments/app.jar
EXPOSE 8080
ENV JAVA_OPTS_APPEND="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS_APPEND -jar /deployments/app.jar"]
