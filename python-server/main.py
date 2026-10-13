"""Process entry for gunicorn (``main:app``).

Behaviour lives in ``chessapp``. Names below are re-exported so tests and
local scripts can keep importing them from ``main``.
"""

from chessapp.api import app
from chessapp.cache import CACHE, BoundedTTLCache
from chessapp.engine import (
    _engine_lock,
    analyse_position,
    resolve_stockfish_path,
    stockfish,
    stockfish_candidates,
)
from chessapp.features import (
    find_square,
    get_doubled_pawns,
    get_dynamic_features,
    get_isolated_pawns,
    get_king_activity,
    get_more_features,
    get_passed_pawns,
    get_pgn,
    get_rook_placement_score,
    get_static_features,
    remove_castling_rights,
)

__all__ = [
    "app",
    "CACHE",
    "BoundedTTLCache",
    "analyse_position",
    "stockfish",
]


if __name__ == '__main__':
    # Never enable the interactive debugger in a shared/networked process.
    app.run(host="0.0.0.0", port=8081, debug=False)
