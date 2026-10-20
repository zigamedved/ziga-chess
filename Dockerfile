# One image: Lucene on the JVM, features and Stockfish via python -m chessapp.cli.
# Build context is the repo root (compose).
FROM public.ecr.aws/docker/library/maven:3.9-amazoncorretto-17 AS build

WORKDIR /src/java-server
COPY java-server/pom.xml .
COPY java-server/src ./src
RUN mvn -q -DskipTests package

# Bookworm still ships OpenJDK 17. The floating 3.9-slim tag moved to Trixie, which does not.
FROM public.ecr.aws/docker/library/python:3.9-slim-bookworm

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
      openjdk-17-jre-headless curl ca-certificates wget tar \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Stockfish binary lives next to the Python package, same layout as python-server/engine/.
ARG TARGETARCH
ARG STOCKFISH_TAG=sf_17
RUN set -eux; \
    arch="${TARGETARCH:-amd64}"; \
    case "${arch}" in \
      amd64) SF_ASSET="stockfish-ubuntu-x86-64.tar" ;; \
      arm64) SF_ASSET="stockfish-android-armv8.tar" ;; \
      *) echo "Unsupported TARGETARCH=${arch}"; exit 1 ;; \
    esac; \
    SF_URL="https://github.com/official-stockfish/Stockfish/releases/download/${STOCKFISH_TAG}/${SF_ASSET}"; \
    mkdir -p /app/python-server/engine /tmp/sf; \
    wget -q "${SF_URL}" -O /tmp/sf/stockfish.tar; \
    tar -xf /tmp/sf/stockfish.tar -C /tmp/sf; \
    BIN="$(find /tmp/sf -type f -name 'stockfish*' ! -name '*.tar' | head -n 1)"; \
    test -n "${BIN}"; \
    cp "${BIN}" /app/python-server/engine/stockfish; \
    chmod +x /app/python-server/engine/stockfish; \
    rm -rf /tmp/sf

COPY python-server/requirements.txt /app/python-server/
RUN pip install --no-cache-dir -r /app/python-server/requirements.txt

# chessapp.cli does not import main.py. Flask stays on the host for tests.
COPY python-server/chessapp /app/python-server/chessapp
COPY --from=build /src/java-server/target/my-maven-docker-project.jar /app/app.jar

ARG PREFIX=
ENV APP_PREFIX=$PREFIX
ENV PORT=8081
ENV CHESSAPP_DIR=/app/python-server
ENV STOCKFISH_PATH=/app/python-server/engine/stockfish
ENV LUCENE_INDEX_DIR=/app/indexedFiles
# Required at runtime: AUTH_USERNAME, AUTH_PASSWORD
# Optional: LUCENE_TOP_K (code default 20). Index is mounted, not baked in.

EXPOSE 8081

CMD ["java", "-jar", "/app/app.jar"]
