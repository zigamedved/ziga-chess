"""Pure chess feature tokens. No Stockfish and no HTTP."""
import chess
import chess.pgn
import numpy as np


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
        attackers = list(board.attackers(color, el))
        for att in attackers:
            figure = board.piece_at(att)
            res.append(figure.symbol() + relation +
                       piece + chess.square_name(el))
    return res


# returns an array of static feature of the position, example return value: ka2 Kb5...
def get_static_features(fen):
    board = chess.Board(fen)

    # gets locations of given piece, color
    wp = list(board.pieces(chess.PAWN, chess.WHITE))
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
                res.append(
                    'I' + 'P' + chess.FILE_NAMES[chess.square_file(square)])
            else:
                res.append(
                    'I' + 'p' + chess.FILE_NAMES[chess.square_file(square)])
    return np.array(res)


def is_att(color, squares, board, symbol):
    res = []
    for square in squares:
        if board.is_attacked_by(color, square) and board.piece_at(square) is None:
            res.append(symbol + chess.square_name(square))
    return res


def get_key_square_control(board):
    white = is_att(chess.WHITE, [chess.C5, chess.D5,
                   chess.E5, chess.F5], board, 'wh')
    black = is_att(chess.BLACK, [chess.C4, chess.D4,
                   chess.E4, chess.F4], board, 'bl')
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
    # Ora, black rook open a file
    wr_open_file = rooks_open_file(board, chess.WHITE, 'R')
    br_open_file = rooks_open_file(board, chess.BLACK, 'r')
    # R-R-a white rooks connected, a file, R-R-1 rank..
    wr_connected = are_rooks_connected(board, chess.WHITE, 'R')
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
    # 2 symbols, both sides, higher number -> less active king, kcc, close if <= 2
    ks = get_king_activity(board)
    # mty = get_position_mobility(board)  # returns legal moves
    # Ppa, passed black pawn a file
    ppw = get_passed_pawns(board, chess.WHITE, 'P')
    ppb = get_passed_pawns(board, chess.BLACK, 'p')
    ip = get_isolated_pawns(board)  # Ipa, isolated black pawn a file
    dp = get_doubled_pawns(board)  # Dpa, doubled black pawns a file
    # [wc5,wd5,we5], white controls c5, d5, e5
    ksc = get_key_square_control(board)
    rps = get_rook_placement_score(board)  # 10 numbers, white, black

    result = np.array([ks, ppw, ppb, ip, dp, ksc, rps],
                      dtype=object)  # removed mty
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
