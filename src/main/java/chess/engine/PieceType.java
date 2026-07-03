package chess.engine;

public enum PieceType {
    PAWN('P'), KNIGHT('N'), BISHOP('B'), ROOK('R'), QUEEN('Q'), KING('K');

    private final char letter;

    PieceType(char letter) {
        this.letter = letter;
    }

    public char letter() {
        return letter;
    }

    public static PieceType fromLetter(char c) {
        for (PieceType t : values()) {
            if (t.letter == c) return t;
        }
        throw new IllegalArgumentException("unknown piece letter: " + c);
    }
}
