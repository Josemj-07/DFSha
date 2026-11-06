FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY proto/ proto/
COPY client/ client/
WORKDIR /src/client
RUN --mount=type=cache,target=/root/.gradle chmod +x gradlew && ./gradlew --no-daemon installDist -x test

FROM eclipse-temurin:21-jre
RUN useradd --create-home --uid 10001 --user-group dfs
COPY --from=build /src/client/build/install/dfs /opt/dfs
ENV PATH="/opt/dfs/bin:${PATH}"
USER dfs
WORKDIR /home/dfs
ENTRYPOINT ["dfs"]
