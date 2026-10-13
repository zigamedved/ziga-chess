FROM public.ecr.aws/docker/library/python:3.9-slim

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
      curl ca-certificates wget tar \
    && rm -rf /var/lib/apt/lists/*

# Download Stockfish for the image architecture (do not commit binaries to git).
ARG TARGETARCH=amd64
ARG STOCKFISH_TAG=sf_17
RUN set -eux; \
    case "${TARGETARCH}" in \
      amd64) SF_ASSET="stockfish-ubuntu-x86-64.tar" ;; \
      arm64) SF_ASSET="stockfish-android-armv8.tar" ;; \
      *) echo "Unsupported TARGETARCH=${TARGETARCH}"; exit 1 ;; \
    esac; \
    SF_URL="https://github.com/official-stockfish/Stockfish/releases/download/${STOCKFISH_TAG}/${SF_ASSET}"; \
    mkdir -p /app/engine /tmp/sf; \
    wget -q "${SF_URL}" -O /tmp/sf/stockfish.tar; \
    tar -xf /tmp/sf/stockfish.tar -C /tmp/sf; \
    BIN="$(find /tmp/sf -type f -name 'stockfish*' ! -name '*.tar' | head -n 1)"; \
    test -n "${BIN}"; \
    cp "${BIN}" /app/engine/stockfish; \
    chmod +x /app/engine/stockfish; \
    rm -rf /tmp/sf

COPY requirements.txt /app/
RUN pip install --no-cache-dir -r requirements.txt

COPY main.py /app/
COPY chessapp /app/chessapp

ARG PREFIX=
ENV APP_PREFIX=$PREFIX
ENV STOCKFISH_PATH=/app/engine/stockfish
# Required at runtime: AUTH_USERNAME, AUTH_PASSWORD
# Optional: JAVA_SERVICE_URL (compose sets http://java-server:8080/position)

# main.py / gunicorn bind to 8081 (java-server uses 8080).
EXPOSE 8081

# Single worker: Stockfish, the engine lock, and the analyse cache are in-process.
CMD ["gunicorn", "-b", "0.0.0.0:8081", "-w", "1", "--timeout", "180", "main:app"]
