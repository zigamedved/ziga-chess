"""HTTP integration tests for Flask routes (Stockfish + Java mocked)."""

import importlib
import json
import sys
import types

import pytest

from conftest import (
    README_FEN,
    TEST_AUTH_PASSWORD,
    TEST_AUTH_USERNAME,
    basic_auth_header,
)


def test_ping_no_auth(client):
    res = client.get("/ping")
    assert res.status_code == 200
    assert res.data.decode("utf-8") == "pong"


def test_hello_requires_auth(client):
    res = client.get("/hello")
    assert res.status_code == 401

    res = client.get("/hello", headers={"Authorization": basic_auth_header()})
    assert res.status_code == 200
    assert res.json == {"message": "Hello, this is the response from the server!"}


def test_hello_rejects_wrong_password(client):
    res = client.get(
        "/hello",
        headers={"Authorization": basic_auth_header(TEST_AUTH_USERNAME, "wrong")},
    )
    assert res.status_code == 401


def test_analyse_requires_auth(client):
    res = client.post("/analyse", json={"FEN": README_FEN})
    assert res.status_code == 401


def test_analyse_missing_fen_returns_400(client):
    res = client.post(
        "/analyse",
        json={},
        headers={"Authorization": basic_auth_header()},
    )
    assert res.status_code == 400
    assert res.json["code"] == "bad_request"


def test_analyse_invalid_fen(client, app_module, monkeypatch):
    monkeypatch.setattr(app_module.stockfish, "is_fen_valid", lambda fen: False)
    res = client.post(
        "/analyse",
        json={"FEN": "not-a-fen"},
        headers={"Authorization": basic_auth_header()},
    )
    assert res.status_code == 400
    assert res.json["code"] == "invalid_fen"
    assert "Invalid FEN" in res.json["error"]


def test_analyse_returns_plain_text_and_sorted_games(client):
    res = client.post(
        "/analyse",
        json={"FEN": README_FEN},
        headers={"Authorization": basic_auth_header()},
    )
    assert res.status_code == 200
    assert res.content_type.startswith("text/plain")
    text = res.data.decode("utf-8")
    assert "Analysed lines of input game:" in text
    assert "PV1:" in text
    assert "PV2:" in text
    assert "Similar games:" in text
    # Higher score (99.0) must appear before lower (12.5) after sort.
    assert text.index("Game #0") < text.index("Game #1")
    assert "score: 99.0" in text
    assert "score: 12.5" in text


def test_analyse_caches_by_fen(client, app_module, monkeypatch):
    calls = {"n": 0}

    class CountingPool:
        def request(self, method, url, body, headers):
            calls["n"] += 1
            payload = [
                json.dumps(
                    {
                        "name": "Game#cache.txt",
                        "endgameFEN": README_FEN,
                        "White": "W",
                        "Black": "B",
                        "Result": "*",
                        "PGN": "1. e4 *",
                        "PV1": "pv1",
                        "PV2": "pv2",
                        "score": 1.0,
                    }
                )
            ]

            class Resp:
                status = 200
                data = json.dumps(payload).encode("utf-8")

            return Resp()

    monkeypatch.setattr(app_module.urllib3, "PoolManager", lambda: CountingPool())
    app_module.CACHE.clear()

    headers = {"Authorization": basic_auth_header()}
    body = {"FEN": README_FEN}
    first = client.post("/analyse", json=body, headers=headers)
    second = client.post("/analyse", json=body, headers=headers)
    assert first.status_code == 200
    assert second.status_code == 200
    assert first.data == second.data
    assert calls["n"] == 1


def test_analyse_java_failure_bubbles_status(client, app_module, monkeypatch):
    class FailingPool:
        def request(self, method, url, body, headers):
            class Resp:
                status = 503
                data = b"down"

            return Resp()

    monkeypatch.setattr(app_module.urllib3, "PoolManager", lambda: FailingPool())
    app_module.CACHE.clear()

    res = client.post(
        "/analyse",
        json={"FEN": README_FEN},
        headers={"Authorization": basic_auth_header()},
    )
    assert res.status_code == 200
    assert res.json == "Request failed with status code: 503"


OTHER_FEN = "4k3/8/8/8/4P3/8/8/4K3 w - - 0 1"


