#!/usr/bin/env bash
# Download a Stockfish binary into python-server/engine/ for local (non-Docker) runs.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="${ROOT}/python-server/engine"
TAG="${STOCKFISH_TAG:-sf_17}"
mkdir -p "${OUT_DIR}"

uname_s="$(uname -s)"
uname_m="$(uname -m)"
case "${uname_s}-${uname_m}" in
  Darwin-arm64) ASSET="stockfish-macos-m1-apple-silicon.tar" ;;
  Darwin-x86_64) ASSET="stockfish-macos-x86-64.tar" ;;
  Linux-x86_64|Linux-amd64) ASSET="stockfish-ubuntu-x86-64.tar" ;;
  Linux-aarch64|Linux-arm64) ASSET="stockfish-android-armv8.tar" ;;
  *)
    echo "Unsupported platform: ${uname_s} ${uname_m}" >&2
    exit 1
    ;;
esac

URL="https://github.com/official-stockfish/Stockfish/releases/download/${TAG}/${ASSET}"
TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT

echo "Downloading ${URL}"
curl -fsSL "${URL}" -o "${TMP}/sf.tar"
tar -xf "${TMP}/sf.tar" -C "${TMP}"
BIN="$(find "${TMP}" -type f -name 'stockfish*' ! -name '*.tar' | head -n 1)"
test -n "${BIN}"
cp "${BIN}" "${OUT_DIR}/stockfish"
chmod +x "${OUT_DIR}/stockfish"
echo "Installed ${OUT_DIR}/stockfish"
