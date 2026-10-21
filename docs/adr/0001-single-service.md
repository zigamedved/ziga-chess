# ADR 0001: One Lucene-backed analyse service

- **Status:** Accepted
- **Date:** 2026-10-14
- **Issue:** [#2](https://github.com/zigamedved/ziga-chess/issues/2)

This record chooses the target shape. Slices 1–3 have landed: one JVM on port **8081** runs `python -m chessapp.cli` for features and Stockfish, then searches Lucene in-process. Slice 4 (features in Java) is still optional.

## Context

The retrieval core is Lucene 8.11 on the JVM: BM25 over `static`, `other`, and `dynamic`, with game metadata in stored fields (`LuceneSearchService`). The index is built by `make index` (`scripts/prepare_lucene_docs.py` → `IndexBuilder`). MongoDB is already gone.

The public API used to be Python (`chessapp`, gunicorn `main:app`). It ran Stockfish, built the feature string, and HTTP POSTed that string to Java. Both processes checked the same `AUTH_USERNAME` / `AUTH_PASSWORD`. Compose wired `JAVA_SERVICE_URL` and `APP_PREFIX`.

Issue #2's body still describes a Mongo enrichment hop and says the index builder is not in the repo. Both are out of date. Do not reintroduce Mongo or a second index pipeline while consolidating.

Two HTTP services cost a network hop, duplicated Basic auth, and two images. The thesis ranking depends on this Lucene index and this tokenizer, so the searcher should stay on Lucene 8.11.

## Options

### A — Single JVM service (chosen)

One process owns `POST /analyse`, health, auth, and the `SearcherManager`. Lucene stays in Java. Feature extraction and Stockfish stay in the existing Python code, called in-process (a CLI or equivalent), until a later port. One image, one port, one auth check.

### B — Single Python service via PyLucene

Keeps the feature code in-process and embeds a JVM. PyLucene is painful to pin in Docker, and the index format must match Lucene 8.11 exactly. Rejected: the searcher we already trust is the Java one.

### C — Python plus OpenSearch or Elasticsearch

One app process, but BM25 moves to another cluster. Rankings on the README sample FEN would need to be revalidated, and ops get heavier. Rejected for a thesis index that already fits in a local Lucene directory.

Keeping two HTTP services is the current deployment, not the target. Compose already exists; it is not a reason to stay split.

## Decision

Option A.

- Lucene 8.11, `BM25Similarity`, whitespace tokens, stored metadata, and `LUCENE_TOP_K` stay as they are.
- The public process is the JVM. It listens on one port and serves `POST /analyse` plus unprefixed `GET /ping`.
- During migration, that process runs the Python feature/Stockfish code as a subprocess. It does not HTTP-call a second service.
- Auth stays env-based Basic auth, checked once at the public edge.
- `POST /analyse` defaults to `application/json` ([openapi.yaml](../../openapi.yaml)). `Accept: text/plain` returns the thesis body.
- The public port stays **8081**, which is what the README curl uses. Java's current 8080 goes away with the `/position` hop.

## Consequences

- Rankings stay comparable because the same index, analyzer, and BM25 setup answer the query. A later PR should pin that with the existing fixture index, not a new engine.
- Python feature code can move without a PyLucene build. The Stockfish binary still lives in `engine/` and is downloaded, not committed.
- Compose is one service. `JAVA_SERVICE_URL` is not part of that path. The Flask app remains for tests and for running `main:app` by hand.
- A full port of feature extraction to Java is optional and later. It is not required to close the epic.

## Migration slices

Each slice is its own PR. This ADR did not implement them.

1. **Feature CLI.** Done. `python -m chessapp.cli` (from `python-server/`) reads one FEN from argv or stdin and prints one JSON object: `fen`, `features`, `pv1`, `pv2` (via `chessapp.response.format_pv`). It does not import the Flask app, so it does not need `AUTH_*`.
2. **JVM `/analyse`.** Done. Java `POST /analyse` (same `APP_PREFIX` as `/position`) runs the CLI, then `LuceneSearchService.searchSimilar`. The default body is JSON. `Accept: text/plain` still uses `AnalyseText` / `format_analyse_text`. One auth check.
3. **Drop the hop.** Done. One image, port 8081. Compose does not run the Python HTTP proxy. `POST /position` stays on the same process for a raw feature string; clients use `POST /analyse`.
4. **Optional.** Reimplement features in Java and delete the CLI if the subprocess shows up in latency.

## Out of scope

UI, the JSON API in #7, and product issues #17–#23.
