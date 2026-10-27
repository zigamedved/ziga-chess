# Architecture: similar rook-endgame search

This project finds **similar rook endgames** for a position (FEN). It extracts chess features, searches a Lucene index with BM25, and returns similar games plus principal variations.

This page describes the **current** data/query flow and how it differs from the **legacy** (pre–PR #15) design.

The running server is one JVM process (Lucene in-process, Python features via a subprocess). That shape is [ADR 0001](adr/0001-single-service.md). A later optional slice can reimplement features in Java.

---

## At a glance

| | Legacy | Current |
|---|--------|---------|
| Game corpus | `games/games.json.zip` (+ copy in Mongo) | `games/games.json.zip` only |
| Search index | Prebuilt Lucene files committed to git | Built locally with `make index` |
| Similar-game metadata | MongoDB `chessGames.games` | **Stored fields on Lucene documents** |
| Runtime deps for `/position` | Lucene **and** Mongo | Lucene index on disk only |
| Adding games | Update zip **and** re-index **and** reload Mongo | Append to zip → `make index` → restart Java |

---

## Current architecture

### 1. Offline: build the index

Source of truth is the zip (NDJSON of games). Features are computed from each game’s `endgameFEN` and PV lines; metadata is written **into** the same Lucene document.

```mermaid
flowchart LR
  subgraph corpus [Corpus]
    ZIP["games/games.json.zip<br/>(Git LFS)"]
  end

  subgraph build [make index]
    PREP["scripts/prepare_lucene_docs.py<br/>static / other / dynamic<br/>+ metadata fields"]
    BUILD["IndexBuilder (Java)<br/>Lucene 8.11"]
  end

  subgraph disk [On disk — not committed]
    IDX["java-server/indexedFiles/"]
  end

  ZIP --> PREP --> BUILD --> IDX
```

**What each Lucene document holds**

| Role | Fields |
|------|--------|
| Search (tokenized) | `static`, `other`, `dynamic` |
| Stored metadata (for the API response) | `name`, `White`, `Black`, `Result`, `WhiteElo`, `BlackElo`, `PGN`, `PV1`, `PV2`, `endgameFEN` |
| Filters | `Result` is a `StringField`. `elo` is an `IntPoint`: the average of the ratings present |

If you add games: append JSON lines to the corpus, refresh the zip, run `make index` again (full rebuild today), restart the Java server. The running process keeps one `SearcherManager` and calls `maybeRefresh()` on each search, so an in-place commit is picked up. `make index` opens the index with `OpenMode.CREATE` (it wipes the directory), so restart Java after a full rebuild.

### 2. Online: analyse a position

```mermaid
sequenceDiagram
  participant Client
  participant Java as JVM :8081<br/>POST /analyse
  participant CLI as python -m chessapp.cli
  participant SF as Stockfish
  participant Lucene as Lucene index

  Client->>Java: FEN + Basic auth
  Java->>CLI: one FEN
  CLI->>SF: evaluate / PVs
  CLI-->>Java: JSON features, pv1, pv2
  Java->>Lucene: BM25 over static/other/dynamic
  Lucene-->>Java: top‑K hits<br/>(score + stored metadata)
  Java-->>Client: plain-text PVs + similar games
```

Auth is checked once, on the JVM. The image contains the JRE, `chessapp`, and the Stockfish binary. `CHESSAPP_DIR` is the directory that holds the `chessapp` package. `POST /position` is still registered on the same port for a raw feature string; the client path is `POST /analyse`.

The default `/analyse` body is JSON (`openapi.yaml`), including `similarPgn` for the current similar-game set and an `explanation` of the matched feature tokens on each hit. Optional `result`, `minElo`, and `maxElo` are Lucene filters on that same BM25 query. An empty filter leaves the query unchanged. `Accept: text/plain` returns the thesis text. Errors are JSON objects with `error` and `code`. `GET /` serves the board from the same process. `?fen=` on that page opens a shared position.

Each `/analyse` starts a new CLI process, so the Flask LRU and the `engine_busy` lock do not apply to compose. They still apply if you run `main:app` yourself.

Lucene does **both** similarity search **and** serving game data. MongoDB is not involved.

```mermaid
flowchart TB
  Client["Client"]
  Java["JVM :8081<br/>auth + Lucene"]
  CLI["python -m chessapp.cli<br/>features + Stockfish"]
  Idx["indexedFiles/<br/>features + metadata"]

  Client -->|"POST /analyse"| Java
  Java --> CLI
  Java --> Idx
  Idx -->|"hits with White/Black/PGN/…"| Java
  Java --> Client
```

---

## Legacy architecture (before PR #15)

The index only stored enough to identify a game (effectively a path/`name`). After BM25, Java queried **MongoDB** to load PGN, players, PVs, etc.

```mermaid
flowchart TB
  Client["Client"]
  Py["Python Flask"]
  Java["Java HttpServer"]
  Idx["Committed indexedFiles/<br/>features + path/name only"]
  Mongo["MongoDB<br/>chessGames.games"]

  Client --> Py
  Py -->|"feature string"| Java
  Java --> Idx
  Idx -->|"name + score"| Java
  Java -->|"$in names"| Mongo
  Mongo -->|"White, Black, PGN, …"| Java
  Java --> Py
  Py --> Client
```

```mermaid
sequenceDiagram
  participant Py as Python /analyse
  participant Java as Java /position
  participant Lucene as Lucene
  participant Mongo as MongoDB

  Py->>Java: feature string
  Java->>Lucene: BM25 search
  Lucene-->>Java: names + scores
  Java->>Mongo: find by name
  Mongo-->>Java: game documents
  Java-->>Py: merged JSON
```

**Problems with legacy**

- Two sources of truth (zip/index vs Mongo) could drift
- Server would not run without a private Mongo URI and data
- Large Lucene segments + `write.lock` lived in git
- No checked-in recipe to rebuild the index after changing features

---

## Mental model

```text
  games.json.zip          make index              query time
 ┌──────────────┐      ┌─────────────┐      ┌──────────────────┐
 │ game records │ ───► │ Lucene docs │ ───► │ BM25 + hydrate   │
 │ FEN, PGN, PV │      │ features +  │      │ similar games    │
 │ players, …   │      │ metadata    │      │ (no Mongo)       │
 └──────────────┘      └─────────────┘      └──────────────────┘
```

- **Zip** = editable corpus  
- **Index** = derived artifact (gitignore; rebuild when corpus or feature code changes)  
- **Python** = understand the *query* position (Stockfish + features)  
- **Java / Lucene** = find and return *similar* games  

Python is split under `python-server/chessapp/` (`features`, `engine`, `api`, `search_client`). `main.py` is the Flask app used by tests. The Stockfish binary still lives in `python-server/engine/` (not a Python package). The image copies that package and downloads the binary beside it. Java HTTP startup is `chessServer` on port 8081; auth is `AuthFilter`, `POST /analyse` is `AnalyseHandler` (it runs `FeatureCli`), `POST /position` is `FenParser`, and BM25 is `LuceneSearchService`.  

---

## Related commands

```bash
git lfs pull
make index-sample    # small index for local smoke tests
make index           # full corpus
make test            # unit + integration tests (mocked Stockfish / fixture index)
```

See also [SECURITY.md](../SECURITY.md) for auth env vars and the root [README](../README.md) for run instructions.
