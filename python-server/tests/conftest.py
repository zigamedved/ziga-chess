"""Shared fixtures: import main.py without real Stockfish or Java service."""

from __future__ import annotations

import base64
import importlib
import json
import sys
import types

import chess
import pytest

# Canonical sample from README — used across golden + integration tests.
README_FEN = "r4rk1/4Rppp/p1p5/P1p5/8/3P2P1/1PP2P1P/R5K1 b - - 0 25"

# Simple rook endgame with doubled / isolated / passed pawn motifs for unit tests.
DOUBLED_ISOLATED_FEN = "4k3/p1p5/1p6/8/8/8/PP1P2P1/4K3 w - - 0 1"
PASSED_PAWN_FEN = "4k3/8/8/8/8/8/P7/4K3 w - - 0 1"
CONNECTED_ROOKS_FEN = "4k3/8/8/8/8/8/8/R3K2R w KQ - 0 1"
KING_CENTER_FEN = "4k3/8/8/3K4/8/8/8/8 w - - 0 1"

# Test-only credentials (not production secrets).
TEST_AUTH_USERNAME = "test-user"
TEST_AUTH_PASSWORD = "test-password-not-for-prod"


def basic_auth_header(
    username: str = TEST_AUTH_USERNAME,
    password: str = TEST_AUTH_PASSWORD,
) -> str:
    token = base64.b64encode(f"{username}:{password}".encode("utf-8")).decode("ascii")
    return f"Basic {token}"


class DummyStockfish:
    """Deterministic Stockfish stand-in that only returns legal UCI moves."""

    def __init__(self, *args, **kwargs):
        self._fen = chess.STARTING_FEN

    def set_elo_rating(self, *args, **kwargs):
        return None

    def set_fen_position(self, fen):
        self._fen = fen

    def is_fen_valid(self, fen):
        try:
            chess.Board(fen)
            return True
        except ValueError:
            return False

    def get_top_moves(self, n):
        board = chess.Board(self._fen)
        moves = list(board.legal_moves)
        if not moves:
            return []
        # Prefer checks/captures for more interesting dynamic features, else first N.
        ranked = sorted(
            moves,
            key=lambda m: (board.is_capture(m), board.gives_check(m)),
            reverse=True,
        )
        return [{"Move": m.uci()} for m in ranked[:n]]


class DummyHTTPResponse:
    def __init__(self, status=200, data=b"[]"):
        self.status = status
        self.data = data


def _default_java_payload():
    return [
        json.dumps(
            {
                "name": "Game#0.txt",
                "endgameFEN": README_FEN,
                "White": "W",
                "Black": "B",
                "Result": "1-0",
                "PGN": "1. e4 e5 1-0",
                "PV1": "pv1",
                "PV2": "pv2",
                "score": 12.5,
            }
        ),
        json.dumps(
            {
                "name": "Game#1.txt",
                "endgameFEN": README_FEN,
                "White": "A",
                "Black": "C",
                "Result": "1/2-1/2",
                "PGN": "1. d4 d5 1/2-1/2",
                "PV1": "pv1b",
                "PV2": "pv2b",
                "score": 99.0,
            }
        ),
    ]


@pytest.fixture
def app_module(monkeypatch):
    """Import (or re-import) main with Stockfish + Java HTTP mocked."""
    monkeypatch.setenv("AUTH_USERNAME", TEST_AUTH_USERNAME)
    monkeypatch.setenv("AUTH_PASSWORD", TEST_AUTH_PASSWORD)
    monkeypatch.setenv("JAVA_SERVICE_URL", "http://127.0.0.1:8080/position")

    stockfish_mod = types.ModuleType("stockfish")
    stockfish_mod.Stockfish = DummyStockfish
    monkeypatch.setitem(sys.modules, "stockfish", stockfish_mod)

    if "main" in sys.modules:
        del sys.modules["main"]

    module = importlib.import_module("main")
    # Keep analyse tree shallow so tests stay fast.
    module.LIMIT_DEPTH = 1
    module.CACHE.clear()

    class DummyPoolManager:
        def request(self, method, url, body, headers):
            assert method == "POST"
            assert url.endswith("/position")
            assert "Authorization" in headers
            return DummyHTTPResponse(
                status=200,
                data=json.dumps(_default_java_payload()).encode("utf-8"),
            )

    monkeypatch.setattr(module.urllib3, "PoolManager", lambda: DummyPoolManager())
    return module


@pytest.fixture
def client(app_module):
    return app_module.app.test_client()
