"""Feature CLI: same analyse_position output the HTTP route already uses."""

import json

from conftest import README_FEN, basic_auth_header
from test_golden_features import GOLDEN_ANALYSE_FEATURE_STRING


def test_cli_prints_features_and_pvs(app_module, capsys):
    from chessapp.cli import main

    assert main([README_FEN]) == 0
    payload = json.loads(capsys.readouterr().out)
    assert payload["fen"] == README_FEN
    assert payload["features"] == GOLDEN_ANALYSE_FEATURE_STRING
    assert "Kh8" in payload["pv1"]
    assert "Rfe8" in payload["pv2"]


def test_cli_reads_fen_from_stdin(app_module, capsys, monkeypatch):
    from chessapp.cli import main

    monkeypatch.setattr("sys.stdin", __import__("io").StringIO(README_FEN + "\n"))
    assert main([]) == 0
    payload = json.loads(capsys.readouterr().out)
    assert payload["features"] == GOLDEN_ANALYSE_FEATURE_STRING


def test_cli_joins_fen_arguments(app_module, capsys):
    from chessapp.cli import main

    parts = README_FEN.split(" ")
    assert main(parts) == 0
    payload = json.loads(capsys.readouterr().out)
    assert payload["fen"] == README_FEN


def test_cli_rejects_missing_and_invalid_fen(app_module, capsys, monkeypatch):
    from chessapp.cli import main

    monkeypatch.setattr("sys.stdin", __import__("io").StringIO(""))
    assert main([]) == 2
    err = capsys.readouterr()
    assert err.out == ""
    assert "required" in err.err

    assert main(["not-a-fen"]) == 2
    err = capsys.readouterr()
    assert err.out == ""
    assert "Invalid FEN" in err.err


def test_cli_pvs_match_analyse_response(client, app_module, capsys):
    from chessapp.cli import main

    assert main([README_FEN]) == 0
    payload = json.loads(capsys.readouterr().out)
    res = client.post(
        "/analyse",
        json={"FEN": README_FEN},
        headers={"Authorization": basic_auth_header()},
    )
    assert res.status_code == 200
    text = res.data.decode("utf-8")
    assert payload["pv1"] in text
    assert payload["pv2"] in text
    assert "PV1:" in text
