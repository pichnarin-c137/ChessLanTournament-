package chess.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameTest {

    private static Game play(String... moves) {
        Game g = new Game();
        for (String m : moves) g.makeMove(Move.fromUci(m));
        return g;
    }

    /** Builds a 64-char board (index 0 = a1) from "square=piece" entries, e.g. "e1=K". */
    private static String board(String... placements) {
        StringBuilder sb = new StringBuilder(".".repeat(64));
        for (String p : placements) {
            String[] kv = p.split("=");
            sb.setCharAt(Move.fromUci(kv[0] + kv[0]).from(), kv[1].charAt(0));
        }
        return sb.toString();
    }

    // castling

    @Test
    void castlingKingsideBothColors() {
        Game g = play("g1f3", "g8f6", "g2g3", "g7g6", "f1g2", "f8g7", "e1g1", "e8g8");
        assertEquals(new Piece(Color.WHITE, PieceType.KING), g.pieceAt(6));   // g1
        assertEquals(new Piece(Color.WHITE, PieceType.ROOK), g.pieceAt(5));   // f1
        assertEquals(new Piece(Color.BLACK, PieceType.KING), g.pieceAt(62));  // g8
        assertEquals(new Piece(Color.BLACK, PieceType.ROOK), g.pieceAt(61));  // f8
    }

    @Test
    void castlingQueenside() {
        Game g = play("d2d4", "d7d5", "c1f4", "c8f5", "b1c3", "b8c6", "d1d2", "d8d7",
                "e1c1", "e8c8");
        assertEquals(new Piece(Color.WHITE, PieceType.KING), g.pieceAt(2));   // c1
        assertEquals(new Piece(Color.WHITE, PieceType.ROOK), g.pieceAt(3));   // d1
        assertEquals(new Piece(Color.BLACK, PieceType.KING), g.pieceAt(58));  // c8
        assertEquals(new Piece(Color.BLACK, PieceType.ROOK), g.pieceAt(59));  // d8
    }

    @Test
    void cannotCastleThroughAttackedSquare() {
        // Black rook on f8 covers f1, the square the king passes through.
        Game g = Game.fromPosition(board("e1=K", "h1=R", "e8=k", "f8=r"), Color.WHITE, "K", -1);
        assertThrows(IllegalArgumentException.class, () -> g.makeMove(Move.fromUci("e1g1")));
    }

    @Test
    void cannotCastleOutOfCheck() {
        Game g = Game.fromPosition(board("e1=K", "h1=R", "a8=k", "e5=r"), Color.WHITE, "K", -1);
        assertTrue(g.inCheck());
        assertThrows(IllegalArgumentException.class, () -> g.makeMove(Move.fromUci("e1g1")));
    }

    @Test
    void cannotCastleIntoCheck() {
        // Black rook on g8 covers g1, the king's destination.
        Game g = Game.fromPosition(board("e1=K", "h1=R", "a8=k", "g8=r"), Color.WHITE, "K", -1);
        assertThrows(IllegalArgumentException.class, () -> g.makeMove(Move.fromUci("e1g1")));
    }

    @Test
    void castlingRightLostAfterKingMoves() {
        Game g = play("e2e4", "e7e5", "g1f3", "g8f6", "f1c4", "f8c5",
                "e1e2", "e8e7", "e2e1", "e7e8");
        assertThrows(IllegalArgumentException.class, () -> g.makeMove(Move.fromUci("e1g1")));
    }

    @Test
    void castlingRightLostWhenRookCaptured() {
        // Black bishop takes h1; even with a rook back on h1 later, the right is gone.
        Game g = Game.fromPosition(board("e1=K", "h1=R", "e8=k", "b7=b"), Color.BLACK, "K", -1);
        g.makeMove(Move.fromUci("b7h1"));
        assertFalse(g.legalMovesUci().contains("e1g1"));
    }

    @Test
    void castlingBlockedByPiece() {
        Game g = new Game();
        assertFalse(g.legalMovesUci().contains("e1g1")); // f1/g1 still occupied
    }

    //  en passant

    @Test
    void enPassantCapture() {
        Game g = play("e2e4", "a7a6", "e4e5", "d7d5", "e5d6");
        assertEquals(new Piece(Color.WHITE, PieceType.PAWN), g.pieceAt(43)); // d6
        assertNull(g.pieceAt(35)); // d5 pawn removed
    }

    @Test
    void enPassantExpiresAfterOneMove() {
        Game g = play("e2e4", "a7a6", "e4e5", "d7d5", "h2h3", "a6a5");
        assertThrows(IllegalArgumentException.class, () -> g.makeMove(Move.fromUci("e5d6")));
    }

    //  promotion

    @Test
    void promotionToChosenPiece() {
        Game g = Game.fromPosition(board("a7=P", "e1=K", "e8=k"), Color.WHITE, "-", -1);
        g.makeMove(Move.fromUci("a7a8q"));
        assertEquals(new Piece(Color.WHITE, PieceType.QUEEN), g.pieceAt(56));
    }

    @Test
    void underpromotionToKnight() {
        Game g = Game.fromPosition(board("a7=P", "e1=K", "e8=k"), Color.WHITE, "-", -1);
        g.makeMove(Move.fromUci("a7a8n"));
        assertEquals(new Piece(Color.WHITE, PieceType.KNIGHT), g.pieceAt(56));
    }

    @Test
    void promotionWithoutPieceChoiceIsIllegal() {
        Game g = Game.fromPosition(board("a7=P", "e1=K", "e8=k"), Color.WHITE, "-", -1);
        assertThrows(IllegalArgumentException.class, () -> g.makeMove(Move.fromUci("a7a8")));
        assertThrows(IllegalArgumentException.class, () -> g.makeMove(Move.fromUci("a7a8k")));
    }

    //  check, mate, stalemate 

    @Test
    void foolsMate() {
        Game g = play("f2f3", "e7e5", "g2g4", "d8h4");
        assertEquals(GameStatus.CHECKMATE, g.status());
        assertEquals(Color.BLACK, g.winner());
        assertThrows(IllegalStateException.class, () -> g.makeMove(Move.fromUci("a2a3")));
    }

    @Test
    void stalemate() {
        Game g = Game.fromPosition(board("a8=k", "b6=K", "c7=Q"), Color.BLACK, "-", -1);
        assertEquals(GameStatus.STALEMATE, g.status());
    }

    @Test
    void pinnedPieceCannotMove() {
        Game g = Game.fromPosition(board("e1=K", "e2=N", "e8=r", "a8=k"), Color.WHITE, "-", -1);
        assertTrue(g.legalMoves().stream().noneMatch(m -> m.from() == 12)); // e2 knight
    }

    @Test
    void mustResolveCheck() {
        Game g = Game.fromPosition(board("e1=K", "a1=R", "e8=r", "h8=k"), Color.WHITE, "-", -1);
        assertTrue(g.inCheck());
        assertThrows(IllegalArgumentException.class, () -> g.makeMove(Move.fromUci("a1a2")));
        g.makeMove(Move.fromUci("e1d1")); // stepping off the e-file is fine
    }

    @Test
    void turnOrderEnforced() {
        Game g = play("e2e4");
        assertThrows(IllegalArgumentException.class, () -> g.makeMove(Move.fromUci("d2d4")));
    }

    //  draws

    @Test
    void insufficientMaterialKingVsKingAndKnight() {
        Game g = Game.fromPosition(board("e1=K", "e8=k", "b8=n"), Color.WHITE, "-", -1);
        assertEquals(GameStatus.DRAW_INSUFFICIENT_MATERIAL, g.status());
    }

    @Test
    void rookIsSufficientMaterial() {
        Game g = Game.fromPosition(board("e1=K", "e8=k", "a8=r"), Color.WHITE, "-", -1);
        assertEquals(GameStatus.ONGOING, g.status());
    }

    @Test
    void threefoldRepetition() {
        Game g = play("g1f3", "g8f6", "f3g1", "f6g8",   // start position x2
                "g1f3", "g8f6", "f3g1", "f6g8");        // x3
        assertEquals(GameStatus.DRAW_THREEFOLD, g.status());
    }

    @Test
    void fiftyMoveRule() {
        Game g = Game.fromPosition(board("e1=K", "a1=R", "e8=k", "h8=r"), Color.WHITE, "-", -1);
        g.setHalfmoveClock(99);
        g.makeMove(Move.fromUci("a1a2")); // quiet rook move: clock reaches 100
        assertEquals(GameStatus.DRAW_FIFTY_MOVES, g.status());
    }

    //  move history (SAN)

    @Test
    void sanRecordsPawnPieceAndCastlingMoves() {
        Game g = play("g1f3", "g8f6", "g2g3", "g7g6", "f1g2", "f8g7", "e1g1", "e8g8");
        assertEquals(List.of("Nf3", "Nf6", "g3", "g6", "Bg2", "Bg7", "O-O", "O-O"),
                g.moveHistory());
    }

    @Test
    void sanFoolsMateEndsWithMateSign() {
        Game g = play("f2f3", "e7e5", "g2g4", "d8h4");
        assertEquals(List.of("f3", "e5", "g4", "Qh4#"), g.moveHistory());
    }

    @Test
    void sanEnPassantIsAFileCapture() {
        Game g = play("e2e4", "a7a6", "e4e5", "d7d5", "e5d6");
        assertEquals(List.of("e4", "a6", "e5", "d5", "exd6"), g.moveHistory());
    }

    @Test
    void sanPromotionWithCheck() {
        Game g = Game.fromPosition(board("a7=P", "e1=K", "e8=k"), Color.WHITE, "-", -1);
        g.makeMove(Move.fromUci("a7a8q")); // the new queen checks along the 8th rank
        assertEquals(List.of("a8=Q+"), g.moveHistory());
    }

    @Test
    void sanCaptureWithCheck() {
        Game g = Game.fromPosition(board("e1=K", "d1=Q", "d8=q", "h8=k"), Color.WHITE, "-", -1);
        g.makeMove(Move.fromUci("d1d8"));
        assertEquals(List.of("Qxd8+"), g.moveHistory());
    }

    @Test
    void sanDisambiguatesByFile() {
        // Both rooks can reach e1, so the a-rook's move must say which one it is.
        Game g = Game.fromPosition(board("a2=K", "a1=R", "h1=R", "b8=k"), Color.WHITE, "-", -1);
        g.makeMove(Move.fromUci("a1e1"));
        assertEquals(List.of("Rae1"), g.moveHistory());
    }
}
