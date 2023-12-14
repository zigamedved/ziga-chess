from flask import Flask, jsonify, request, Response
from flask_httpauth import HTTPBasicAuth
import chess
import chess.pgn
import chess.engine
import numpy as np
import json
import urllib3
from stockfish import Stockfish
import os


app = Flask(__name__)
auth = HTTPBasicAuth()

stockfish = Stockfish(path='engine/stockfish-ubuntu-x86-64', depth=12,  # Stockfish_ARM stockfish
                      parameters={"Threads": 1, "Hash": 64})
stockfish.set_elo_rating(2500)
LIMIT_DEPTH = 8

CACHE = {}
prefix = os.environ.get("APP_PREFIX", "")


@auth.verify_password
def verify_password(username, password):
    return username == 'zigamedved' and password == 'skrivnost.1234'


def find_square(piece, elements):
    return [piece + chess.square_name(el) for el in elements]


def remove_castling_rights(fen):
    fen_list = fen.split(" ")
    fen_list[2] = "-"
    fen_list[3] = "-"
    return " ".join(fen_list)


def find_attackers(piece, color, elements, board, relation):
    res = []
    for el in elements:
        attackers = list(board.attackers(color, el))  # seznam lokacij ki napadajo ta square,tu vem kdo napada piece,x>p
        for att in attackers:
            figure = board.piece_at(att)
            res.append(figure.symbol() + relation + piece + chess.square_name(el))
            # za vsak attacker, naredi board.piece_at(attacker), pripni v rezultate attacker>piece
            # x dobis iz board.piece_at(list(board.attackers(chess.BLACK, square))[0])
    return res


# returns an array of static feature of the position, example return value: ka2 Kb5...
def get_static_features(fen):
    board = chess.Board(fen)

    wp = list(board.pieces(chess.PAWN, chess.WHITE))  # gets locations of given piece, color
    bp = list(board.pieces(chess.PAWN, chess.BLACK))

    wp_att = find_attackers('P', chess.BLACK, wp, board, '>')
    wp_def = find_attackers('P', chess.WHITE, wp, board, '<')  # defenders

    bp_att = find_attackers('p', chess.WHITE, bp, board, '>')
    bp_def = find_attackers('p', chess.BLACK, bp, board, '<')  # defenders

    wp_sq = find_square('P', wp)
    bp_sq = find_square('p', bp)

    wr = list(board.pieces(chess.ROOK, chess.WHITE))
    br = list(board.pieces(chess.ROOK, chess.BLACK))

    wr_att = find_attackers('R', chess.BLACK, wr, board, '>')
    wr_def = find_attackers('R', chess.WHITE, wr, board, '<')  # defenders

    br_att = find_attackers('r', chess.WHITE, br, board, '>')
    br_def = find_attackers('r', chess.BLACK, br, board, '<')  # defenders

    wr_sq = find_square('R', wr)
    br_sq = find_square('r', br)

    wk = list(board.pieces(chess.KING, chess.WHITE))
    bk = list(board.pieces(chess.KING, chess.BLACK))

    wk_att = find_attackers('K', chess.BLACK, wk, board, '>')
    wk_def = find_attackers('K', chess.WHITE, wk, board, '<')  # defenders

    bk_att = find_attackers('k', chess.WHITE, bk, board, '>')
    bk_def = find_attackers('k', chess.BLACK, bk, board, '<')  # defenders

    wk_sq = find_square('K', wk)
    bk_sq = find_square('k', bk)

    return wp_sq + bp_sq + wr_sq + br_sq + wk_sq + bk_sq + wp_att + bp_att + wr_att + br_att + wk_att + bk_att + wp_def + bp_def + wr_def + br_def + wk_def + bk_def


global_moves_list = []


def evaluate(fen, depth, branch_factor, moves, root):
    board = chess.Board(fen)
    if depth == LIMIT_DEPTH or board.is_stalemate() or board.is_checkmate():
        return [moves]

    # analyse current position
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


def get_attacked_pieces(squares, board, color, piece):
    defenders = ['P', 'R', 'K']
    if color:
        defenders = ['p', 'r', 'k']
    pawns = set(list(board.pieces(chess.PAWN, not color)))
    rooks = set(list(board.pieces(chess.ROOK, not color)))
    king = set(list(board.pieces(chess.KING, not color)))

    piece = str(piece)
    result = []
    for pawn in pawns:
        if pawn in squares:
            result.append('!' + piece + '>' + defenders[0])
    for rook in rooks:
        if rook in squares:
            result.append('!' + piece + '>' + defenders[1])
    if list(king)[0] in squares:
        result.append('!' + piece + '>' + defenders[2])
    return result


