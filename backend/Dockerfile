# syntax=docker/dockerfile:1.6
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /build
COPY pom.xml mvnw* ./
COPY .mvn .mvn 2>/dev/null || true
COPY src src
# Download deps in a separate layer for caching
RUN --mount=type=cache,target=/root/.m2 \
    sh -c 'if [ -x ./mvnw ]; then ./mvnw -B -q -DskipTests package; else mvn -B -q -DskipTests package; fi'
RUN cp target/fapiao-web.jar /build/app.jar

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN useradd -ms /bin/bash app
COPY --from=build /build/app.jar /app/app.jar
RUN mkdir -p /app/data/storage && chown -R app:app /app/data
ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75 -Djava.security.egd=file:/dev/./urandom"
USER app
EXPOSE 8080
ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar /app/app.jar"]