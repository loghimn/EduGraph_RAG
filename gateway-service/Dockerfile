FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN for attempt in 1 2 3; do \
      if mvn -B -ntp -U dependency:go-offline; then exit 0; fi; \
      if [ "$attempt" -eq 3 ]; then exit 1; fi; \
      echo "Dependency download failed; retrying in 5 seconds ($attempt/3)." >&2; \
      sleep 5; \
    done
COPY src ./src
RUN mvn -B -ntp -DskipTests package && cp target/*.jar /app.jar

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* && useradd --system --uid 10001 app
WORKDIR /app
COPY --from=build /app.jar ./app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
