# Multi-stage: build with Maven, run on a slim JRE.
FROM public.ecr.aws/docker/library/maven:3.9-amazoncorretto-17 AS build

ARG PREFIX=
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

FROM public.ecr.aws/docker/library/eclipse-temurin:17-jre

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

ARG PREFIX=
ENV APP_PREFIX=$PREFIX
# Required at runtime: AUTH_USERNAME, AUTH_PASSWORD
# Optional: LUCENE_INDEX_DIR (default ./indexedFiles), LUCENE_TOP_K (default 50)

WORKDIR /app
COPY --from=build /app/target/my-maven-docker-project.jar /app/app.jar

# Index is mounted at runtime (see docker-compose.yml). Not baked into the image.
EXPOSE 8080

CMD ["java", "-jar", "/app/app.jar"]
