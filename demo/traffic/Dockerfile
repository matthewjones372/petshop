# The demo's visitors (loadtest/src/test/kotlin/petshop/load/DemoTraffic.kt), built from this repository and run on
# their own, so `docker compose up` brings traffic with it. Built from the repository root: see docker-compose.yml.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew --no-daemon --quiet :loadtest:installDemoTraffic

FROM eclipse-temurin:25-jre
COPY --from=build /src/loadtest/build/demo-traffic/lib /app/lib
ENTRYPOINT ["java", "-cp", "/app/lib/*", "petshop.load.DemoTrafficKt"]
