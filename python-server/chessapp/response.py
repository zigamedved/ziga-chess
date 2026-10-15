"""Plain-text pieces shared by HTTP /analyse and the feature CLI."""


def format_pv(pgn) -> str:
    """PV body used in the text response.

    ``str(pgn)`` is a python-chess game with six header lines. The HTTP
    response drops those lines. Keep this the only place that does.
    """
    return "\n".join(str(pgn).split("\n")[6:])
