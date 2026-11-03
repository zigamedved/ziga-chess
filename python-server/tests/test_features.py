"""Unit tests pinning pure chess-feature helpers (no Stockfish / Java)."""

import chess

from conftest import (
    CONNECTED_ROOKS_FEN,
    KING_CENTER_FEN,
    PASSED_PAWN_FEN,
    README_FEN,
)


def test_remove_castling_rights(app_module):
    fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq e3 0 1"
    assert app_module.remove_castling_rights(fen) == (
        "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w - - 0 1"
    )


def test_find_square(app_module):
    assert app_module.find_square("K", [chess.E1, chess.E8]) == ["Ke1", "Ke8"]


def test_get_static_features_readme_fen_piece_locations(app_module):
    features = app_module.get_static_features(README_FEN)
    # Piece-on-square tokens for the sample endgame.
    for token in ["Pa5", "Re7", "ra8", "rf8", "Kg1", "kg8", "pc5", "pc6"]:
        assert token in features
    # Attack / defense relation tokens present in this position.
    assert "R>pf7" in features
    assert "R<Kg1" in features


def test_get_passed_pawns(app_module):
    board = chess.Board(PASSED_PAWN_FEN)
    assert list(app_module.get_passed_pawns(board, chess.WHITE, "P")) == ["PPa"]
    assert list(app_module.get_passed_pawns(board, chess.BLACK, "p")) == []


def test_get_doubled_pawns(app_module):
    # Two black pawns on the a-file.
    board = chess.Board("4k3/8/p7/p7/8/8/8/4K3 w - - 0 1")
    assert list(app_module.get_doubled_pawns(board)) == ["Dpa"]


def test_get_isolated_pawns(app_module):
    board = chess.Board(PASSED_PAWN_FEN)
    assert "IPa" in list(app_module.get_isolated_pawns(board))


def test_get_king_activity_center(app_module):
    board = chess.Board(KING_CENTER_FEN)
    assert list(app_module.get_king_activity(board)) == ["Kcc"]


def test_get_rook_placement_open_and_connected(app_module):
    board = chess.Board(CONNECTED_ROOKS_FEN)
    score = list(app_module.get_rook_placement_score(board))
    assert "ORa" in score
    assert "ORh" in score
    assert "R-R-1" in score


def test_get_more_features_combines_motifs(app_module):
    more = app_module.get_more_features(README_FEN)
    # Doubled black c-pawns, open e-file white rook, connected black rooks on rank 8.
    assert "Dpc" in more
    assert "ORe" in more
    assert "r-r-8" in more


def test_get_dynamic_features_quiet_move(app_module):
    move = chess.Move.from_uci("g8h8")
    dyn = app_module.get_dynamic_features(README_FEN, [move])
    assert dyn == ["!kh8"]


def test_endgame_family_bishop_and_other(app_module):
    assert app_module.endgame_family(
        "4k3/8/8/8/2b5/8/3B4/4K3 w - - 0 1") == "bishop"
    assert "oppB" in app_module.get_more_features(
        "4k3/8/8/8/2b5/8/3B4/4K3 w - - 0 1")
    assert app_module.endgame_family(README_FEN) == "rook"
    assert app_module.endgame_family("6k1/8/8/8/8/8/8/4QK2 w - - 0 1") == "other"
    assert app_module.endgame_family("4k3/8/8/8/8/8/4N3/4K3 w - - 0 1") == "other"
    assert app_module.endgame_family("4k3/8/8/8/3b4/8/3R4/4K3 w - - 0 1") == "other"


def test_get_dynamic_features_bishop_attacks_bishop(app_module):
    fen = "4k3/8/4p3/4b3/8/8/3B4/4K3 w - - 0 1"
    dyn = app_module.get_dynamic_features(fen, [chess.Move.from_uci("d2c3")])
    assert "!Bc3" in dyn
    assert "!B>b" in dyn


def test_get_dynamic_features_rook_lift(app_module):
    move = chess.Move.from_uci("f8e8")
    dyn = app_module.get_dynamic_features(README_FEN, [move])
    assert "!re8" in dyn
    assert "!r>R" in dyn


def test_get_pgn_wraps_moves(app_module):
    moves = [chess.Move.from_uci("g8h8")]
    game = app_module.get_pgn(README_FEN, moves)
    text = str(game)
    assert README_FEN in text
    assert "Kh8" in text
