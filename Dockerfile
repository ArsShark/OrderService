# ---- Build stage: compiles the application and packages the executable jar ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/ src/

# The BuildKit cache mount keeps the Maven repository between builds, so the
# dependencies are downloaded once. Tests run in the CI pipeline, not in the image build.
# No secret is needed here: the database password and the internal key are read only when the
# application starts.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -ntp package -Dmaven.test.skip=true -Djacoco.skip=true

# ---- Runtime stage: JRE only, no Maven and no sources ----
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# Run as a non-root user. The logs directory is relative to /app (Log4j2 default "logs")
# and must belong to that user, otherwise the application cannot write its log file.
RUN groupadd --system app \
    && useradd --system --gid app --no-create-home app \
    && mkdir logs \
    && chown app:app logs

COPY --from=build --chown=app:app /workspace/target/*.jar app.jar

USER app
EXPOSE 8083

# Exec form: the JVM is PID 1 and receives SIGTERM, so "docker stop" shuts Spring down gracefully.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
