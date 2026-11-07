#!/usr/bin/env python3
"""Write Lucene NDJSON for the checked-in bishop endgames.

The rook corpus in games/games.json.zip has no bishop endgame. This file is the
set `make index-bishops` loads with IndexBuilder --update, so the rook index
stays in place.

Source positions live in fixtures/bishop-sources.json. Each output line matches
scripts/prepare_lucene_docs.py: features, endgameFamily, and the stored names.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PYTHON_SERVER = ROOT / "python-server"
DEFAULT_SOURCES = ROOT / "fixtures" / "bishop-sources.json"
DEFAULT_OUT = ROOT / "fixtures" / "bishop-docs.ndjson"


def load_features():
    sys.path.insert(0, str(PYTHON_SERVER))
    from chessapp.features import endgame_family, get_more_features, get_static_features

    return endgame_family, get_static_features, get_more_features


def documents(sources: Path):
    endgame_family, get_static_features, get_more_features = load_features()
    rows = json.loads(sources.read_text(encoding="utf-8"))
    docs = []
    for row in rows:
        fen = row["fen"]
        family = endgame_family(fen)
        if family != "bishop":
            raise SystemExit(f"{row['name']} is {family}, not a bishop endgame: {fen}")
        docs.append(
            {
                "name": row["name"],
                "White": row.get("white", ""),
                "Black": row.get("black", ""),
                "Result": row.get("result", "*"),
                "WhiteElo": "",
                "BlackElo": "",
                "PGN": row.get("pgn", ""),
                "PV1": "",
                "PV2": "",
                "endgameFEN": fen,
                "endgameFamily": family,
                "static": " ".join(get_static_features(fen)),
                "other": " ".join(get_more_features(fen)),
                "dynamic": "",
            }
        )
    return docs


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sources", type=Path, default=DEFAULT_SOURCES)
    parser.add_argument("-o", "--out", type=Path, default=DEFAULT_OUT)
    args = parser.parse_args(argv)
    docs = documents(args.sources)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    with args.out.open("w", encoding="utf-8") as out:
        for doc in docs:
            out.write(json.dumps(doc, ensure_ascii=False))
            out.write("\n")
    print(f"Wrote {len(docs)} bishop docs to {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
