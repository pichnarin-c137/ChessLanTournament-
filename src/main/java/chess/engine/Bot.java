package chess.engine;

import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * The built-in computer player: negamax with alpha-beta pruning over
 * {@link Game#copy() position copies}, scoring leaves by material and a
 * little piece placement. No opening book, no stored knowledge — strength
 * comes purely from search depth, and a pinch of random noise keeps its
 * games from repeating.
 */
public final class Bot {

    /** Difficulty: how many plies the search looks ahead, and how noisy it scores. */
    public enum Level {
        EASY(1, 75), MEDIUM(2, 25), HARD(4, 5);

        private final int depth;
        private final int noise; // max random centipawns added to a leaf score

        Level(int depth, int noise) {
            this.depth = depth;
            this.noise = noise;
        }
    }

    static final int MATE = 100_000;

    private Bot() {
    }

    /** The bot's move for the side to play in {@code g} (null only if the game is over). */
    public static Move choose(Game g, Level level, Random rnd) {
        Move best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Move m : ordered(g)) {
            Game child = g.copy();
            child.applyUnchecked(m);
            int score = -negamax(child, level.depth - 1, -MATE, MATE, level.noise, rnd, 1);
            if (score > bestScore) {
                bestScore = score;
                best = m;
            }
        }
        return best;
    }

    /** Score for the side to move in {@code g}, searching {@code depth} more plies. */
    static int negamax(Game g, int depth, int alpha, int beta,
                       int noise, Random rnd, int ply) {
        List<Move> moves = ordered(g);
        if (moves.isEmpty()) return g.inCheck() ? -(MATE - ply) : 0; // mate or stalemate
        if (depth == 0) return evaluate(g, noise, rnd);
        for (Move m : moves) {
            Game child = g.copy();
            child.applyUnchecked(m);
            int score = -negamax(child, depth - 1, -beta, -alpha, noise, rnd, ply + 1);
            if (score > alpha) alpha = score;
            if (alpha >= beta) break; // the opponent avoids this line anyway
        }
        return alpha;
    }

    /** Legal moves with captures first (biggest victim first), to speed up pruning. */
    private static List<Move> ordered(Game g) {
        List<Move> moves = g.legalMoves();
        moves.sort(Comparator.comparingInt(m -> {
            Piece victim = g.pieceAt(m.to());
            return victim == null ? 0 : -value(victim.type());
        }));
        return moves;
    }

    /** Material and a little placement, from the perspective of the side to move. */
    private static int evaluate(Game g, int noise, Random rnd) {
        int score = 0;
        for (int sq = 0; sq < 64; sq++) {
            Piece p = g.pieceAt(sq);
            if (p == null) continue;
            int s = value(p.type()) + placement(p, sq);
            score += p.color() == g.turn() ? s : -s;
        }
        return score + (noise > 0 ? rnd.nextInt(2 * noise + 1) - noise : 0);
    }

    /** Small bonus for advanced pawns and for minor pieces near the middle. */
    private static int placement(Piece p, int sq) {
        int rank = sq >> 3, file = sq & 7;
        return switch (p.type()) {
            case PAWN -> 4 * (p.color() == Color.WHITE ? rank - 1 : 6 - rank);
            case KNIGHT, BISHOP -> 4 * (Math.min(file, 7 - file) + Math.min(rank, 7 - rank));
            default -> 0;
        };
    }

    static int value(PieceType type) {
        return switch (type) {
            case PAWN -> 100;
            case KNIGHT -> 320;
            case BISHOP -> 330;
            case ROOK -> 500;
            case QUEEN -> 900;
            case KING -> 0;
        };
    }
}
