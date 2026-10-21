"""Feature CLI for the single-service migration (ADR 0001, slice 1).

Run from ``python-server``::

    python -m chessapp.cli 'FEN'
    echo 'FEN' | python -m chessapp.cli

Stdout is one JSON object: ``fen``, ``features``, ``pv1``, ``pv2``,
``engineDepth``, and ``limitDepth``.

This module must not import ``chessapp.api``. The API process requires
``AUTH_*``; the CLI is a subprocess and does not.
"""

import json
import sys

from chessapp import engine
from chessapp.response import format_pv


def main(argv=None) -> int:
    args = list(sys.argv[1:] if argv is None else argv)
    if args in (["-h"], ["--help"]):
        print(
            "usage: python -m chessapp.cli [FEN]\n"
            "One FEN from the remaining arguments, or one stdin line.",
            file=sys.stderr,
        )
        return 0

    if args:
        fen = " ".join(args).strip()
    else:
        fen = sys.stdin.readline().strip()
    if not fen:
        print("FEN is required", file=sys.stderr)
        return 2
    if not engine.stockfish.is_fen_valid(fen):
        print("Invalid FEN", file=sys.stderr)
        return 2

    features, (pgn1, pgn2) = engine.analyse_position(fen)
    json.dump(
        {
            "fen": fen,
            "features": features,
            "pv1": format_pv(pgn1),
            "pv2": format_pv(pgn2),
            "engineDepth": engine.ENGINE_DEPTH,
            "limitDepth": engine.LIMIT_DEPTH,
        },
        sys.stdout,
    )
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
