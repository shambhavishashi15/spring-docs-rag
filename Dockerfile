# syntax=docker/dockerfile:1

# Build stage: package the app with the Maven wrapper, then split the jar into layers.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
# Dependencies first: this layer is reused until pom.xml (or the wrapper) changes.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN ./mvnw -B -q dependency:go-offline
COPY src src
# Tests run in CI (they need Docker for Testcontainers), not in the image build.
RUN ./mvnw -B -q package -DskipTests \
    && cp target/*.jar app.jar \
    && java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# Run stage: JRE only, non-root user, one COPY per layer so unchanged dependency layers stay cached.
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 1001 app
USER app
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
# The docs HTML is not baked into the image (it isn't redistributed): mount it at /app/docs.
ENV DOCS_DIR=/app/docs
EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
