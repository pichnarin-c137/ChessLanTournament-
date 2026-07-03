package chess.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Full chess rules engine. Pure Java, no UI or network dependencies.
 *
 * <p>Squares are 0..63 with a1 = 0, h1 = 7, a8 = 56, h8 = 63.
 * Draws by stalemate, the 50-move rule, threefold repetition and
 * insufficient material are declared automatically.
 */
public final class Game {

    private static final int[][] KNIGHT_DIRS =
            {{1, 2}, {2, 1}, {2, -1}, {1, -2}, {-1, -2}, {-2, -1}, {-2, 1}, {-1, 2}};
    private static final int[][] KING_DIRS =
            {{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};
    private static final int[][] ROOK_DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] BISHOP_DIRS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private final Piece[] board = new Piece[64];
    private Color turn = Color.WHITE;
    private boolean castleWK = true, castleWQ = true, castleBK = true, castleBQ = true;
    private int epSquare = -1; // square a double-pushed pawn skipped over, else -1
    private int halfmoveClock = 0;
    private GameStatus status = GameStatus.ONGOING;
    private Move lastMove;
    private final Map<String, Integer> repetition = new HashMap<>();
    private final List<String> history = new ArrayList<>();

    public Game() {
        PieceType[] back = {PieceType.ROOK, PieceType.KNIGHT, PieceType.BISHOP, PieceType.QUEEN,
                PieceType.KING, PieceType.BISHOP, PieceType.KNIGHT, PieceType.ROOK};
        for (int f = 0; f < 8; f++) {
            board[f] = new Piece(Color.WHITE, back[f]);
            board[8 + f] = new Piece(Color.WHITE, PieceType.PAWN);
            board[48 + f] = new Piece(Color.BLACK, PieceType.PAWN);
            board[56 + f] = new Piece(Color.BLACK, back[f]);
        }
        repetition.put(positionKey(), 1);
    }

    private Game(boolean ignored) {
    }

    /**
     * Builds a game from an arbitrary position, mainly for tests.
     *
     * @param board64  64 chars, index 0 = a1; FEN letters, '.' for empty
     * @param castling subset of "KQkq", or "-"
     * @param epSquare en-passant target square, or -1
     */
    public static Game fromPosition(String board64, Color turn, String castling, int epSquare) {
        if (board64.length() != 64) throw new IllegalArgumentException("board64 must be 64 chars");
        Game g = new Game(true);
        for (int i = 0; i < 64; i++) {
            char c = board64.charAt(i);
            if (c != '.') {
                g.board[i] = new Piece(Character.isUpperCase(c) ? Color.WHITE : Color.BLACK,
                        PieceType.fromLetter(Character.toUpperCase(c)));
            }
        }
        g.turn = turn;
        g.castleWK = castling.contains("K");
        g.castleWQ = castling.contains("Q");
        g.castleBK = castling.contains("k");
        g.castleBQ = castling.contains("q");
        g.epSquare = epSquare;
        g.repetition.put(g.positionKey(), 1);
        g.updateStatus();
        return g;
    }

    public Color turn() {
        return turn;
    }

    public GameStatus status() {
        return status;
    }

    public Move lastMove() {
        return lastMove;
    }

    public Piece pieceAt(int square) {
        return board[square];
    }

    /** The winner if the game ended in checkmate, else null. */
    public Color winner() {
        return status == GameStatus.CHECKMATE ? turn.opposite() : null;
    }

    /** Is the side to move in check? */
    public boolean inCheck() {
        return inCheck(turn);
    }

    public boolean inCheck(Color side) {
        return isSquareAttacked(board, kingSquare(board, side), side.opposite());
    }

    /** 64 chars, index 0 = a1; FEN letters, '.' for empty. */
    public String boardString() {
        StringBuilder sb = new StringBuilder(64);
        for (Piece p : board) sb.append(p == null ? '.' : p.fen());
        return sb.toString();
    }

    public List<Move> legalMoves() {
        List<Move> out = new ArrayList<>();
        for (Move m : pseudoLegalMoves(turn)) {
            if (!leavesKingInCheck(m)) out.add(m);
        }
        return out;
    }

    public List<String> legalMovesUci() {
        return legalMoves().stream().map(Move::uci).toList();
    }

    /** Every move played so far, in standard algebraic notation (e.g. "Nf3", "O-O", "Qh4#"). */
    public List<String> moveHistory() {
        return List.copyOf(history);
    }

