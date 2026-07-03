package chess.engine;

public record Piece(Color color, PieceType type) {

    /** FEN letter: uppercase for white, lowercase for black. */
    public char fen() {
        return color == Color.WHITE ? type.letter() : Character.toLowerCase(type.letter());
    }
}
