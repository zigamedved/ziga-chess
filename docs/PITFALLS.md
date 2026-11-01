# Pitfalls

Living log of wrong assumptions that broke CI, a local run, a user-facing request, or an agent session on this repo.

Read this before changing compose, auth, Lucene, Stockfish, Docker, or tests. When you uncover a new one, add an entry at the top of the matching section. Keep old entries. The mistake is the lesson.

## How to add an entry

```markdown
### YYYY-MM-DD — short title

- **Assumed:** what seemed true
- **Actually:** what the code or the tool did
- **Broke:** CI job, request, or workflow that failed
- **Do this:** the check or fix that avoids a repeat
```

Date is the day the mistake was found, not the day the code was first written.

## CI and runtime

### 2026-10-30 — reading a CLI's stdout before closing its stdin deadlocks

- **Assumed:** `FeatureCli`'s pattern (start the process, then `readAllBytes` on stdout) works when the PGN is passed on stdin.
- **Actually:** the child waits until stdin closes, and the parent waits on stdout. `POST /analyse` hangs until the CLI timeout.
- **Broke:** would hang a PGN analyse request.
- **Do this:** write the PGN, close the child's stdin, then read stdout. `EndgameCli` does that. Do not copy the FEN argv pattern for a multi-line PGN.

### 2026-10-27 — a stored Result cannot be a Lucene filter

- **Assumed:** `StoredField("Result")` is enough to filter similar games by result, and Elo can be filtered from the same stored string.
- **Actually:** stored fields are not indexed. A `TermQuery` on `Result` matches nothing until the field is a `StringField`. An Elo bound needs an `IntPoint` named `elo`. An index built before that returns no hits for those filters.
- **Broke:** would have been an empty similar-game list after a result filter on the current corpus.
- **Do this:** index `Result` with `StringField` and `elo` with `IntPoint`. Run `make index` after the schema change. Leave the filter off when the request omits it, so the BM25 query stays the same.

### 2026-10-27 — a nowrap history row widens the phone layout

- **Assumed:** `grid-template-columns: 1fr` under 1100px keeps the dashboard inside the viewport.
- **Actually:** a grid item's default `min-width` is `auto`, so it uses the row's min-content. A history FEN with `white-space: nowrap` is wider than a phone. The page scrolls sideways and the board grows with it.
- **Broke:** the board at a 390px layout width. `scrollWidth` was larger than the viewport.
- **Do this:** set `min-width: 0` on `.dashboard > *` and on the history and game buttons. Keep the ellipsis on the FEN.

### 2026-10-26 — board rows follow the piece glyph, not the square

- **Assumed:** a square grid with eight `1fr` columns and `aspect-ratio: 1` makes every rank the same height.
- **Actually:** row tracks were `auto`. A rank whose glyphs are short (pawns only, rank 5 in the sample) keeps a smaller min-content height, so that rank looks squashed. Ranks with a rook or king stay taller.
- **Broke:** the board on `GET /`. The fourth rank from the top was shorter than the others.
- **Do this:** set `grid-template-rows: repeat(8, minmax(0, 1fr))` and `min-height: 0` on `.sq`. Size the glyph with `calc(100cqi / 8 * 0.72)` on `.sq`, and put `container-type: inline-size` on `.board`. A query on the square itself reads the viewport, not the square.

### 2026-10-22 — a healthy container can still be the pre-board jar

- **Assumed:** starting compose after the board commit serves `GET /` from the current tree.
- **Actually:** the image jar had no `StaticFiles` and no `web/`. `GET /` was the JDK page `404 No context found for request`. `GET /ping` still returned `pong`, and the healthcheck only curls `/ping`.
- **Broke:** `http://127.0.0.1:8081/` locally, while the container was Up (healthy).
- **Do this:** if `/ping` is `pong` and `/` is that 404, rebuild so `COPY java-server/src` runs again (`docker compose up --build`). A cached Maven stage from before the board stays healthy and still has no page.

### 2026-10-21 — the board is files inside the one JVM, not a second server

- **Assumed:** a chessboard UI needs nginx, a Vite dev server, or another compose service.
- **Actually:** `chessServer` serves `src/main/resources/web` on `GET /`. `/analyse`, `/position`, and `/ping` stay on their own contexts. The image only needs the jar.
- **Broke:** nothing yet. A second service would split auth and the port again.
- **Do this:** add pages under `java-server/src/main/resources/web`. Do not add a compose service for the UI.

