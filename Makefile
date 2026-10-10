.PHONY: test test-python test-java index index-sample package-java

DOCS_NDJSON ?= /tmp/ziga-chess-lucene-docs.ndjson
INDEX_DIR ?= java-server/indexedFiles
INDEX_LIMIT ?= 0

test: test-python test-java

test-python:
	cd python-server && \
		(test -d .venv || python3 -m venv .venv) && \
		.venv/bin/pip install -q -U pip setuptools wheel && \
		.venv/bin/pip install -q "numpy>=1.26,<2" "chess==1.9.4" "Flask==2.3.2" \
			"Werkzeug==2.3.6" "flask_httpauth==4.8.0" "stockfish==3.28.0" \
			"urllib3~=2.0.4" -r requirements-dev.txt && \
		.venv/bin/pytest -q

test-java:
	cd java-server && mvn -q test

package-java:
	cd java-server && mvn -q -DskipTests package

# Smoke index (100 games) — enough to run the Java server locally.
index-sample:
	$(MAKE) index INDEX_LIMIT=100

# Full rebuild from games/games.json.zip (Git LFS). INDEX_LIMIT=0 means all games.
# Approx: feature prep ~15–30 min for ~900k games, then Lucene write.
index: package-java
	@test -f games/games.json.zip || (echo "Missing games/games.json.zip — run: git lfs pull" && exit 1)
	cd python-server && \
		(test -d .venv || python3 -m venv .venv) && \
		.venv/bin/pip install -q -U pip setuptools wheel && \
		.venv/bin/pip install -q "numpy>=1.26,<2" "chess==1.9.4" "Flask==2.3.2" \
			"Werkzeug==2.3.6" "flask_httpauth==4.8.0" "stockfish==3.28.0" \
			"urllib3~=2.0.4"
	@if [ "$(INDEX_LIMIT)" -gt 0 ] 2>/dev/null; then \
		python-server/.venv/bin/python scripts/prepare_lucene_docs.py \
			--limit $(INDEX_LIMIT) -o $(DOCS_NDJSON); \
	else \
		python-server/.venv/bin/python scripts/prepare_lucene_docs.py \
			-o $(DOCS_NDJSON); \
	fi
	java -cp java-server/target/my-maven-docker-project.jar IndexBuilder \
		--docs $(DOCS_NDJSON) --out $(INDEX_DIR)
	@echo "Index ready at $(INDEX_DIR)"