def get_dynamic_features(fen, best_moves):
    board = chess.Board(fen=fen)
    dynamic_moves = []
    ending_move = []
    general_dynamic = []
    new_static = set()

    for move in best_moves:
        is_capture = 0

        if board.is_capture(move):
            is_capture = 1

        board.push(move)
        string_move = str(move)[2:4]
        parsed_square = chess.parse_square(string_move)
        piece = board.piece_at(parsed_square)

        if is_capture:
            dynamic_moves.append('!' + piece.symbol() + 'x' + string_move)
            general_dynamic.append('!x')
        if board.is_checkmate():
            ending_move.append('!' + piece.symbol() + string_move + '#')
            general_dynamic.append('!#')
            break
        if board.is_insufficient_material() or board.is_stalemate():
            ending_move.append('?')  # piece.symbol() + string_move +
            break
        if board.is_check():
            dynamic_moves.append('!' + piece.symbol() + string_move + '+')
            general_dynamic.append('!+')

        dynamic_moves.append('!' + piece.symbol() + string_move)

        attacked_squares = get_attacked_pieces(list(board.attacks(parsed_square)), board,
                                               not board.turn, piece)
        new_static.update(attacked_squares)
    return list(new_static) + dynamic_moves + ending_move + general_dynamic


def get_king_activity(board):  # close to center, more active king
    res = []
    wk = board.king(chess.WHITE)
    wk_dist = (chess.square_distance(wk, chess.D4) + chess.square_distance(wk, chess.D5) + chess.square_distance(wk,
                                                                                                                 chess.E4) + chess.square_distance(
        wk, chess.E5)) / 4
    bk = board.king(chess.BLACK)
    bk_dist = (chess.square_distance(bk, chess.D4) + chess.square_distance(bk, chess.D5) + chess.square_distance(bk,
                                                                                                                 chess.E4) + chess.square_distance(
        bk, chess.E5)) / 4
    if wk_dist <= 2:
        res.append('Kcc')
    if bk_dist <= 2:
        res.append('kcc')
    return np.array(res)


def get_passed_pawns(board, color, symbol):  # Ppa, passed black pawn a file
    passed_pawns = []
    for square in list(board.pieces(chess.PAWN, color)):
        file_index = chess.square_file(square)
        file_index_left = file_index - 1
        file_index_right = file_index + 1
        if file_index == 0:
            file_index_left = 0
        if file_index == 7:
            file_index_right = 7
        if not (board.occupied_co[not color] &
                (chess.BB_FILES[file_index] |
                 chess.BB_FILES[file_index_left] |
                 chess.BB_FILES[file_index_right]) & board.pawns):
            passed_pawns.append('P' + symbol + chess.FILE_NAMES[file_index])
    return np.array(passed_pawns)


def get_position_mobility(board):
    return np.array(['L' + board.san(move) for move in list(board.legal_moves)])


def get_doubled_pawns(board):
    wp_s = {}
    bp_s = {}
    res = []
    for pawn in list(board.pieces(chess.PAWN, chess.WHITE)):
        file = chess.square_file(pawn)
        if file in wp_s.keys():
            res.append('D' + 'P' + chess.FILE_NAMES[file])
        else:
            wp_s[file] = 0
    for pawn in list(board.pieces(chess.PAWN, chess.BLACK)):
        file = chess.square_file(pawn)
        if file in bp_s.keys():
            res.append('D' + 'p' + chess.FILE_NAMES[file])
        else:
            bp_s[file] = 0
    return np.array(res)


def get_isolated_pawns(board):  # Ipa
    # Find all pawns on the board
    pawns = board.pawns
    res = []
    for square in chess.scan_forward(pawns):
        # Check if the pawn is isolated (i.e., has no pawns on adjacent files)
        if not any(board.pawns & chess.BB_FILES[file] for file in
                   (chess.square_file(square) - 1, chess.square_file(square) + 1) if 0 <= file <= 7):
            if board.color_at(square) == chess.WHITE:
                res.append('I' + 'P' + chess.FILE_NAMES[chess.square_file(square)])
            else:
                res.append('I' + 'p' + chess.FILE_NAMES[chess.square_file(square)])
    return np.array(res)


def is_att(color, squares, board, symbol):
    res = []
    for square in squares:
        if board.is_attacked_by(color, square) and board.piece_at(square) is None:
            res.append(symbol + chess.square_name(square))
    return res


