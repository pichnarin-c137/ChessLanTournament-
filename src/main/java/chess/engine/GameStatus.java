package chess.engine;

public enum GameStatus {
    ONGOING,
    CHECKMATE,
    STALEMATE,
    DRAW_FIFTY_MOVES,
    DRAW_THREEFOLD,
    DRAW_INSUFFICIENT_MATERIAL;

    public boolean isOver() {
        return this != ONGOING;
    }
}
