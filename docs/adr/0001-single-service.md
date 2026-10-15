# ADR 0001: One Lucene-backed analyse service

- **Status:** Accepted
- **Date:** 2026-10-14
- **Issue:** [#2](https://github.com/zigamedved/ziga-chess/issues/2)

This record chooses the target shape. It does not change the running system. Today a request still goes Python `:8081` `POST /analyse` → Java `:8080` `POST /position`.

## Context

The retrieval core is Lucene 8.11 on the JVM: BM25 over `static`, `other`, and `dynamic`, with game metadata in stored fields (`LuceneSearchService`). The index is built by `make index` (`scripts/prepare_lucene_docs.py` → `IndexBuilder`). MongoDB is already gone.

The public API is Python (`chessapp`, gunicorn `main:app`). It runs Stockfish, builds the feature string, and HTTP POSTs that string to Java. Both processes check the same `AUTH_USERNAME` / `AUTH_PASSWORD`. Compose wires `JAVA_SERVICE_URL` and `APP_PREFIX`.

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
- `POST /analyse` stays `text/plain` until [#7](https://github.com/zigamedved/ziga-chess/issues/7). Do not change the response shape in the consolidation PRs.
- The public port stays **8081**, which is what the README curl uses. Java's current 8080 goes away with the `/position` hop.

## Consequences

- Rankings stay comparable because the same index, analyzer, and BM25 setup answer the query. A later PR should pin that with the existing fixture index, not a new engine.
- Python feature code can move without a PyLucene build. The Stockfish binary still lives in `engine/` and is downloaded, not committed.
- Two containers remain until the migration slices below land. Compose and `JAVA_SERVICE_URL` stay required until the hop is deleted.
- A full port of feature extraction to Java is optional and later. It is not required to close the epic.

## Migration slices

Each slice is its own PR. None of them are done by this ADR.

1. **Feature CLI.** `python -m chessapp.cli` (from `python-server/`) reads one FEN from argv or stdin and prints one JSON object: `fen`, `features` (the string sent to `POST /position`), `pv1`, `pv2` (same text as the HTTP body, via `chessapp.response.format_pv`). It does not import the Flask app, so it does not need `AUTH_*`. HTTP `/analyse` still calls `analyse_position`.
2. **JVM `/analyse`.** Java runs that CLI, then `LuceneSearchService.searchSimilar`, and returns today's plain-text body. One auth check. Golden-path test against the fixture index.
3. **Drop the hop.** Remove `POST /position` as a public dependency, the Python HTTP proxy, and the second compose service. One image, port 8081, README one-command run updated.
4. **Optional.** Reimplement features in Java and delete the CLI if the subprocess shows up in latency.

## Out of scope

UI, the JSON API in #7, and product issues #17–#23.