def get_key_square_control(board):
    white = is_att(chess.WHITE, [chess.C5, chess.D5, chess.E5, chess.F5], board, 'wh')
    black = is_att(chess.BLACK, [chess.C4, chess.D4, chess.E4, chess.F4], board, 'bl')
    return np.array(white + black)


def calculate_rook_placement_score(board, color, symbol_r, symbol_k, symbol_p, symbol_ep):
    rooks = board.pieces(chess.ROOK, color)
    res = []
    for square in rooks:
        file = chess.square_file(square)
        rank = chess.square_rank(square)
        # Attack on enemy pawns, rank
        enemy_pawns = board.pieces(chess.PAWN, not color)
        if enemy_pawns & chess.BB_RANKS[rank]:
            res.append(symbol_r + 'AR' + chess.RANK_NAMES[rank] + symbol_ep)
        if enemy_pawns & chess.BB_FILES[file]:
            res.append(symbol_r + 'AF' + chess.FILE_NAMES[file] + symbol_ep)
        # King safety
        king_square = board.king(color)
        if chess.square_distance(square, king_square) <= 2:
            res.append(symbol_r + 'C' + symbol_k)
        # Pawn structure
        pawns = board.pieces(chess.PAWN, color)
        if pawns & chess.BB_FILES[file]:
            res.append(symbol_r + 'BF' + chess.FILE_NAMES[file] + symbol_p)
    return res


def rooks_open_file(board, color, symbol):  # Ora, black rook open a file
    res = []
    rooks = board.pieces(chess.ROOK, color)
    pieces = board.pieces(chess.KING, not color) | board.pieces(chess.KING, color) | board.pieces(chess.PAWN,
                                                                                                  not color) | board.pieces(
        chess.PAWN, color) | board.pieces(chess.ROOK, not color)
    for square in rooks:
        file = chess.square_file(square)
        file_pieces = pieces & chess.BB_FILES[file]
        if not file_pieces:
            res.append('O' + symbol + chess.FILE_NAMES[file])
    return res


def are_rooks_connected(board, color, symbol):
    rooks = list(board.pieces(chess.ROOK, color))
    if len(rooks) == 1 or len(rooks) == 0:
        return []
    if chess.square_file(rooks[0]) == chess.square_file(rooks[1]):
        return [symbol + '-' + symbol + '-' + chess.FILE_NAMES[chess.square_file(rooks[0])]]
    if chess.square_rank(rooks[0]) == chess.square_rank(rooks[1]):
        return [symbol + '-' + symbol + '-' + chess.RANK_NAMES[chess.square_rank(rooks[0])]]
    return []


def get_rook_placement_score(board):
    wr_open_file = rooks_open_file(board, chess.WHITE, 'R')  # Ora, black rook open a file
    br_open_file = rooks_open_file(board, chess.BLACK, 'r')
    wr_connected = are_rooks_connected(board, chess.WHITE, 'R')  # R-R-a white rooks connected, a file, R-R-1 rank..
    br_connected = are_rooks_connected(board, chess.BLACK, 'r')
    wr_connected = [x for x in wr_connected if x is not None]
    br_connected = [x for x in br_connected if x is not None]

    crsw = calculate_rook_placement_score(board, chess.WHITE, 'R', 'K', 'P',
                                          'p')  # 3 numbers, king safety, rook behind p
    crsb = calculate_rook_placement_score(board, chess.BLACK, 'r', 'k', 'p',
                                          'P')  # rp, rook behind pawn, rk- rook close to king

    return np.array(wr_open_file + br_open_file + wr_connected + br_connected + crsw + crsb)


def get_more_features(fen):
    board = chess.Board(fen=fen)
    ks = get_king_activity(board)  # 2 symbols, both sides, higher number -> less active king, kcc, close if <= 2
    # mty = get_position_mobility(board)  # returns legal moves
    ppw = get_passed_pawns(board, chess.WHITE, 'P')  # Ppa, passed black pawn a file
    ppb = get_passed_pawns(board, chess.BLACK, 'p')
    ip = get_isolated_pawns(board)  # Ipa, isolated black pawn a file
    dp = get_doubled_pawns(board)  # Dpa, doubled black pawns a file
    ksc = get_key_square_control(board)  # [wc5,wd5,we5], white controls c5, d5, e5
    rps = get_rook_placement_score(board)  # 10 numbers, white, black

    result = np.array([ks, ppw, ppb, ip, dp, ksc, rps], dtype=object)  # removed mty
    return [item for sub_list in result for item in sub_list]


