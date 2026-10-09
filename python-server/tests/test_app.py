"""HTTP integration tests for Flask routes (Stockfish + Java mocked)."""

import json

from conftest import README_FEN, basic_auth_header


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


def test_analyse_requires_auth(client):
    res = client.post("/analyse", json={"FEN": README_FEN})
    assert res.status_code == 401


def test_analyse_invalid_fen(client, app_module, monkeypatch):
    monkeypatch.setattr(app_module.stockfish, "is_fen_valid", lambda fen: False)
    res = client.post(
        "/analyse",
        json={"FEN": "not-a-fen"},
        headers={"Authorization": basic_auth_header()},
    )
    assert res.status_code == 200
    assert res.data.decode("utf-8") == "Invalid FEN!"


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