    /**
     * Applies a move for the side to move. Throws {@link IllegalStateException}
     * if the game is over, {@link IllegalArgumentException} if the move is illegal.
     */
    public void makeMove(Move move) {
        if (status.isOver()) throw new IllegalStateException("game is over");
        if (!legalMoves().contains(move)) {
            throw new IllegalArgumentException("illegal move: " + move.uci());
        }
        Piece piece = board[move.from()];
        boolean isPawn = piece.type() == PieceType.PAWN;
        boolean capture = board[move.to()] != null || (isPawn && move.to() == epSquare);
        String san = san(move); // needs the pre-move position

        applyToBoard(board, move, epSquare);
        updateCastlingRights(piece, move);
        epSquare = isPawn && Math.abs(move.to() - move.from()) == 16
                ? (move.from() + move.to()) / 2
                : -1;
        halfmoveClock = (isPawn || capture) ? 0 : halfmoveClock + 1;
        turn = turn.opposite();
        lastMove = move;
        repetition.merge(positionKey(), 1, Integer::sum);
        updateStatus();
        history.add(san + (status == GameStatus.CHECKMATE ? "#" : inCheck() ? "+" : ""));
    }

    /** Standard algebraic notation for a legal move in the current position, without +/#. */
    private String san(Move m) {
        Piece p = board[m.from()];
        if (p.type() == PieceType.KING && Math.abs((m.from() & 7) - (m.to() & 7)) == 2) {
            return (m.to() & 7) == 6 ? "O-O" : "O-O-O";
        }
        String dest = "" + (char) ('a' + (m.to() & 7)) + (char) ('1' + (m.to() >> 3));
        boolean capture = board[m.to()] != null
                || (p.type() == PieceType.PAWN && m.to() == epSquare
                        && (m.from() & 7) != (m.to() & 7));
        if (p.type() == PieceType.PAWN) {
            String s = capture ? (char) ('a' + (m.from() & 7)) + "x" + dest : dest;
            return m.promotion() == null ? s : s + "=" + m.promotion().letter();
        }
        // Disambiguate when another piece of the same type can reach the same square.
        boolean ambiguous = false, sameFile = false, sameRank = false;
        for (Move other : legalMoves()) {
            if (other.from() == m.from() || other.to() != m.to()
                    || board[other.from()].type() != p.type()) continue;
            ambiguous = true;
            if ((other.from() & 7) == (m.from() & 7)) sameFile = true;
            if ((other.from() >> 3) == (m.from() >> 3)) sameRank = true;
        }
        StringBuilder s = new StringBuilder().append(p.type().letter());
        if (ambiguous) {
            if (!sameFile) s.append((char) ('a' + (m.from() & 7)));
            else if (!sameRank) s.append((char) ('1' + (m.from() >> 3)));
            else s.append((char) ('a' + (m.from() & 7))).append((char) ('1' + (m.from() >> 3)));
        }
        return s.append(capture ? "x" : "").append(dest).toString();
    }

    // -- rules bookkeeping ---------------------------------------------------

    private void updateCastlingRights(Piece moved, Move m) {
        if (moved.type() == PieceType.KING) {
            if (moved.color() == Color.WHITE) castleWK = castleWQ = false;
            else castleBK = castleBQ = false;
        }
        // A rook moving from, or anything capturing on, a corner kills that right.
        if (m.from() == 0 || m.to() == 0) castleWQ = false;
        if (m.from() == 7 || m.to() == 7) castleWK = false;
        if (m.from() == 56 || m.to() == 56) castleBQ = false;
        if (m.from() == 63 || m.to() == 63) castleBK = false;
    }

    private void updateStatus() {
        if (legalMoves().isEmpty()) {
            status = inCheck(turn) ? GameStatus.CHECKMATE : GameStatus.STALEMATE;
        } else if (insufficientMaterial()) {
            status = GameStatus.DRAW_INSUFFICIENT_MATERIAL;
        } else if (halfmoveClock >= 100) {
            status = GameStatus.DRAW_FIFTY_MOVES;
        } else if (repetition.getOrDefault(positionKey(), 0) >= 3) {
            status = GameStatus.DRAW_THREEFOLD;
        }
    }

    private boolean insufficientMaterial() {
        int knights = 0;
        boolean bishopOnLight = false, bishopOnDark = false;
        for (int sq = 0; sq < 64; sq++) {
            Piece p = board[sq];
            if (p == null || p.type() == PieceType.KING) continue;
            switch (p.type()) {
                case KNIGHT -> knights++;
                case BISHOP -> {
                    if (((sq >> 3) + (sq & 7)) % 2 == 0) bishopOnDark = true;
                    else bishopOnLight = true;
                }
                default -> {
                    return false; // pawn, rook or queen: mating material
                }
            }
        }
        boolean anyBishop = bishopOnLight || bishopOnDark;
        if (knights == 0) return !(bishopOnLight && bishopOnDark); // K vs K, K+B(s) on one color
        return knights == 1 && !anyBishop; // K+N vs K
    }

