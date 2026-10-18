"""Plain-text pieces shared by HTTP /analyse and the feature CLI."""


def format_pv(pgn) -> str:
    """PV body used in the text response.

    ``str(pgn)`` is a python-chess game with six header lines. The HTTP
    response drops those lines. Keep this the only place that does.
    """
    return "\n".join(str(pgn).split("\n")[6:])


def format_analyse_text(pv1: str, pv2: str, games) -> str:
    """Plain-text /analyse body. Java ``AnalyseText`` mirrors this join."""
    text_responses = []
    text_responses.append("\n\n".join(
        ["Analysed lines of input game:", "PV1: \n" + pv1, "PV2: \n" + pv2]))
    text_responses.append("\nSimilar games:")
    for idx, data_dict in enumerate(games):
        text_response = "\n".join(
            f"{key}: {value}" for key, value in data_dict.items())
        text_responses.append(f"\nGame #{idx}")
        text_responses.append(text_response)
    return "\n\n".join(text_responses)