def get_pgn(fen, best_moves):
    game = chess.pgn.Game()
    board = chess.Board(fen)
    game.setup(board)
    # Iterate through the list of best moves and add them to the game
    node = game
    for move in best_moves:
        node = node.add_variation(move)
    return game  # return game as string


def analyse_position(fen):
    stockfish.set_fen_position(fen)
    # evaluation = stockfish.get_evaluation()
    # print(evaluation)

    static_features = get_static_features(fen)
    more_features = get_more_features(fen)

    result = ' '.join(static_features + more_features)

    global global_moves_list
    global_moves_list = []
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


def prepare_response(filename):
    f = os.path.join("TODO", filename)
    with open(f, 'r') as file:
        try:
            data = file.readlines()
        except Exception as e:
            print(e)
            return []

    if len(data) == 0:
        return []

    first, last = 0, 0
    for idx, i in enumerate(data):
        if i == '########################################\n':
            first = idx
        if first != 0 and i == '\n':
            last = idx + 1
            break
    if first == 0 or last == 0:
        return []
    sublist = data[first + 1:last - 1]
    # Create a Python dictionary for the JSON document
    game_data = {}

    for item in sublist:
        if item == '\n':
            continue
        string = item.split('"')
        game_data[string[0][1:-1]] = string[1]
    game_data["name"] = filename
    game_data["PGN"] = data[last]
    return game_data


def parse_game(data, name):
    if len(data) == 0:
        return []

    first, last = 0, 0
    for idx, i in enumerate(data):
        if i == '########################################\n':
            first = idx
        if first != 0 and i == '\n':
            last = idx + 1
            break
    if first == 0 or last == 0:
        return []
    sublist = data[first + 1:last - 1]
    # Create a Python dictionary for the JSON document
    game_data = {}

    for item in sublist:
        if item == '\n':
            continue
        # string = item.split("\n")[0][1:-2].replace('"','')
        string = item.split('"')
        game_data[string[0][1:-1]] = string[1]
    game_data["name"] = name
    game_data["PGN"] = data[last]
    return game_data


@app.route(prefix + '/hello', methods=['GET'])
@auth.login_required
def hello():
    return jsonify({'message': 'Hello, this is the response from the server!'})


