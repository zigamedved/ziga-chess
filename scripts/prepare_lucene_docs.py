#!/usr/bin/env python3
"""
Stream games/games.json.zip → NDJSON Lucene docs with feature fields.

Each output line is a JSON object:
  name, White, Black, Result, PGN, PV1, PV2, endgameFEN, static, other, dynamic

Feature extraction matches python-server/main.py (static / more→other / dynamic from PV1+PV2).
Stockfish is not required — PV moves come from the stored PV strings.

Examples:
  python scripts/prepare_lucene_docs.py --limit 100 -o /tmp/docs.ndjson
  python scripts/prepare_lucene_docs.py -o /tmp/docs.ndjson   # full corpus (~15–30 min)
"""

from __future__ import annotations

import argparse
import importlib
import io
import json
import os
import re
import sys
import types
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PYTHON_SERVER = ROOT / "python-server"
DEFAULT_ZIP = ROOT / "games" / "games.json.zip"


def _import_feature_module():
    """Import main.py without starting a real Stockfish binary."""
    os.environ.setdefault("AUTH_USERNAME", "index-builder")
    os.environ.setdefault("AUTH_PASSWORD", "index-builder")

    class DummyStockfish:
        def __init__(self, *args, **kwargs):
            pass

        def set_elo_rating(self, *args, **kwargs):
            pass

    stockfish_mod = types.ModuleType("stockfish")
    stockfish_mod.Stockfish = DummyStockfish
    sys.modules["stockfish"] = stockfish_mod

    sys.path.insert(0, str(PYTHON_SERVER))
    if "main" in sys.modules:
        del sys.modules["main"]
    return importlib.import_module("main")


def parse_pv_moves(pv: str):
    import chess.pgn

    if not pv or not str(pv).strip():
        return []
    fixed = re.sub(r"\]\[", "]\n[", str(pv))
    fixed = re.sub(r"\](\d)", r"]\n\1", fixed)
    game = chess.pgn.read_game(io.StringIO(fixed))
    if game is None:
        return []
    moves = []
    node = game
    while node.variations:
        node = node.variation(0)
        moves.append(node.move)
    return moves


def features_for_game(main, game: dict) -> tuple[str, str, str]:
    fen = game["endgameFEN"]
    static = " ".join(main.get_static_features(fen))
    other = " ".join(main.get_more_features(fen))
    dyn_tokens = []
    for key in ("PV1", "PV2"):
        moves = parse_pv_moves(game.get(key, ""))
        if moves:
            dyn_tokens.extend(main.get_dynamic_features(fen, moves))
    dynamic = " ".join(dyn_tokens)
    return static, other, dynamic


def iter_games(zip_path: Path):
    with zipfile.ZipFile(zip_path) as zf:
        with zf.open("games.json") as raw:
            for line in raw:
                if not line.strip():
                    continue
                yield json.loads(line)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--games-zip",
        type=Path,
        default=DEFAULT_ZIP,
        help="Path to games.json.zip (Git LFS)",
    )
    parser.add_argument(
        "-o",
        "--out",
        type=Path,
        required=True,
        help="Output NDJSON path",
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=0,
        help="If >0, only process this many games (smoke / fixture builds)",
    )
    args = parser.parse_args(argv)

    if not args.games_zip.is_file():
        print(f"Missing {args.games_zip} — run: git lfs pull", file=sys.stderr)
        return 1

    main_mod = _import_feature_module()
    args.out.parent.mkdir(parents=True, exist_ok=True)

    written = 0
    skipped = 0
    with args.out.open("w", encoding="utf-8") as out:
        for game in iter_games(args.games_zip):
            if args.limit and written >= args.limit:
                break
            fen = game.get("endgameFEN")
            name = game.get("name")
            if not fen or not name:
                skipped += 1
                continue
            try:
                static, other, dynamic = features_for_game(main_mod, game)
            except Exception as exc:  # noqa: BLE001 — skip corrupt rows, keep going
                skipped += 1
                print(f"skip {name}: {exc}", file=sys.stderr)
                continue

            doc = {
                "name": name,
                "White": game.get("White", ""),
                "Black": game.get("Black", ""),
                "Result": game.get("Result", ""),
                "PGN": game.get("PGN", ""),
                "PV1": game.get("PV1", ""),
                "PV2": game.get("PV2", ""),
                "endgameFEN": fen,
                "static": static,
                "other": other,
                "dynamic": dynamic,
            }
            out.write(json.dumps(doc, ensure_ascii=False))
            out.write("\n")
            written += 1
            if written % 5000 == 0:
                print(f"... {written} docs", flush=True)

    print(f"Wrote {written} docs to {args.out} (skipped {skipped})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
