"""The checked-in bishop NDJSON stays in step with the feature functions."""

import json
from pathlib import Path

from chessapp.features import endgame_family, get_more_features, get_static_features

DOCS = Path(__file__).resolve().parents[2] / "fixtures" / "bishop-docs.ndjson"


def test_bishop_docs_are_bishop_endgames_with_live_features():
    lines = [line for line in DOCS.read_text(encoding="utf-8").splitlines() if line.strip()]
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
