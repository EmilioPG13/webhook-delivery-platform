# Build with the JDK, run with the JRE. One image serves both roles: the SPRING_PROFILES_ACTIVE
# environment variable decides whether a container is also a worker.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /build
COPY .mvn .mvn
COPY mvnw pom.xml ./
# Dependencies first, so editing source does not download them again.
RUN ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /build/target/webhooks-*.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
