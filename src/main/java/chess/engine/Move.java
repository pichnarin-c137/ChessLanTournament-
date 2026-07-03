package chess.engine;

/**
 * A move between two squares (0..63, a1 = 0, h8 = 63), with an optional
 * promotion piece for pawn moves to the last rank.
 */
public record Move(int from, int to, PieceType promotion) {

    public Move {
        if (from < 0 || from > 63 || to < 0 || to > 63) {
            throw new IllegalArgumentException("square out of range");
        }
    }

    /** Parses UCI notation, e.g. "e2e4" or "e7e8q". */
    public static Move fromUci(String uci) {
        if (uci == null || uci.length() < 4 || uci.length() > 5) {
            throw new IllegalArgumentException("bad move: " + uci);
        }
        int ff = uci.charAt(0) - 'a', fr = uci.charAt(1) - '1';
        int tf = uci.charAt(2) - 'a', tr = uci.charAt(3) - '1';
        if (ff < 0 || ff > 7 || fr < 0 || fr > 7 || tf < 0 || tf > 7 || tr < 0 || tr > 7) {
            throw new IllegalArgumentException("bad move: " + uci);
        }
        PieceType promo = uci.length() == 5
                ? PieceType.fromLetter(Character.toUpperCase(uci.charAt(4)))
                : null;
        return new Move(fr * 8 + ff, tr * 8 + tf, promo);
    }

    public String uci() {
        String s = "" + (char) ('a' + (from & 7)) + (char) ('1' + (from >> 3))
                + (char) ('a' + (to & 7)) + (char) ('1' + (to >> 3));
        return promotion == null ? s : s + Character.toLowerCase(promotion.letter());
    }
}
