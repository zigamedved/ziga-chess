"""The checked-in bishop NDJSON stays in step with the feature functions."""

import json
from pathlib import Path

import chess

from chessapp.features import endgame_family, get_more_features, get_static_features

DOCS = Path(__file__).resolve().parents[2] / "fixtures" / "bishop-docs.ndjson"


def test_bishop_docs_are_bishop_endgames_with_live_features():
    text = DOCS.read_text(encoding="utf-8")
    assert "4k3/8/8/8/2b5/8/3B4/4K3" not in text
    lines = [line for line in text.splitlines() if line.strip()]
    assert len(lines) >= 20
    names = set()
    for line in lines:
        doc = json.loads(line)
        fen = doc["endgameFEN"]
        assert doc["endgameFamily"] == "bishop"
        assert endgame_family(fen) == "bishop"
        assert doc["static"] == " ".join(get_static_features(fen))
        assert doc["other"] == " ".join(get_more_features(fen))
        assert doc["name"] not in names
        names.add(doc["name"])
        board = chess.Board(fen)
        pair = (
            len(board.pieces(chess.BISHOP, chess.WHITE)) >= 2
            or len(board.pieces(chess.BISHOP, chess.BLACK)) >= 2
        )
        assert board.pawns or pair
