# Multi-stage build for ARM64 (Raspberry Pi 4)
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app

COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

# Split the fat jar into Spring Boot layers so a deploy that only changes our code
# re-downloads a small application layer instead of every library.
RUN java -Djarmode=tools -jar target/mal2cy-sync-*.jar extract --layers --launcher --destination target/extracted

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /app/target/extracted/dependencies/ ./
COPY --from=build /app/target/extracted/spring-boot-loader/ ./
COPY --from=build /app/target/extracted/snapshot-dependencies/ ./
COPY --from=build /app/target/extracted/application/ ./
EXPOSE 8080
ENTRYPOINT ["java", "-Xmx512m", "-Xms256m", "org.springframework.boot.loader.launch.JarLauncher"]
