package chess.engine;

import java.util.List;
import java.util.Random;

/**
 * Post-move analysis: replays the {@link Bot}'s search without noise to score
 * every legal move in the position a move was played from, then grades the
 * played move by how much it gave away against the engine's best.
 */
public final class Analysis {

    /** chess.com-style grades, from a sparkling sacrifice down to a losing lapse. */
    public enum Judgment { BRILLIANT, GREAT, BEST, GOOD, INACCURACY, MISTAKE, BLUNDER }

    /** Scores in centipawns from the mover's perspective; mate encoded as {@code Bot.MATE - ply}. */
    public record MoveAnalysis(Judgment judgment, int playedScore, int bestScore) {
    }

    /** Plies searched per root move — reply and recapture, and mate-in-1 either way. */
    static final int DEPTH = 3;

    /** Scores beyond this are forced mates; never bucket a loss across this boundary. */
    private static final int MATE_THRESHOLD = Bot.MATE - 100;

    /** A sacrifice must offer at least this much material (exchange sac and up). */
    private static final int SACRIFICE_CP = 150;

    private static final Random NO_NOISE = new Random(0); // never consulted: noise == 0

    private Analysis() {
    }

    /** Grades {@code played}, a legal move in {@code before} (which is left untouched). */
    public static MoveAnalysis analyse(Game before, Move played) {
        List<Move> moves = before.legalMoves();
        int best = Integer.MIN_VALUE, second = Integer.MIN_VALUE, playedScore = Integer.MIN_VALUE;
        for (Move m : moves) {
            Game child = before.copy();
            child.applyUnchecked(m);
            // Full window per root move: cp-loss needs every score exact, not a fail-hard bound.
            int score = -Bot.negamax(child, DEPTH - 1, -Bot.MATE, Bot.MATE, 0, NO_NOISE, 1);
            if (score > best) {
                second = best;
                best = score;
            } else if (score > second) {
                second = score;
            }
            if (m.equals(played)) playedScore = score;
        }
        if (playedScore == Integer.MIN_VALUE) {
            throw new IllegalArgumentException("not a legal move here: " + played.uci());
        }
        return new MoveAnalysis(classify(before, played, moves.size(), best, second, playedScore),
                playedScore, best);
    }

    private static Judgment classify(Game before, Move played, int moveCount,
                                     int best, int second, int playedScore) {
        int loss = best - playedScore;
        if (loss == 0) {
            if (playedScore >= -90 && sacrifices(before, played)) return Judgment.BRILLIANT;
            if (playedScore >= MATE_THRESHOLD) return Judgment.BEST; // delivering or keeping mate
            if (moveCount > 1 && best - second >= 90 && best >= -200) {
                return Judgment.GREAT; // the only move that works
            }
            return Judgment.BEST;
        }
        if (best >= MATE_THRESHOLD) { // there was a forced mate on the board
            if (playedScore >= MATE_THRESHOLD) return Judgment.GOOD; // just a slower mate
            return playedScore >= 900 ? Judgment.MISTAKE : Judgment.BLUNDER;
        }
        if (playedScore <= -MATE_THRESHOLD) return Judgment.BLUNDER; // walked into forced mate
        if (loss <= 40) return Judgment.GOOD;
        if (loss <= 90) return Judgment.INACCURACY;
        if (loss <= 200) return Judgment.MISTAKE;
        return Judgment.BLUNDER;
    }

    /**
     * True when {@code played} offers the opponent material: their greediest reply
     * leaves the mover at least {@link #SACRIFICE_CP} down on where they stood.
     */
    private static boolean sacrifices(Game before, Move played) {
        Color mover = before.turn();
        int stake = material(before, mover);
        Game after = before.copy();
        after.applyUnchecked(played);
        int floor = material(after, mover); // the opponent may also decline
        for (Move reply : after.legalMoves()) {
            Game g = after.copy();
            g.applyUnchecked(reply);
            floor = Math.min(floor, material(g, mover));
        }
        return floor <= stake - SACRIFICE_CP;
    }

    /** Raw material balance for {@code side}, in centipawns. */
    private static int material(Game g, Color side) {
        int sum = 0;
        for (int sq = 0; sq < 64; sq++) {
            Piece p = g.pieceAt(sq);
            if (p == null) continue;
            sum += p.color() == side ? Bot.value(p.type()) : -Bot.value(p.type());
        }
        return sum;
    }
}
