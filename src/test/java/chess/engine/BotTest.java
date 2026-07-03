package chess.engine;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotTest {

    private static final Random RND = new Random(42);

    /** Builds a 64-char board (index 0 = a1) from "square=piece" entries, e.g. "e1=K". */
    private static String board(String... placements) {
        StringBuilder sb = new StringBuilder(".".repeat(64));
        for (String p : placements) {
            String[] kv = p.split("=");
            sb.setCharAt(Move.fromUci(kv[0] + kv[0]).from(), kv[1].charAt(0));
        }
        return sb.toString();
    }

    @Test
    void findsBackRankMateAtEveryLevel() {
        // The classic: black king boxed in by its own pawns, Ra8 is mate.
        Game g = Game.fromPosition(board("a1=R", "g1=K", "g8=k", "f7=p", "g7=p", "h7=p"),
                Color.WHITE, "-", -1);
        for (Bot.Level level : Bot.Level.values()) {
            assertEquals("a1a8", Bot.choose(g, level, RND).uci(), level.name());
        }
    }

    @Test
    void grabsAHangingQueen() {
        Game g = Game.fromPosition(board("d1=Q", "e1=K", "d5=q", "e8=k"), Color.WHITE, "-", -1);
        assertEquals("d1d5", Bot.choose(g, Bot.Level.MEDIUM, RND).uci());
        assertEquals("d1d5", Bot.choose(g, Bot.Level.HARD, RND).uci());
    }

    @Test
    void declinesAPawnDefendedByAPawn() {
        // Qxd5 exd5 trades the queen for a pawn; one ply deeper and the bot sees it.
        Game g = Game.fromPosition(board("d1=Q", "e1=K", "d5=p", "e6=p", "e8=k"),
                Color.WHITE, "-", -1);
        assertNotEquals("d1d5", Bot.choose(g, Bot.Level.MEDIUM, RND).uci());
        assertNotEquals("d1d5", Bot.choose(g, Bot.Level.HARD, RND).uci());
    }

    @Test
    void selfPlayProducesOnlyLegalMoves() {
        Game g = new Game();
        Random rnd = new Random(7);
        for (int ply = 0; ply < 60 && !g.status().isOver(); ply++) {
            g.makeMove(Bot.choose(g, Bot.Level.EASY, rnd)); // throws on an illegal pick
        }
    }

    @Test
    void hardPicksAMoveQuickly() {
        long start = System.nanoTime();
        assertNotNull(Bot.choose(new Game(), Bot.Level.HARD, RND));
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(millis < 5000, "Hard took " + millis + " ms; a move should be near-instant");
    }
}
