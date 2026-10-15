"""Flask routes: /analyse, /hello, /ping."""

import json
import os

from flask import Flask, Response, jsonify, request
from flask_httpauth import HTTPBasicAuth

from chessapp.cache import CACHE
from chessapp import engine
from chessapp.response import format_pv
from chessapp.search_client import JAVA_SERVICE_URL, http_pool

app = Flask(__name__)
auth = HTTPBasicAuth()
prefix = os.environ.get("APP_PREFIX", "")


def _load_required_secret(name: str) -> str:
    value = os.environ.get(name)
    if value is None or value.strip() == "":
        raise RuntimeError(
            f"{name} must be set via the environment. "
            "See .env.example — hardcoded credentials have been removed."
        )
    return value


AUTH_USERNAME = _load_required_secret("AUTH_USERNAME")
AUTH_PASSWORD = _load_required_secret("AUTH_PASSWORD")


@auth.verify_password
def verify_password(username, password):
    return username == AUTH_USERNAME and password == AUTH_PASSWORD


@app.route(prefix + '/hello', methods=['GET'])
@auth.login_required
def hello():
    return jsonify({'message': 'Hello, this is the response from the server!'})


@app.route(prefix + '/analyse', methods=['POST'])
@auth.login_required
def analyse():
    data = request.get_json(silent=True)
    if not isinstance(data, dict) or not isinstance(data.get("FEN"), str) or not data["FEN"].strip():
        return jsonify({"error": "FEN is required (string)", "code": "bad_request"}), 400

    fen = data["FEN"].strip()
    cached = CACHE.get(fen)
    if cached is not None:
        return Response(cached, content_type="text/plain")

    if not engine._engine_lock.acquire(timeout=engine.ENGINE_LOCK_TIMEOUT_SECONDS):
        return jsonify({
            "error": "Stockfish is busy; retry shortly",
            "code": "engine_busy",
        }), 503

    try:
        cached = CACHE.get(fen)
        if cached is not None:
            return Response(cached, content_type="text/plain")
        if not engine.stockfish.is_fen_valid(fen):
            return jsonify({"error": "Invalid FEN!", "code": "invalid_fen"}), 400
        result, [pgn1, pgn2] = engine.analyse_position(fen)
    finally:
        engine._engine_lock.release()

    encoded_data = json.dumps(result)

    r = http_pool().request(
        method='POST',
        url=JAVA_SERVICE_URL,
        body=encoded_data,
        headers={
            "Authorization": request.headers.get("Authorization"),
            "Content-Type": "application/json"
        }
    )
    if r.status != 200:
        print(f"Request failed with status code: {r.status}")
        return jsonify(f"Request failed with status code: {r.status}")

    res = [json.loads(item) for item in json.loads(r.data)]

    # Lucene already returns this page in score order (LUCENE_TOP_K, default 20).
    res = sorted(res, key=lambda x: x["score"], reverse=True)

    parsedPgn1 = format_pv(pgn1)
    parsedPgn2 = format_pv(pgn2)

    text_responses = []
    text_responses.append("\n\n".join(
        ["Analysed lines of input game:", "PV1: \n"+parsedPgn1, "PV2: \n"+parsedPgn2]))
    idx = 0
    text_responses.append("\nSimilar games:")
    for data_dict in res:
        text_response = "\n".join(
            [f"{key}: {value}" for key, value in data_dict.items()])
        text_responses.append(f"\nGame #{idx}")
        idx += 1
        text_responses.append(text_response)

    plain_text_response = "\n\n".join(text_responses)
    CACHE[fen] = plain_text_response

    return Response(plain_text_response, content_type="text/plain")


@app.route('/ping')
@app.route(prefix + '/ping')
def ping():
    return 'pong'
