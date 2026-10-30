import io

import chess.pgn

from chessapp.endgame import InvalidPgn, NoEndgame, first_rook_endgame, is_rook_endgame, main

ROOK_FEN = "4k3/8/8/8/8/8/8/R3K3 w - - 0 1"
QUEEN_FEN = "4k3/8/8/8/8/8/q7/R3K3 w - - 0 1"

TRADE = f'[SetUp "1"]\n[FEN "{QUEEN_FEN}"]\n\n1. Rxa2\n'


def test_trade_is_the_first_rook_endgame():
    fen, ply = first_rook_endgame(TRADE)
    assert ply == 1
    board = chess.pgn.read_game(io.StringIO(TRADE)).board()
    board.push_san("Rxa2")
    assert fen == board.fen()
    assert is_rook_endgame(board)


def test_start_position_that_is_already_a_rook_endgame_is_ply_zero():
    pgn = f'[SetUp "1"]\n[FEN "{ROOK_FEN}"]\n\n'
    fen, ply = first_rook_endgame(pgn)
    assert ply == 0
    assert fen == ROOK_FEN


def test_opening_moves_have_no_rook_endgame():
    try:
        first_rook_endgame("1. e4 e5 2. Nf3 Nc6 *")
    except NoEndgame:
        return
    raise AssertionError("expected NoEndgame")


def test_garbage_and_an_illegal_move_are_invalid():
    for text in ("not a game", "1. Ke5"):
        try:
            first_rook_endgame(text)
        except InvalidPgn:
            continue
        raise AssertionError(text)


def test_cli_prints_fen_and_ply(monkeypatch, capsys):
    monkeypatch.setattr("sys.stdin", io.StringIO(TRADE))
    assert main() == 0
    out = capsys.readouterr().out
    assert '"ply": 1' in out
    assert "fen" in out