@app.route(prefix + '/analyse', methods=['POST'])
@auth.login_required
def analyse():
    data = request.get_json()
    fen = data["FEN"]
    if not stockfish.is_fen_valid(fen):
        return Response("Invalid FEN!", content_type="text/plain")

    if fen in CACHE.keys():
        return Response(CACHE[fen], content_type="text/plain")

    result, [pgn1, pgn2] = analyse_position(fen)
    data = result
    http = urllib3.PoolManager()
    encoded_data = json.dumps(data)

    request_url = "http://0.0.0.0:8080/position"

    r = http.request(
        method='POST',
        url=request_url,
        body=encoded_data,
        headers={
            "Authorization": request.headers.get("Authorization"),
            "Content-Type": "application/json"
        }
    )
    if r.status != 200:
        print(f"Request failed with status code: {r.status}")
        return jsonify(f"Request failed with status code: {r.status}")

    # r = {
    #     "data": [
    #         "{\"_id\": {\"$oid\": \"64c4ac08cc2c6865c4337e98\"}, \"Event\": \"EU-chT (Men)\", \"Site\": \"Pula\", \"Date\": \"1997.??.??\", \"Round\": \"9\", \"White\": \"Cvitan, Ognjen\", \"Black\": \"Yrjola, Jouni\", \"Result\": \"1/2-1/2\", \"BlackElo\": \"2435\", \"ECO\": \"E11\", \"WhiteElo\": \"2550\", \"endgameFEN\": \"r4rk1/p1p2ppp/2pp4/8/2PP4/6PP/PP3P2/R4RK1 w - - 0 18\", \"positionType\": \"original\", \"sourceFile\": \"./actualGames\\\\Bogo4Bd2.pgn\", \"name\": \"Game#52802.txt\", \"PGN\": \"1. d4 Nf6 2. Nf3 e6 3. c4 Bb4+ 4. Bd2 Qe7 5. g3 Nc6 6. Nc3 Bxc3 7. Bxc3 Ne4 8. Qc2 Nxc3 9. Qxc3 O-O 10. Bg2 d6 11. O-O e5 12. e3 Bg4 13. h3 Bxf3 14. Bxf3 Qf6 15. Bxc6 exd4 16. Qxd4 Qxd4 17. exd4 bxc6 18. Rfe1 Rfe8 19. Kf1 Kf8 1/2-1/2\\n\", \"score\": 58.24555969238281}",
    #         "{\"_id\": {\"$oid\": \"64c4ac08cc2c6865c4337e99\"}, \"Event\": \"6th LUCOPEN 2015\", \"Site\": \"Lille FRA\", \"Date\": \"2015.05.09\", \"Round\": \"8.1\", \"White\": \"Demuth,A\", \"Black\": \"Sagar,S\", \"Result\": \"1/2-1/2\", \"BlackElo\": \"2436\", \"ECO\": \"E00\", \"WhiteElo\": \"2515\", \"endgameFEN\": \"r4rk1/pp3ppp/8/8/1P2R3/P5P1/5P1P/R5K1 b - - 0 24\", \"positionType\": \"original\", \"sourceFile\": \"./actualGames\\\\Catalan3Bb4.pgn\", \"name\": \"Game#122214.txt\", \"PGN\": \"1. d4 Nf6 2. c4 e6 3. g3 Bb4+ 4. Bd2 Qe7 5. Nf3 Bxd2+ 6. Qxd2 Nc6 7. b3 d5 8. Bg2 Ne4 9. Qd3 O-O 10. O-O Nb4 11. Qd1 c5 12. a3 Nc6 13. dxc5 Qxc5 14. cxd5 exd5 15. b4 Qd6 16. Qd3 Ne5 17. Nxe5 Qxe5 18. Nd2 Nc3 19. Rfe1 Bf5 20. e4 Nxe4 21. Nxe4 dxe4 22. Bxe4 Bxe4 23. Qxe4 Qxe4 24. Rxe4 f5 25. Re7 Rf7 26. Rae1 Kf8 27. Rxf7+ 1/2-1/2\\n\", \"score\": 62.60334777832031}",
    #         "{\"_id\": {\"$oid\": \"64c4ac08cc2c6865c4337e9a\"}, \"Event\": \"Titled Tue 27th Dec Early\", \"Site\": \"chess.com INT\", \"Date\": \"2022.12.27\", \"Round\": \"5\", \"White\": \"Christiansen,Johan-Sebastian\", \"Black\": \"Guliev,L\", \"Result\": \"1-0\", \"BlackElo\": \"2261\", \"BlackFideId\": \"13400061\", \"BlackTitle\": \"IM\", \"ECO\": \"B50\", \"EventDate\": \"2022.12.27\", \"Opening\": \"Sicilian\", \"WhiteElo\": \"2578\", \"WhiteFideId\": \"1512668\", \"WhiteTitle\": \"GM\", \"endgameFEN\": \"r4rk1/4Rppp/p1p5/P1p5/8/3P2P1/1PP2P1P/R5K1 b - - 0 25\", \"positionType\": \"original\", \"sourceFile\": \"./actualGames\\\\twic1469.pgn\", \"name\": \"Game#894776.txt\", \"PGN\": \"1. e4 c5 2. Nf3 d6 3. Nc3 a6 4. g3 e6 5. Bg2 Nf6 6. e5 dxe5 7. Nxe5 Be7 8. a4 O-O 9. O-O Nbd7 10. Nc4 Nb6 11. Nxb6 Qxb6 12. a5 Qc7 13. d3 Bd7 14. Bf4 e5 15. Bg5 Bc6 16. Bxc6 Qxc6 17. Qe2 Qe6 18. Rfe1 Qh3 19. Bxf6 Bxf6 20. Qf3 Qd7 21. Nd5 Qc6 22. Rxe5 Bd8 23. Ne7+ Bxe7 24. Qxc6 bxc6 25. Rxe7 c4 26. dxc4 Rfd8 27. Rae1 Kf8 28. Rc7 Rd6 29. Ree7 Rf6 30. Kg2 g6 31. Re3 Rd8 32. Rf3 Rxf3 33. Kxf3 Rd2 34. Rxc6 Rxc2 35. b4 Rb2 36. Rb6 Ke7 37. b5 axb5 38. cxb5 Rb3+ 39. Ke4 Kd7 40. Rb7+ Ke6 41. a6 f5+ 42. Kd4 1-0\\n\", \"score\": 85.13201904296875}",
    #         "{\"_id\": {\"$oid\": \"64c4ac08cc2c6865c4337e9b\"}, \"Event\": \"TCh-NOR Elite 2015-16\", \"Site\": \"Norway NOR\", \"Date\": \"2016.01.17\", \"Round\": \"6.3\", \"White\": \"Kaasen,Tor Fredrik\", \"Black\": \"Gamback,B\", \"Result\": \"1-0\", \"BlackElo\": \"2134\", \"ECO\": \"B52\", \"WhiteElo\": \"2080\", \"endgameFEN\": \"r3r1k1/p4ppp/2p5/2pp4/P7/3P4/1PP2PPP/R3R1K1 w - - 0 21\", \"positionType\": \"original\", \"sourceFile\": \"./actualGames\\\\SicilianMoscow.pgn\", \"name\": \"Game#776572.txt\", \"PGN\": \"1. e4 c5 2. Nf3 d6 3. Bb5+ Bd7 4. Bxd7+ Qxd7 5. O-O Nf6 6. e5 dxe5 7. Nxe5 Qd6 8. Re1 Nc6 9. Nc4 Qd8 10. Nc3 e6 11. d3 Be7 12. Bf4 O-O 13. a4 Nd5 14. Nxd5 exd5 15. Ne5 Bf6 16. Qg4 Nxe5 17. Bxe5 Re8 18. Bxf6 Qxf6 19. Qd7 Qc6 20. Qxc6 bxc6 21. Kf1 Kf8 22. Ra3 Rab8 23. Rc3 Rxb2 24. Rxc5 Rc8 25. Re5 Rb4 26. a5 Rbb8 27. f4 a6 28. Kf2 Ra8 29. g4 h6 30. Kf3 Rab8 31. Ke3 Ra8 32. h4 Rab8 33. g5 hxg5 34. hxg5 g6 35. f5 gxf5 36. Kf4 Rb5 37. Rexd5 Rxa5 38. Rxf5 Rxc5 39. Rxc5 Ke7 40. Ke5 Rc7 41. d4 Kd7 42. c4 Ra7 43. Kf6 a5 44. Kxf7 a4 45. Re5 Kc8+ 46. Kf8 a3 47. Re8+ Kc7 48. Re7+ Kb8 49. Re1 Rd7 50. Rg1 Rxd4 51. g6 Rf4+ 52. Ke7 Re4+ 53. Kf6 a2 54. g7 Rg4 55. g8=Q+ Rxg8 56. Rxg8+ Kb7 57. Rg1 Kb6 58. Ra1 Kc5 59. Rxa2 Kxc4 60. Rc2+ Kd5 61. Kf5 c5 62. Rd2+ Kc4 63. Ke4 Kc3 64. Rd3+ Kc2 65. Rd5 c4 66. Rc5 c3 67. Kd4 Kb2 68. Rxc3 Ka2 69. Rh3 Kb2 70. Kc4 Ka2 71. Kb4 Kb2 72. Rh2+ Kc1 73. Kc3 Kd1 74. Ra2 Ke1 75. Kd3 Kf1 76. Ke3 Kg1 77. Kf3 Kh1 78. Kg3 Kg1 79. Ra1# 1-0\\n\", \"score\": 57.81740188598633}"
    #     ]
    # }

    res = [json.loads(item) for item in json.loads(r.data)]
    CACHE[fen] = res

    # SORT
    res = sorted(res, key=lambda x: x["score"], reverse=True)

    linesPgn1 = str(pgn1).split('\n')[6:]
    parsedPgn1 = '\n'.join(linesPgn1)

    linesPgn2 = str(pgn2).split('\n')[6:]
    parsedPgn2 = '\n'.join(linesPgn2)

    text_responses = []
    text_responses.append("\n\n".join(["Analysed lines of input game:", "PV1: \n"+parsedPgn1, "PV2: \n"+parsedPgn2]))
    idx = 0
    text_responses.append("\nSimilar games:")
    for data_dict in res:
        text_response = "\n".join([f"{key}: {value}" for key, value in data_dict.items()])
        text_responses.append(f"\nGame #{idx}")
        idx += 1
        text_responses.append(text_response)

    # Join the text responses with a separator (e.g., a blank line) if needed
    plain_text_response = "\n\n".join(text_responses)
    CACHE[fen] = plain_text_response

    return Response(plain_text_response, content_type="text/plain")
    # return jsonify(r)

@app.route('/ping')
@app.route(prefix + '/ping')
def ping():
    return 'pong'


if __name__ == '__main__':
    app.run(host="0.0.0.0", port=8081, debug=True)