### 2026-10-21 — `.gitignore` does not untrack `java-server/target/*.class`

- **Assumed:** adding `java-server/target/` to `.gitignore` removes `chessServer.class` and its inner classes from the repo.
- **Actually:** those four files were committed first. Git keeps tracking them. Their bytes are the pre-split `chessServer`, so an incremental `mvn test` can load that class when the `.class` file is newer than `chessServer.java` and fail with `NoSuchMethodError` for `parsePort`.
- **Broke:** a local `mvn test` on the hygiene branch, before `mvn clean`. CI was fine because it compiles from source into an empty `target/`.
- **Do this:** `git rm --cached` the tracked class files (done on the hygiene PR). Do not `git add -f` them. If tests fail with `NoSuchMethodError` on `chessServer`, run `mvn clean test`. A full clone still downloads old Lucene segments and Stockfish binaries from history; do not rewrite that history unless the owner asks. `git clone --depth 1` is the small tree.

### 2026-10-20 — `public.ecr.aws` pull quota fails compose-smoke

- **Assumed:** `public.ecr.aws/docker/library/...` is a safe mirror of Docker Hub for GitHub Actions, and a 429 is a one-off.
- **Actually:** the error is `429 Too Many Requests` / `toomanyrequests: Data limit exceeded` while resolving the image manifest. It happened on PR #30 and again on the merged PR #31. The build never started. A retry in the same job does not help once the quota is spent.
- **Broke:** `compose-smoke` on PR #31. `test` was green.
- **Do this:** pull the same tags from `docker.io/library/...` (`maven:3.9-amazoncorretto-17`, `python:3.9-slim-bookworm`). Keep the Bookworm pin. Do not point the image back at `public.ecr.aws`.

### 2026-10-18 — `python:3.9-slim` no longer has OpenJDK 17

- **Assumed:** `python:3.9-slim` is still Debian Bookworm, so `apt-get install openjdk-17-jre-headless` works in the combined image.
- **Actually:** the floating tag is Debian Trixie. Trixie has dropped OpenJDK 17. The package error points at OpenJDK 21.
- **Broke:** the local image build for the single-service Dockerfile, before Stockfish or the jar were copied.
- **Do this:** pin `python:3.9-slim-bookworm` (or another base that still ships a Java 17 JRE). Do not switch the server to Java 21 just to follow the floating tag.

### 2026-10-18 — the public server is one image on port 8081

- **Assumed:** `POST /analyse` can stay a Python container that HTTP-calls Java on 8080, or the smoke curl can move to `:8080` once the JVM exposes `/analyse`.
- **Actually:** compose builds the root `Dockerfile`. That image is a JRE plus `python3`, `chessapp`, and Stockfish. The JVM listens on 8081 (`PORT`), sets `CHESSAPP_DIR=/app/python-server`, and runs `python -m chessapp.cli`. There is no second service and no `JAVA_SERVICE_URL`.
- **Broke:** nothing in this slice. Pointing smoke at `:8080`, or shipping a JRE-only image, fails the CLI or the healthcheck.
- **Do this:** keep one compose service. Curl `http://127.0.0.1:8081/ping` and `:8081/analyse`. `APP_PREFIX` prefixes `/analyse` and `/position` only. Healthchecks stay on unprefixed `/ping`. The Stockfish binary is `/app/python-server/engine/stockfish`, not a Python package. Do not `COPY` `main.py` into this image: the process is the jar, and `python -m chessapp.cli` does not import it. The older "COPY main.py / gunicorn" note is about the removed Python image.

### 2026-10-15 — Java `POST /analyse` does not run inside the current image

- **Assumed:** once `chessServer` exposes `/analyse`, compose-smoke and the README curl can move from Python `:8081` to Java `:8080`.
- **Actually:** the Java image is a JRE plus the jar. `FeatureCli` needs `python3` and `chessapp` (`CHESSAPP_DIR` or cwd). Those exist on a dev machine and in the Python image, not in `java-server/Dockerfile`.
- **Broke:** nothing yet. Switching the smoke curl before the images are merged would fail the CLI at startup of the request.
- **Do this:** keep compose on Python `:8081` until the single-image slice. Set `CHESSAPP_DIR` to `python-server/` when calling Java `/analyse` from the host.

