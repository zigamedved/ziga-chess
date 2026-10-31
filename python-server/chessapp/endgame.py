"""First rook endgame on a PGN main line.

A position is a rook endgame when it is legal, every piece is a king, rook,
or pawn, and at least one rook is on the board. The chosen position is the
first one on the main line, including the start position. Sidelines are ignored.

Stdout is one JSON object, ``fen`` and ``ply``. Exit 2 means the text is not
a PGN. Exit 3 means the main line never reaches a rook endgame.
"""

from __future__ import annotations

import io
import json
import re
import sys

import chess
import chess.pgn


class InvalidPgn(Exception):
    pass


class NoEndgame(Exception):
    pass


def is_rook_endgame(board: chess.Board) -> bool:
    if not board.is_valid():
        return False
    has_rook = False
    for piece in board.piece_map().values():
        if piece.piece_type not in (chess.KING, chess.ROOK, chess.PAWN):
            return False
        if piece.piece_type == chess.ROOK:
            has_rook = True
    return has_rook


def first_rook_endgame(pgn_text: str) -> tuple[str, int]:
    text = (pgn_text or "").strip()
    if not text:
        raise InvalidPgn("PGN is required")
    try:
        game = chess.pgn.read_game(io.StringIO(text))
    except ValueError as exc:
        raise InvalidPgn("Invalid PGN") from exc
    if game is None or game.errors:
        raise InvalidPgn("Invalid PGN")
    if (
        not any(True for _ in game.mainline_moves())
        and "FEN" not in game.headers
        and re.search(r"\d+\.", text) is None
    ):
        raise InvalidPgn("Invalid PGN")
    board = game.board()
    if is_rook_endgame(board):
        return board.fen(), 0
    ply = 0
    try:
        for move in game.mainline_moves():
            ply += 1
            board.push(move)
            if is_rook_endgame(board):
                return board.fen(), ply
    except ValueError as exc:
        raise InvalidPgn("Invalid PGN") from exc
    raise NoEndgame("No rook endgame in this game")


def main(argv=None) -> int:
    del argv
    text = sys.stdin.read()
    try:
        fen, ply = first_rook_endgame(text)
    except InvalidPgn as exc:
        print(str(exc), file=sys.stderr)
        return 2
    except NoEndgame as exc:
        print(str(exc), file=sys.stderr)
        return 3
    json.dump({"fen": fen, "ply": ply}, sys.stdout)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