    // Note: the raw ep square is part of the key even when no en-passant capture
    // is actually possible — a slight deviation from the strict FIDE definition
    // that only makes threefold marginally harder to reach.
    private String positionKey() {
        return boardString() + turn
                + (castleWK ? "K" : "") + (castleWQ ? "Q" : "")
                + (castleBK ? "k" : "") + (castleBQ ? "q" : "")
                + epSquare;
    }

    /** Test hook for the 50-move rule. */
    void setHalfmoveClock(int value) {
        halfmoveClock = value;
    }

    // -- move generation -----------------------------------------------------

    private List<Move> pseudoLegalMoves(Color side) {
        List<Move> out = new ArrayList<>(48);
        for (int sq = 0; sq < 64; sq++) {
            Piece p = board[sq];
            if (p == null || p.color() != side) continue;
            switch (p.type()) {
                case PAWN -> pawnMoves(sq, side, out);
                case KNIGHT -> stepMoves(sq, side, KNIGHT_DIRS, out);
                case BISHOP -> slideMoves(sq, side, BISHOP_DIRS, out);
                case ROOK -> slideMoves(sq, side, ROOK_DIRS, out);
                case QUEEN -> {
                    slideMoves(sq, side, ROOK_DIRS, out);
                    slideMoves(sq, side, BISHOP_DIRS, out);
                }
                case KING -> {
                    stepMoves(sq, side, KING_DIRS, out);
                    castleMoves(side, out);
                }
            }
        }
        return out;
    }

    private void pawnMoves(int sq, Color side, List<Move> out) {
        int dir = side == Color.WHITE ? 8 : -8;
        int startRank = side == Color.WHITE ? 1 : 6;
        int file = sq & 7, rank = sq >> 3;
        int one = sq + dir;
        if (one >= 0 && one < 64 && board[one] == null) {
            addPawnMove(sq, one, side, out);
            int two = sq + 2 * dir;
            if (rank == startRank && board[two] == null) out.add(new Move(sq, two, null));
        }
        for (int df : new int[]{-1, 1}) {
            int nf = file + df;
            int to = one + df;
            if (nf < 0 || nf > 7 || to < 0 || to >= 64) continue;
            Piece target = board[to];
            if (target != null && target.color() != side) addPawnMove(sq, to, side, out);
            else if (target == null && to == epSquare) out.add(new Move(sq, to, null));
        }
    }

    private static void addPawnMove(int from, int to, Color side, List<Move> out) {
        int promoRank = side == Color.WHITE ? 7 : 0;
        if ((to >> 3) == promoRank) {
            out.add(new Move(from, to, PieceType.QUEEN));
            out.add(new Move(from, to, PieceType.ROOK));
            out.add(new Move(from, to, PieceType.BISHOP));
            out.add(new Move(from, to, PieceType.KNIGHT));
        } else {
            out.add(new Move(from, to, null));
        }
    }

    private void stepMoves(int sq, Color side, int[][] dirs, List<Move> out) {
        int file = sq & 7, rank = sq >> 3;
        for (int[] d : dirs) {
            int nf = file + d[0], nr = rank + d[1];
            if (nf < 0 || nf > 7 || nr < 0 || nr > 7) continue;
            Piece target = board[nr * 8 + nf];
            if (target == null || target.color() != side) out.add(new Move(sq, nr * 8 + nf, null));
        }
    }

    private void slideMoves(int sq, Color side, int[][] dirs, List<Move> out) {
        int file = sq & 7, rank = sq >> 3;
        for (int[] d : dirs) {
            int nf = file + d[0], nr = rank + d[1];
            while (nf >= 0 && nf <= 7 && nr >= 0 && nr <= 7) {
                Piece target = board[nr * 8 + nf];
                if (target == null) {
                    out.add(new Move(sq, nr * 8 + nf, null));
                } else {
                    if (target.color() != side) out.add(new Move(sq, nr * 8 + nf, null));
                    break;
                }
                nf += d[0];
                nr += d[1];
            }
        }
    }

