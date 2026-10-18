# Security notes

## Authentication

The public server checks HTTP Basic auth once. Credentials **must** come from the environment:

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

The public server is one JVM on port 8081. It needs a Lucene index on disk (`make index` / `LUCENE_INDEX_DIR`) and a Python interpreter that can run `python -m chessapp.cli` (`CHESSAPP_DIR`, `STOCKFISH_PATH`).

## Flask

`python-server/main.py` still starts with `debug=False`. Compose does not run it. The tests use the Flask client; production traffic goes to the JVM.

## Reporting

Open a private security advisory or contact the repository owner for vulnerability reports.
