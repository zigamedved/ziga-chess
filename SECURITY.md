# Security notes

## Authentication

Both services use HTTP Basic auth. Credentials **must** come from the environment:

- `AUTH_USERNAME`
- `AUTH_PASSWORD`

See `.env.example`. The historical hardcoded password that appeared in source is **compromised** — do not reuse it anywhere; rotate any system that ever shared it.

## Local / Docker

```bash
cp .env.example .env
# edit AUTH_*
export $(grep -v '^#' .env | xargs)   # or use your compose env_file
make index-sample   # or make index
```

Java needs a Lucene index on disk (`make index` / `LUCENE_INDEX_DIR`). Python talks to Java via `JAVA_SERVICE_URL` (default `http://127.0.0.1:8080/position`).

## Flask

The Python process starts with `debug=False`. Prefer gunicorn in production (already listed in `requirements.txt`).

## Reporting

Open a private security advisory or contact the repository owner for vulnerability reports.
