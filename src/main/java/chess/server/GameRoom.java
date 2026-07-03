package chess.server;

import chess.engine.Color;
import chess.engine.Game;
import chess.engine.Move;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.websocket.WsContext;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * One game session between the two color seats. Holds the authoritative
 * {@link Game}, the session scoreboard and the live WebSocket per seat.
 * All state lives in memory; everything is discarded with the room.
 *
 * <p>Seats are bound to their color for the whole session: whoever holds the
 * White join link plays White in every rematch, so reconnecting through the
 * same link/QR always works.
 */
public final class GameRoom {

    public enum Phase { WAITING, PLAYING, OVER }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String id;
    private final Consumer<RoomState> hostListener;

    private Game game = new Game();
    private Phase phase = Phase.WAITING;
    private final Map<Color, WsContext> seats = new EnumMap<>(Color.class);
    private final Set<Color> everConnected = EnumSet.noneOf(Color.class);
    private final Set<Color> rematchVotes = EnumSet.noneOf(Color.class);
    private int whiteWins, blackWins, draws;
    private Color winner;
    private String result;

    public GameRoom(String id, Consumer<RoomState> hostListener) {
        this.id = id;
        this.hostListener = hostListener;
    }

    public String id() {
        return id;
    }

    /** Claims the seat if it has no live connection; broadcasts on success. */
    public synchronized boolean tryConnect(Color color, WsContext ctx) {
        if (seats.get(color) != null) return false;
        seats.put(color, ctx);
        everConnected.add(color);
        if (phase == Phase.WAITING && seats.size() == 2 && result == null) {
            phase = Phase.PLAYING; // game begins once both sides are connected
        }
        broadcast();
        return true;
    }

    public synchronized void disconnect(Color color, WsContext ctx) {
        if (seats.get(color) != null && seats.get(color).session == ctx.session) {
            seats.remove(color);
            broadcast();
        }
    }

    public synchronized void handleMessage(Color color, WsContext ctx, String raw) {
        String type, moveUci;
        try {
            ObjectNode msg = (ObjectNode) JSON.readTree(raw);
            type = msg.path("type").asText();
            moveUci = msg.path("move").asText();
        } catch (Exception e) {
            sendError(ctx, "Bad message.");
            return;
        }
        switch (type) {
            case "ping" -> send(ctx, "{\"type\":\"pong\"}");
            case "move" -> handleMove(color, ctx, moveUci);
            case "resign" -> handleResign(color, ctx);
            case "rematch" -> handleRematch(color, ctx);
            default -> sendError(ctx, "Unknown message type.");
        }
    }

    private void handleMove(Color color, WsContext ctx, String uci) {
        if (phase == Phase.WAITING) {
            sendError(ctx, "Waiting for both players to join.");
            return;
        }
        if (phase == Phase.OVER) {
            sendError(ctx, "The game is over.");
            return;
        }
        if (game.turn() != color) {
            sendError(ctx, "Not your turn.");
            return;
        }
        try {
            game.makeMove(Move.fromUci(uci));
        } catch (Exception e) {
            sendError(ctx, "Illegal move.");
            return;
        }
        if (game.status().isOver()) finishFromEngine();
        broadcast();
    }

    private void handleResign(Color color, WsContext ctx) {
        if (phase != Phase.PLAYING) {
            sendError(ctx, "No game in progress.");
            return;
        }
        phase = Phase.OVER;
        winner = color.opposite();
        result = name(color) + " resigned — " + name(winner) + " wins";
        addPoint(winner);
        broadcast();
    }

    private void handleRematch(Color color, WsContext ctx) {
        if (phase != Phase.OVER) {
            sendError(ctx, "The game is not over.");
            return;
        }
        rematchVotes.add(color);
        if (rematchVotes.size() == 2) {
            game = new Game();
            winner = null;
            result = null;
            rematchVotes.clear();
            phase = seats.size() == 2 ? Phase.PLAYING : Phase.WAITING;
        }
        broadcast();
    }

