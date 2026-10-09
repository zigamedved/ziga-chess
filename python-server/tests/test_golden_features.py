"""
Golden pins for the README sample FEN.

These freeze the exact feature-token sequences produced by the current
implementation so refactors cannot silently change retrieval inputs.

Note: `analyse_position` PV / dynamic features depend on DummyStockfish's
legal-move ranking (captures/checks first). Keep that ranking in conftest
stable, or update these goldens intentionally.
"""

from conftest import README_FEN

# Pinned outputs captured from main.py on 2026-10-09 (LIMIT_DEPTH=1 + DummyStockfish).
GOLDEN_STATIC = [
    "Pb2",
    "Pc2",
    "Pf2",
    "Ph2",
    "Pd3",
    "Pg3",
    "Pa5",
    "pc5",
    "pa6",
    "pc6",
    "pf7",
    "pg7",
    "ph7",
    "Ra1",
    "Re7",
    "ra8",
    "rf8",
    "Kg1",
    "kg8",
    "R>pf7",
    "K<Pf2",
    "K<Ph2",
    "P<Pd3",
    "P<Pg3",
    "P<Pg3",
    "R<Pa5",
    "r<pa6",
    "r<pf7",
    "k<pf7",
    "k<pg7",
    "k<ph7",
    "r<ra8",
    "r<rf8",
    "k<rf8",
    "R<Kg1",
    "r<kg8",
]

GOLDEN_MORE = [
    "Dpc",
    "whe5",
    "bld4",
    "ORe",
    "r-r-8",
    "RAFap",
    "RBFaP",
    "RAR7p",
    "rAFaP",
    "rBFap",
    "rAFfP",
    "rCk",
    "rBFfp",
]

# Intentional quirk pinned: first dynamic token is concatenated without a
# separating space after the last "more" feature (see analyse_position).
GOLDEN_ANALYSE_FEATURE_STRING = (
    "Pb2 Pc2 Pf2 Ph2 Pd3 Pg3 Pa5 pc5 pa6 pc6 pf7 pg7 ph7 Ra1 Re7 ra8 rf8 "
    "Kg1 kg8 R>pf7 K<Pf2 K<Ph2 P<Pd3 P<Pg3 P<Pg3 R<Pa5 r<pa6 r<pf7 k<pf7 "
    "k<pg7 k<ph7 r<ra8 r<rf8 k<rf8 R<Kg1 r<kg8 Dpc whe5 bld4 ORe r-r-8 "
    "RAFap RBFaP RAR7p rAFaP rBFap rAFfP rCk rBFfp!kh8!r>R !re8"
)


def test_golden_static_features(app_module):
    assert app_module.get_static_features(README_FEN) == GOLDEN_STATIC


def test_golden_more_features(app_module):
    assert app_module.get_more_features(README_FEN) == GOLDEN_MORE


def test_golden_analyse_position_feature_string(app_module):
    # conftest sets LIMIT_DEPTH=1 and DummyStockfish legal ranking.
    feature_string, variants = app_module.analyse_position(README_FEN)
    assert feature_string == GOLDEN_ANALYSE_FEATURE_STRING
    assert "Kh8" in str(variants[0])
    assert "Rfe8" in str(variants[1])
