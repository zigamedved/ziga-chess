# Contributing

## Clone

The game corpus is Git LFS. Install the client before the first pull so `games/games.json.zip` is the zip, not a pointer file.

```bash
git lfs install
git clone https://github.com/zigamedved/ziga-chess.git
cd ziga-chess
git lfs pull
```

After `git lfs pull` the zip is about 372 MB. Unit tests do not need it. `make index` and `make index-sample` do.

For a code review, `git clone --depth 1` skips older commits. Those commits still contain Lucene segments and Stockfish binaries. This repo does not rewrite that history.

## Not in git

- **Stockfish.** The image downloads it during `docker compose up --build`. On the host: `./scripts/download_stockfish.sh` (binary lands in `python-server/engine/`).
- **Lucene index.** `make index-sample` (about 100 games) or `make index` (full zip). Output is `java-server/indexedFiles/`. Restart the server after a full rebuild.
- **`java-server/target/`.** `mvn test` writes class files there. They are gitignored. Do not `git add` them.

## Tests

From the repo root:

```bash
make test
```

Python tests mock Stockfish. Java tests build a temporary fixture index.
