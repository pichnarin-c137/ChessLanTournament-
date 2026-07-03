package chess.server;

import chess.engine.Color;

import java.util.List;
import java.util.Set;

/** Immutable snapshot of a {@link GameRoom}, consumed by the host UI and the JSON layer. */
public record RoomState(
        GameRoom.Phase phase,
        String board,
        Color turn,
        boolean check,
        Color winner,
        String result,
        String lastMove,
        List<String> legalMoves,
        boolean whiteConnected,
        boolean blackConnected,
        boolean whiteEverConnected,
        boolean blackEverConnected,
        int whiteWins,
        int blackWins,
        int draws,
        Set<Color> rematchVotes) {
}