    private void finishFromEngine() {
        phase = Phase.OVER;
        switch (game.status()) {
            case CHECKMATE -> {
                winner = game.winner();
                result = "Checkmate — " + name(winner) + " wins";
                addPoint(winner);
            }
            case STALEMATE -> {
                result = "Stalemate — draw";
                draws++;
            }
            case DRAW_FIFTY_MOVES -> {
                result = "Draw — 50-move rule";
                draws++;
            }
            case DRAW_THREEFOLD -> {
                result = "Draw — threefold repetition";
                draws++;
            }
            case DRAW_INSUFFICIENT_MATERIAL -> {
                result = "Draw — insufficient material";
                draws++;
            }
            default -> throw new IllegalStateException("game not over");
        }
    }

    private void addPoint(Color side) {
        if (side == Color.WHITE) whiteWins++;
        else blackWins++;
    }

    /** Sends a final message to both players and drops the seats (host started a new game). */
    public synchronized void close(String message) {
        for (WsContext ctx : seats.values()) {
            send(ctx, rejectedJson(message));
            try {
                ctx.closeSession();
            } catch (Exception ignored) {
            }
        }
        seats.clear();
    }

    public synchronized RoomState snapshot() {
        return new RoomState(
                phase,
                game.boardString(),
                game.turn(),
                game.inCheck(),
                winner,
                result,
                game.lastMove() == null ? null : game.lastMove().uci(),
                phase == Phase.PLAYING ? game.legalMovesUci() : List.of(),
                seats.containsKey(Color.WHITE),
                seats.containsKey(Color.BLACK),
                everConnected.contains(Color.WHITE),
                everConnected.contains(Color.BLACK),
                whiteWins, blackWins, draws,
                EnumSet.copyOf(rematchVotes.isEmpty() ? EnumSet.noneOf(Color.class) : rematchVotes));
    }

    /** Pushes the current state to both players and the host view. */
    public synchronized void broadcast() {
        RoomState state = snapshot();
        seats.forEach((color, ctx) -> send(ctx, stateJson(state, color)));
        hostListener.accept(state);
    }

    // -- JSON ------------------------------------------------------------------

    private static String stateJson(RoomState s, Color you) {
        ObjectNode n = JSON.createObjectNode();
        n.put("type", "state");
        n.put("yourColor", name(you).toLowerCase());
        n.put("phase", s.phase().name().toLowerCase());
        n.put("board", s.board());
        n.put("turn", name(s.turn()).toLowerCase());
        n.put("check", s.check());
        n.put("winner", s.winner() == null ? null : name(s.winner()).toLowerCase());
        n.put("result", s.result());
        n.put("lastMove", s.lastMove());
        ArrayNode moves = n.putArray("legalMoves");
        s.legalMoves().forEach(moves::add);
        ObjectNode connected = n.putObject("connected");
        connected.put("white", s.whiteConnected());
        connected.put("black", s.blackConnected());
        ObjectNode score = n.putObject("score");
        score.put("white", s.whiteWins());
        score.put("black", s.blackWins());
        score.put("draws", s.draws());
        ArrayNode votes = n.putArray("rematchVotes");
        s.rematchVotes().forEach(c -> votes.add(name(c).toLowerCase()));
        return n.toString();
    }

    static String rejectedJson(String message) {
        ObjectNode n = JSON.createObjectNode();
        n.put("type", "rejected");
        n.put("message", message);
        return n.toString();
    }

    private static void sendError(WsContext ctx, String message) {
        ObjectNode n = JSON.createObjectNode();
        n.put("type", "error");
        n.put("message", message);
        send(ctx, n.toString());
    }

    private static void send(WsContext ctx, String text) {
        try {
            ctx.send(text);
        } catch (Exception ignored) { // connection died; the close handler frees the seat
        }
    }

    private static String name(Color c) {
        return c == Color.WHITE ? "White" : "Black";
    }
}
