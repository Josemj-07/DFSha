FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY proto/ proto/
COPY nodes/ nodes/
WORKDIR /src/nodes
RUN --mount=type=cache,target=/root/.gradle chmod +x gradlew && ./gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --user-group dfs && mkdir -p /data && chown dfs:dfs /data
COPY --from=build /src/nodes/build/libs/*-SNAPSHOT.jar /app/app.jar
USER dfs
VOLUME /data
EXPOSE 50051 50052
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