    private void castleMoves(Color side, List<Move> out) {
        int base = side == Color.WHITE ? 0 : 56; // rank of the king's home row
        Color enemy = side.opposite();
        boolean kingside = side == Color.WHITE ? castleWK : castleBK;
        boolean queenside = side == Color.WHITE ? castleWQ : castleBQ;
        if (!isPiece(base + 4, side, PieceType.KING)) return;
        // The king's destination square is covered by the leavesKingInCheck filter.
        if (kingside && isPiece(base + 7, side, PieceType.ROOK)
                && board[base + 5] == null && board[base + 6] == null
                && !isSquareAttacked(board, base + 4, enemy)
                && !isSquareAttacked(board, base + 5, enemy)) {
            out.add(new Move(base + 4, base + 6, null));
        }
        if (queenside && isPiece(base, side, PieceType.ROOK)
                && board[base + 1] == null && board[base + 2] == null && board[base + 3] == null
                && !isSquareAttacked(board, base + 4, enemy)
                && !isSquareAttacked(board, base + 3, enemy)) {
            out.add(new Move(base + 4, base + 2, null));
        }
    }

    private boolean isPiece(int sq, Color color, PieceType type) {
        Piece p = board[sq];
        return p != null && p.color() == color && p.type() == type;
    }

    // -- legality ------------------------------------------------------------

    private boolean leavesKingInCheck(Move m) {
        Piece[] copy = board.clone();
        Color mover = copy[m.from()].color();
        applyToBoard(copy, m, epSquare);
        return isSquareAttacked(copy, kingSquare(copy, mover), mover.opposite());
    }

    /** Pure board mechanics: normal moves, en-passant capture, castling rook, promotion. */
    private static void applyToBoard(Piece[] b, Move m, int epSquare) {
        Piece p = b[m.from()];
        if (p.type() == PieceType.PAWN && m.to() == epSquare && b[m.to()] == null
                && (m.from() & 7) != (m.to() & 7)) {
            int dir = p.color() == Color.WHITE ? 8 : -8;
            b[m.to() - dir] = null; // the passed pawn
        }
        if (p.type() == PieceType.KING && Math.abs((m.from() & 7) - (m.to() & 7)) == 2) {
            int base = m.from() & ~7;
            if ((m.to() & 7) == 6) { // kingside: rook h -> f
                b[base + 5] = b[base + 7];
                b[base + 7] = null;
            } else { // queenside: rook a -> d
                b[base + 3] = b[base];
                b[base] = null;
            }
        }
        b[m.to()] = m.promotion() != null ? new Piece(p.color(), m.promotion()) : p;
        b[m.from()] = null;
    }

    private static int kingSquare(Piece[] b, Color side) {
        for (int sq = 0; sq < 64; sq++) {
            Piece p = b[sq];
            if (p != null && p.color() == side && p.type() == PieceType.KING) return sq;
        }
        throw new IllegalStateException("no " + side + " king on the board");
    }

    private static boolean isSquareAttacked(Piece[] b, int sq, Color by) {
        int file = sq & 7, rank = sq >> 3;
        for (int[] d : KNIGHT_DIRS) {
            if (isAt(b, file + d[0], rank + d[1], by, PieceType.KNIGHT)) return true;
        }
        for (int[] d : KING_DIRS) {
            if (isAt(b, file + d[0], rank + d[1], by, PieceType.KING)) return true;
        }
        int pawnRank = by == Color.WHITE ? rank - 1 : rank + 1; // where an attacking pawn sits
        if (isAt(b, file - 1, pawnRank, by, PieceType.PAWN)
                || isAt(b, file + 1, pawnRank, by, PieceType.PAWN)) {
            return true;
        }
        for (int[] d : KING_DIRS) { // all 8 ray directions
            boolean diagonal = d[0] != 0 && d[1] != 0;
            int nf = file + d[0], nr = rank + d[1];
            while (nf >= 0 && nf <= 7 && nr >= 0 && nr <= 7) {
                Piece p = b[nr * 8 + nf];
                if (p != null) {
                    if (p.color() == by && (p.type() == PieceType.QUEEN
                            || p.type() == (diagonal ? PieceType.BISHOP : PieceType.ROOK))) {
                        return true;
                    }
                    break;
                }
                nf += d[0];
                nr += d[1];
            }
        }
        return false;
    }

    private static boolean isAt(Piece[] b, int file, int rank, Color color, PieceType type) {
        if (file < 0 || file > 7 || rank < 0 || rank > 7) return false;
        Piece p = b[rank * 8 + file];
        return p != null && p.color() == color && p.type() == type;
    }
}