### 2026-10-12 — `docker compose down` still needs `AUTH_*`

- **Assumed:** the compose-smoke job only needs `AUTH_USERNAME` / `AUTH_PASSWORD` on `docker compose up`. Teardown under `if: always()` can run without them.
- **Actually:** `docker-compose.yml` uses `${AUTH_USERNAME:?...}` and `${AUTH_PASSWORD:?...}`. Compose interpolates those on every command, including `down`. A step that does not export the vars, and has no `.env`, fails with `required variable AUTH_USERNAME is missing a value`.
- **Broke:** PR #24 CI. `test`, compose up, and `/analyse` were green. The job failed on **Compose down** after a successful smoke.
- **Do this:** write `.env` once at the start of the job and let compose load it for both `up` and `down`. Any new compose step needs the same env. Do not add another `:?` variable without updating that `.env` write.

### 2026-10-12 — an indented YAML heredoc puts spaces in `.env`

- **Assumed:** a `cat > .env <<'EOF'` indented under a GitHub Actions `run: |` block writes the values as typed.
- **Actually:** the spaces used to indent the YAML script become part of each line. `AUTH_USERNAME` then does not match the curl user.
- **Broke:** would have failed compose interpolation or the analyse curl if the `sed` strip had not been added in the same workflow.
- **Do this:** prefer a flush-left heredoc or `printf`. If the script stays indented, strip leading whitespace before compose runs. The current workflow does that with `sed`; do not delete the strip when editing the step.

### 2026-10-12 — `APP_PREFIX` must be on the Python → Java hop

- **Assumed:** healthchecks on `/ping` and a hardcoded `JAVA_SERVICE_URL=http://java-server:8080/position` stay valid when `APP_PREFIX` is set.
- **Actually:** both servers register unprefixed `/ping` **and** move `/position` and `/analyse` under the prefix. The hop has to be `http://java-server:8080${APP_PREFIX}/position`. Healthchecks stay on unprefixed `/ping`.
- **Broke:** a stack started with `APP_PREFIX=/v1/chess` would pass healthchecks and then fail `/analyse`, because Python would call Java at the unprefixed path.
- **Do this:** keep build `PREFIX`, runtime `APP_PREFIX`, and `JAVA_SERVICE_URL` aligned. Do not point a healthcheck at the prefixed ping.

### 2026-10-13 — `maybeRefresh()` does not replace a restart after `make index`

- **Assumed:** a long-lived `SearcherManager` that calls `maybeRefresh()` will see a rebuilt index without restarting Java.
- **Actually:** `IndexBuilder` opens with `OpenMode.CREATE`, which wipes the directory. `maybeRefresh()` sees an in-place commit. A full rebuild is a different event.
- **Broke:** nothing in CI. The wrong assumption would have dropped the "restart Java after `make index`" step from the docs and left a running server on a deleted index.
- **Do this:** restart Java after `make index` / `make index-sample`. `make index-update` commits into the same directory, so `maybeRefresh()` on the next search sees the new games. Leave `maybeRefresh()` for those commits.

### 2026-10-12 — Linux arm64 Stockfish asset is the Android tarball

- **Assumed:** `TARGETARCH=arm64` downloads a native Linux Stockfish.
- **Actually:** `scripts/download_stockfish.sh` and the Python image use `stockfish-android-armv8.tar` for Linux aarch64. CI runners are amd64, so CI does not catch this.
- **Broke:** a Linux aarch64 host can fail at engine start even though the image build succeeds.
- **Do this:** treat amd64 CI as not covering that asset. If a Linux aarch64 box breaks, switch the asset when Stockfish publishes a native linux-arm build.

### 2026-10-13 — one Stockfish process, one gunicorn worker, one cache

- **Assumed:** Flask's threaded server, or `gunicorn -w 2`, can share the global Stockfish instance and the analyse cache.
- **Actually:** the engine is one process. Concurrent UCI commands corrupt it. The LRU cache and the engine lock live in that process. The image is `gunicorn -w 1`. A second overlapping `/analyse` that needs the engine returns **503** `engine_busy` when `STOCKFISH_LOCK_TIMEOUT_SECONDS` is `0`. A cached FEN does not take the lock.
- **Broke:** no CI failure. Concurrent `/analyse` without the lock races Stockfish. Extra workers would each hold their own cache and their own engine.
- **Do this:** keep `-w 1`. Do not raise the worker count to "scale" analyse. Scale only with a design that gives each worker its own engine, or a pool with an explicit 503.

