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
- **Do this:** restart Java after `make index` / `make index-sample`. Leave `maybeRefresh()` for commits into the same directory.

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
- Product issues stay deferred until the owner says otherwise. Open tech work is finished or explicitly skipped before starting a product issue.
