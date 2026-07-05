package chess.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AnalysisTest {

    /** Builds a 64-char board (index 0 = a1) from "square=piece" entries, e.g. "e1=K". */
    private static String board(String... placements) {
        StringBuilder sb = new StringBuilder(".".repeat(64));
        for (String p : placements) {
            String[] kv = p.split("=");
            sb.setCharAt(Move.fromUci(kv[0] + kv[0]).from(), kv[1].charAt(0));
        }
        return sb.toString();
    }

    private static Analysis.Judgment judge(Game g, String uci) {
        return Analysis.analyse(g, Move.fromUci(uci)).judgment();
    }

    @Test
    void hangingQueenIsBlunder() {
        // Qd5 hangs to the d8 queen; Qxd8+ was on the board.
        Game g = Game.fromPosition(board("d1=Q", "e1=K", "d8=q", "e8=k"), Color.WHITE, "-", -1);
        assertEquals(Analysis.Judgment.BLUNDER, judge(g, "d1d5"));
    }

    @Test
    void deliveringMateIsBest() {
        // Back-rank mate: forced mate outranks the "only good move" GREAT label.
        Game g = Game.fromPosition(board("a1=R", "g1=K", "g8=k", "f7=p", "g7=p", "h7=p"),
                Color.WHITE, "-", -1);
        assertEquals(Analysis.Judgment.BEST, judge(g, "a1a8"));
    }

    @Test
    void walkingIntoMateInOneIsBlunder() {
        Game g = new Game();
        g.makeMove(Move.fromUci("f2f3"));
        g.makeMove(Move.fromUci("e7e5"));
        assertEquals(Analysis.Judgment.BLUNDER, judge(g, "g2g4")); // allows Qh4#
    }

    @Test
    void onlyMoveIsGreat() {
        // Grabbing the hanging queen is the one move that doesn't stay a queen down.
        Game g = Game.fromPosition(board("d1=Q", "e1=K", "d5=q", "e8=k"), Color.WHITE, "-", -1);
        assertEquals(Analysis.Judgment.GREAT, judge(g, "d1d5"));
    }

    @Test
    void queenSacMateIsBrilliant() {
        // Smothered-mate net: Qg8+!! Rxg8 (forced) Nf7#.
        Game g = Game.fromPosition(board("g1=K", "e6=Q", "h6=N", "h8=k", "f8=r", "g7=p", "h7=p"),
                Color.WHITE, "-", -1);
        assertEquals(Analysis.Judgment.BRILLIANT, judge(g, "e6g8"));
    }

    @Test
    void openingMoveIsNotPunished() {
        Analysis.Judgment j = judge(new Game(), "e2e4");
        assertFalse(List.of(Analysis.Judgment.INACCURACY, Analysis.Judgment.MISTAKE,
                Analysis.Judgment.BLUNDER).contains(j), "e4 graded " + j);
    }

    @Test
    void analyseIsDeterministic() {
        Game g = new Game();
        g.makeMove(Move.fromUci("e2e4"));
        assertEquals(Analysis.analyse(g, Move.fromUci("e7e5")),
                Analysis.analyse(g, Move.fromUci("e7e5")));
    }
}
