.PHONY: test test-python test-java

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