### 2026-10-13 — `python-server/engine/` is the binary directory

- **Assumed:** issue #11's package layout `engine/` can be a Python package at `python-server/engine/`.
- **Actually:** that directory is where `scripts/download_stockfish.sh` and the image install the Stockfish binary (`engine/stockfish`). Docker creates `/app/engine/stockfish` before `COPY`. A Python package there collides with the binary.
- **Broke:** an import named `engine` would either hide the binary path or be overwritten by the image's `mkdir` / `cp`.
- **Do this:** leave `python-server/engine/` for the binary. PR #26 puts the Python packages under `python-server/chessapp/` (`features`, `engine`, `api`, `search_client`). The image must `COPY` that package as well as `main.py`. `main:app` stays the gunicorn target.

## Tests and refactors

### 2026-10-14 — issue #2 still describes Mongo and a missing indexer

- **Assumed:** the epic's context block is the current design: Java enriches Lucene hits from Mongo, and there is no index builder in the repo.
- **Actually:** PR #15 stored metadata on Lucene documents and added `IndexBuilder` / `make index`. Compose, env auth, and the `chessapp` split landed after the issue was written.
- **Broke:** nothing in CI. Following the issue text would put Mongo back and invent a second indexing path.
- **Do this:** treat [#2](https://github.com/zigamedved/ziga-chess/issues/2) as the consolidation goal. Use [ADR 0001](adr/0001-single-service.md) and `docs/ARCHITECTURE.md` for what the system does now.

### 2026-10-13 — issue text was behind the code

- **Assumed:** #6 still opened a Lucene reader and searched 1000 hits, and the Stockfish path was hard-coded to `engine/stockfish-ubuntu-x86-64`. #11 still had no tests.
- **Actually:** top-K was already `LUCENE_TOP_K` (default 50, later 20). The binary path already checked `STOCKFISH_PATH` and several filenames. pytest, JUnit, `make test`, and CI already existed from earlier PRs. Issue #1 was already closed.
- **Broke:** nothing in CI. Implementing the ticket literally would have rewritten working code and re-closed a closed issue.
- **Do this:** read the current code and the open PR before coding a ticket. Treat the issue body as a claim to verify.

### 2026-10-13 — a Lucene "before/after" that measured two different things

- **Assumed:** timing `searchSimilar` against a loop that opens a `DirectoryReader` shows the cost of reopening the index.
- **Actually:** the shared path also parsed a query and printed `Total Results`. The open-each path ran `MatchAllDocsQuery` and did not print. On a 1-document fixture the shared path looked slower (~36 ms vs ~22 ms for 40 queries). The same query on both sides was about 20 ms vs 6 ms.
- **Broke:** the README latency note would have claimed a slowdown.
- **Do this:** compare the same query. Say that the fixture is tiny and that the real check is "same `IndexReader`, file descriptors do not grow". Do not treat a noisy microbench as the `/analyse` latency. The sample FEN is still dominated by Stockfish depth 12 and `LIMIT_DEPTH`.

### 2026-10-13 — re-exports on `main` are not the live bindings

- **Assumed:** after splitting `main.py`, `from chessapp.engine import LIMIT_DEPTH` means `module.LIMIT_DEPTH = 1` and `monkeypatch.setattr(module.urllib3, "PoolManager", ...)` still change what the request runs.
- **Actually:** assignment rebinds the name on `main` only. `evaluate()` reads `chessapp.engine.LIMIT_DEPTH`. `http_pool()` looks up `urllib3` and `_http` on `chessapp.search_client`. `BoundedTTLCache` reads `time` on `chessapp.cache`. Deleting only `sys.modules["main"]` leaves `chessapp` imported, so a later import does not re-read `AUTH_*`. A test file that imports `chessapp.search_client` at module level keeps the old module object after that unload.
- **Broke:** local pytest. Tree depth stayed at 8 (slow, and goldens would drift). The Java pool mock never ran. `test_import_fails_without_auth_env` would pass for the wrong reason, or fail, depending on order.
- **Do this:** patch the module that looks the name up. Set `chessapp.engine.LIMIT_DEPTH`. Unload `main` and every `chessapp*` entry before a re-import. Import `chessapp.*` inside the test, after the fixture has loaded the app.

### 2026-10-13 — Flask's test client runs the view on the test thread

- **Assumed:** holding `_engine_lock` in the test and then `client.post("/analyse")` exercises a second thread waiting on the lock.
- **Actually:** the test client calls the view on the same thread. `threading.Lock` is not reentrant. With `STOCKFISH_LOCK_TIMEOUT_SECONDS=0` the second acquire fails immediately and the handler returns 503. A timeout above 0 deadlocks that thread.
- **Broke:** a 503 test written with a blocking timeout would hang pytest.
- **Do this:** keep the test timeout at 0, or take the lock from another thread. Do not "fix" a hang by removing the lock.

### 2026-10-13 — caching the Java payload and then the plain-text body

- **Assumed:** `CACHE[fen] = res` (the hit list) and later `CACHE[fen] = plain_text_response` is a harmless two-step write.
- **Actually:** a concurrent request can observe the list and return it as the body. The response contract is `text/plain`.
- **Broke:** no CI failure. The text tests only see the final value on one thread.
- **Do this:** store only the finished plain-text body. Check the cache before taking the engine lock, and again after acquiring it.

## Agent and local tooling

### 2026-10-12 — `gh pr view` / `gh issue view` without `--json`

- **Assumed:** `gh pr view 24` and `gh issue view 6` print the body.
- **Actually:** the default GraphQL query still asks for classic Projects (`projectCards`). GitHub returns an error about Projects classic being deprecated, and the command exits non-zero even when the PR exists.
- **Broke:** the first status check of an open PR. The shell looked like a failed `gh` auth.
- **Do this:** `gh pr view N --json ...` and `gh issue view N --json ...`. `gh pr list` and `gh pr checks` are fine.

### 2026-10-13 — pytest segfaults inside the Cursor sandbox

- **Assumed:** `python-server/.venv/bin/pytest` from the workspace sandbox is the same as CI.
- **Actually:** numpy hits a segfault (`Fatal Python error` in `numpy/linalg/linalg.py`, exit 139) under the sandbox. The same command outside the sandbox passes.
- **Broke:** local verification of Python tests. CI on GitHub is unaffected.
- **Do this:** re-run pytest outside the sandbox before calling the suite red. Java `mvn test` does run in the sandbox.

### 2026-10-13 — `git push -u` can succeed and still fail to record tracking

- **Assumed:** a failed `git push -u` means the branch is not on the remote.
- **Actually:** in the sandbox, the objects can be pushed and then `git` fails with `could not write config file .git/config: Operation not permitted`. `gh` from the sandbox often returns `Post "https://api.github.com/graphql": Forbidden`.
- **Broke:** a follow-up `gh pr create` looked like the push had failed. The branch was already on origin.
- **Do this:** if the remote says `*[new branch]`, create the PR with `gh` outside the sandbox. Do not push a second time to "fix" the config write. Git LFS may also complain about the locking API; turning off `locksverify` for that remote is acceptable when the push itself works. Keep this repo as the workspace root so `.git` is inside the workspace.

### 2026-10-13 — `java-server/target/` is gitignored and still tracked

- **Assumed:** `.gitignore` entry `java-server/target/` means `mvn test` cannot dirty git status.
- **Actually:** class files that were committed before the ignore (`chessServer.class` and its inner classes) stay tracked. A test compile shows them as modifications.
- **Broke:** easy to stage binary class files into a source PR.
- **Do this:** do not `git add java-server/target`. Restore those paths before committing. New class files under `target/` stay ignored.

## Still true, easy to "fix" by accident

- Auth is `AUTH_USERNAME` / `AUTH_PASSWORD` from the environment. There is no hardcoded Basic auth to fall back to. Empty values must fail startup.
- Game metadata is Lucene stored fields. There is no Mongo round-trip to restore.
- Stockfish is not in git. The image and `scripts/download_stockfish.sh` download it.
- `games/games.json.zip` is Git LFS. `make index` needs `git lfs pull`.
- Product issues #17–#23 are the open track, one issue per PR. The owner started it after #35 merged.
