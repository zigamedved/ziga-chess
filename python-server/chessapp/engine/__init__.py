"""One Stockfish process and the PV tree used to build dynamic features."""

import os
import platform
import threading

import chess
import numpy as np
from stockfish import Stockfish

from chessapp.cache import _env_float
from chessapp.features import (
    get_dynamic_features,
    get_more_features,
    get_pgn,
    get_static_features,
)


def stockfish_candidates(system: str, machine: str):
    """Binary names under engine/, most specific platform match first."""
    system = (system or "").lower()
    machine = (machine or "").lower()
    preferred = []
    if system == "darwin" and machine in ("arm64", "aarch64"):
        preferred.append("engine/Stockfish_ARM")
    elif system == "linux" and machine in ("x86_64", "amd64"):
        preferred.append("engine/stockfish-ubuntu-x86-64")
    preferred.append("engine/stockfish")
    for legacy in ("engine/stockfish-ubuntu-x86-64", "engine/Stockfish_ARM"):
        if legacy not in preferred:
            preferred.append(legacy)
    return preferred


def resolve_stockfish_path() -> str:
    """STOCKFISH_PATH wins. Otherwise the first executable platform candidate."""
    explicit = os.environ.get("STOCKFISH_PATH", "").strip()
    if explicit:
        return explicit
    for path in stockfish_candidates(platform.system(), platform.machine()):
        if os.path.isfile(path) and os.access(path, os.X_OK):
            return path
    return "engine/stockfish"


ENGINE_DEPTH = 12
LIMIT_DEPTH = 8

stockfish = Stockfish(
    path=resolve_stockfish_path(),
    depth=ENGINE_DEPTH,
    parameters={"Threads": 1, "Hash": 64},
)
stockfish.set_elo_rating(2500)

# One engine process. Overlapping /analyse calls that need it get 503
# instead of interleaving UCI commands. Cached FENs do not take the lock.
# gunicorn stays at -w 1; this lock covers a threaded worker or Flask's dev server.
_engine_lock = threading.Lock()

# 0 = do not wait; a busy engine returns 503 immediately.
ENGINE_LOCK_TIMEOUT_SECONDS = max(
    0.0, _env_float("STOCKFISH_LOCK_TIMEOUT_SECONDS", 0.0)
)


def evaluate(fen, depth, branch_factor, moves, root):
    board = chess.Board(fen)
    if depth == LIMIT_DEPTH or board.is_stalemate() or board.is_checkmate():
        return [moves]

    stockfish.set_fen_position(fen)
    best_moves = stockfish.get_top_moves(branch_factor)

    if branch_factor == 2 and len(best_moves) == 2:  # first iteration, call
        next_move1 = np.array([best_moves[0]['Move']])
        next_position1 = chess.Board(fen)
        next_position1.push(chess.Move.from_uci(best_moves[0]['Move']))
        new_fen1 = next_position1.fen()

        next_move2 = np.array([best_moves[1]['Move']])
        next_position2 = chess.Board(fen)
        next_position2.push(chess.Move.from_uci(best_moves[1]['Move']))
        new_fen2 = next_position2.fen()

        return evaluate(new_fen1, depth + 1, 1, next_move1, root + 1) + evaluate(new_fen2, depth + 1, 1, next_move2,
                                                                                 root + 1)
    else:
        added_move = np.append(moves, best_moves[0]['Move'])
        next_position = chess.Board(fen)
        next_position.push(chess.Move.from_uci(best_moves[0]['Move']))
        new_fen = next_position.fen()

        return evaluate(new_fen, depth + 1, 1, added_move, root + 1)


def analyse_position(fen):
    stockfish.set_fen_position(fen)

    static_features = get_static_features(fen)
    more_features = get_more_features(fen)

    result = ' '.join(static_features + more_features)

    best_variants = evaluate(fen, 0, 2, [], 0)
    variants = [[], []]
    index = 0
    if len(best_variants) > 0:
        for variant in best_variants:
            variant = list(map(lambda x: chess.Move.from_uci(x), variant))
            pgn = get_pgn(fen, variant)
            variants[index] = pgn
            index += 1
            dynamic_features = get_dynamic_features(fen, variant)
            result = result + ' '.join(dynamic_features)
    return result, variants
