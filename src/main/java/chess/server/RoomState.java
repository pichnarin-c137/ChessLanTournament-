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
        List<String> history,
        List<String> annotations, // move grades parallel to history; null = still analysing
        String timeControl, // label like "10|5", or null when the game is untimed
        long whiteMillis,
        long blackMillis,
        Color clockRunning, // whose clock is ticking, null when stopped
        boolean whiteConnected,
        boolean blackConnected,
        boolean whiteEverConnected,
        boolean blackEverConnected,
        String whiteBot, // bot level ("easy"/"medium"/"hard"), or null for a human seat
        String blackBot,
        int whiteWins,
        int blackWins,
        int draws,
        Set<Color> rematchVotes) {
}