def test_analyse_returns_503_when_engine_busy(client, app_module):
    calls = {"n": 0}

    class CountingPool:
        def request(self, method, url, body, headers):
            calls["n"] += 1

            class Resp:
                status = 200
                data = b"[]"

            return Resp()

    app_module._http = CountingPool()
    assert app_module._engine_lock.acquire(timeout=0)
    try:
        res = client.post(
            "/analyse",
            json={"FEN": README_FEN},
            headers={"Authorization": basic_auth_header()},
        )
    finally:
        app_module._engine_lock.release()

    assert res.status_code == 503
    assert res.json["code"] == "engine_busy"
    assert calls["n"] == 0
    assert app_module.CACHE.get(README_FEN) is None


def test_analyse_cache_hit_skips_busy_engine(client, app_module):
    headers = {"Authorization": basic_auth_header()}
    body = {"FEN": README_FEN}
    primed = client.post("/analyse", json=body, headers=headers)
    assert primed.status_code == 200

    assert app_module._engine_lock.acquire(timeout=0)
    try:
        cached = client.post("/analyse", json=body, headers=headers)
    finally:
        app_module._engine_lock.release()

    assert cached.status_code == 200
    assert cached.data == primed.data


def test_http_pool_is_reused(client, app_module, monkeypatch):
    created = {"n": 0}

    class CountingPool:
        def __init__(self):
            created["n"] += 1

        def request(self, method, url, body, headers):
            payload = [
                json.dumps(
                    {
                        "name": "Game#pool.txt",
                        "endgameFEN": README_FEN,
                        "White": "W",
                        "Black": "B",
                        "Result": "*",
                        "PGN": "1. e4 *",
                        "PV1": "pv1",
                        "PV2": "pv2",
                        "score": 1.0,
                    }
                )
            ]

            class Resp:
                status = 200
                data = json.dumps(payload).encode("utf-8")

            return Resp()

    monkeypatch.setattr(app_module.urllib3, "PoolManager", CountingPool)
    app_module.CACHE.clear()
    headers = {"Authorization": basic_auth_header()}
    first = client.post("/analyse", json={"FEN": README_FEN}, headers=headers)
    second = client.post("/analyse", json={"FEN": OTHER_FEN}, headers=headers)
    assert first.status_code == 200
    assert second.status_code == 200
    assert created["n"] == 1


def test_stockfish_candidates_prefer_platform(app_module):
    apple = app_module.stockfish_candidates("Darwin", "arm64")
    assert apple[0] == "engine/Stockfish_ARM"
    assert "engine/stockfish" in apple

    linux = app_module.stockfish_candidates("Linux", "x86_64")
    assert linux[0] == "engine/stockfish-ubuntu-x86-64"
    assert linux[1] == "engine/stockfish"


def test_resolve_stockfish_path_uses_env_verbatim(app_module, monkeypatch):
    monkeypatch.setenv("STOCKFISH_PATH", "/opt/engines/stockfish")
    assert app_module.resolve_stockfish_path() == "/opt/engines/stockfish"


def test_cache_lru_and_ttl(app_module, monkeypatch):
    now = {"t": 1_000.0}
    monkeypatch.setattr(app_module.time, "monotonic", lambda: now["t"])
    cache = app_module.BoundedTTLCache(max_entries=2, ttl_seconds=10)
    cache["a"] = "1"
    cache["b"] = "2"
    assert cache.get("a") == "1"
    cache["c"] = "3"
    assert cache.get("b") is None
    assert cache.get("a") == "1"
    assert cache.get("c") == "3"

    now["t"] = 1_011.0
    assert cache.get("a") is None
    assert cache.get("c") is None
    assert len(cache) == 0


def test_import_fails_without_auth_env(monkeypatch):
    monkeypatch.delenv("AUTH_USERNAME", raising=False)
    monkeypatch.delenv("AUTH_PASSWORD", raising=False)

    stockfish_mod = types.ModuleType("stockfish")

    class DummyStockfish:
        def __init__(self, *args, **kwargs):
            pass

        def set_elo_rating(self, *args, **kwargs):
            pass

    stockfish_mod.Stockfish = DummyStockfish
    monkeypatch.setitem(sys.modules, "stockfish", stockfish_mod)
    if "main" in sys.modules:
        del sys.modules["main"]

    with pytest.raises(RuntimeError, match="AUTH_USERNAME"):
        importlib.import_module("main")
