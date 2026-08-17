## Build stage: compiles and packages the Spring Boot jar using the Maven wrapper, so the
## resulting image does not depend on Maven or the JDK being installed on the host.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw
# Warm the dependency cache in its own layer so source-only changes don't re-download the world.
RUN ./mvnw -B dependency:go-offline

COPY src ./src
RUN ./mvnw -B -DskipTests package

## Runtime stage: a minimal JRE image containing only the built jar.
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

RUN useradd --system --no-create-home appuser
COPY --from=build /workspace/target/*.jar app.jar
USER appuser

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
