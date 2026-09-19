# ---- Build stage: compile and run the tests on Linux --------------------------------
# Running the tests here also proves the Tree-sitter native library loads on Linux.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src

# Download dependencies in their own layer so code changes don't re-download them.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q verify && cp target/codeatlas-backend-*.jar /app.jar

# ---- Runtime stage -------------------------------------------------------------------
FROM eclipse-temurin:17-jre
WORKDIR /app

RUN useradd --system --create-home codeatlas
USER codeatlas

COPY --from=build /app.jar app.jar

# Free hosting tiers have ~512 MB RAM: size the heap from the container limit, not the host.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
