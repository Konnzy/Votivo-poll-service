FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY . .
RUN chmod +x gradlew && ./gradlew bootJar --no-daemon

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /workspace/build/libs/*-SNAPSHOT.jar app.jar
COPY --from=build /workspace/build/classes/java/main/ua/votivo/poll/ops/Healthcheck.class /app/healthcheck/ua/votivo/poll/ops/Healthcheck.class
USER 10001:10001
EXPOSE 8082
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=3 CMD ["java", "-cp", "/app/healthcheck", "ua.votivo.poll.ops.Healthcheck"]
ENTRYPOINT ["java", "-jar", "app.jar"]
