package chess.server;

import chess.engine.Analysis;
import chess.engine.Bot;
import chess.engine.Color;
import chess.engine.Game;
import chess.engine.Move;
import chess.engine.Piece;
import chess.engine.PieceType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.websocket.WsContext;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
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

    /** Fires flag-fall checks for all rooms; the task re-verifies under the room lock. */
    private static final ScheduledExecutorService CLOCK =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "chess-clock");
                t.setDaemon(true);
                return t;
            });

    /** Runs bot searches for all rooms, off the clock thread so flag checks never wait. */
    private static final ScheduledExecutorService BOT =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "chess-bot");
                t.setDaemon(true);
                return t;
            });

    /** Grades played moves for all rooms; never delays bot searches or flag checks. */
    private static final ExecutorService ANALYSIS =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "chess-analysis");
                t.setDaemon(true);
                return t;
            });

    private final String id;
    private final Consumer<RoomState> hostListener;

    private Game game = new Game();
    private Phase phase = Phase.WAITING;
    private final Map<Color, WsContext> seats = new EnumMap<>(Color.class);
    private final Map<Color, Bot.Level> bots = new EnumMap<>(Color.class);
    private final Random rnd = new Random();
    private final Set<Color> everConnected = EnumSet.noneOf(Color.class);
    private final Set<Color> rematchVotes = EnumSet.noneOf(Color.class);
    private int whiteWins, blackWins, draws;
    private Color winner;
    private String result;
    private final List<String> annotations = new ArrayList<>(); // parallel to history; null = pending
    private int gameGeneration; // bumped when `game` is replaced, so stale analyses are dropped

    private TimeControl nextTimeControl = TimeControl.NONE; // referee's pick, applied at game start
    private TimeControl timeControl = TimeControl.NONE;     // active for the current game
    private long whiteMillis, blackMillis;
    private long turnStartedNanos;
    private ScheduledFuture<?> flagTask;

    public GameRoom(String id, Consumer<RoomState> hostListener) {
        this.id = id;
        this.hostListener = hostListener;
    }

    public String id() {
        return id;
    }

    /** The referee's time control; applies to games that have not started yet. */
    public synchronized void setTimeControl(TimeControl tc) {
        nextTimeControl = tc;
        if (phase == Phase.WAITING) resetClocks(); // not started: show the new budget right away
        broadcast();
    }

    /** Seats the built-in bot; the referee calls this right after creating the room. */
    public synchronized void setBot(Color color, Bot.Level level) {
        bots.put(color, level);
        everConnected.add(color);
        maybeStart(); // both seats can be bots: the game starts with no one joining
        broadcast();
        maybeScheduleBot();
    }

    /** Claims the seat if it has no live connection; broadcasts on success. */
    public synchronized boolean tryConnect(Color color, WsContext ctx) {
        if (seats.get(color) != null || bots.containsKey(color)) return false;
        seats.put(color, ctx);
        everConnected.add(color);
        maybeStart();
        broadcast();
        maybeScheduleBot();
        return true;
    }

    /** The game begins once every seat is filled, by a connection or a bot. */
    private void maybeStart() {
        if (phase == Phase.WAITING && seats.size() + bots.size() == 2 && result == null) {
            phase = Phase.PLAYING;
            resetClocks();
            startTurnTimer();
        }
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
        Game before = game.copy();
        Move move;
        try {
            move = Move.fromUci(uci);
            game.makeMove(move);
        } catch (Exception e) {
            sendError(ctx, "Illegal move.");
            return;
        }
        scheduleAnalysis(before, move);
        afterMove(color);
    }

    /** Post-move bookkeeping shared by human and bot moves. */
    private void afterMove(Color mover) {
        if (timeControl.timed()) {
            long elapsed = (System.nanoTime() - turnStartedNanos) / 1_000_000;
            if (mover == Color.WHITE) {
                whiteMillis = Math.max(0, whiteMillis - elapsed) + timeControl.incMillis();
            } else {
                blackMillis = Math.max(0, blackMillis - elapsed) + timeControl.incMillis();
            }
        }
        if (game.status().isOver()) {
            cancelFlagCheck();
            finishFromEngine();
        } else {
            startTurnTimer(); // the opponent's clock starts now
        }
        broadcast();
        maybeScheduleBot();
    }

    private void handleResign(Color color, WsContext ctx) {
        if (phase != Phase.PLAYING) {
            sendError(ctx, "No game in progress.");
            return;
        }
        cancelFlagCheck();
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
        rematchVotes.addAll(bots.keySet()); // the computer is always up for another
        if (rematchVotes.size() == 2) {
            game = new Game();
            annotations.clear();
            gameGeneration++;
            winner = null;
            result = null;
            rematchVotes.clear();
            resetClocks();
            phase = seats.size() + bots.size() == 2 ? Phase.PLAYING : Phase.WAITING;
            if (phase == Phase.PLAYING) startTurnTimer();
        }
        broadcast();
        maybeScheduleBot();
    }

    //  the built-in bot 

    /** If it is a bot's turn, search on the shared bot thread and then move. */
    private void maybeScheduleBot() {
        Bot.Level level = bots.get(game.turn());
        if (phase != Phase.PLAYING || level == null) return;
        Game position = game.copy();
        int ply = game.moveHistory().size();
        int minDelay = Config.botDelayMinMs(); // a beat of "thinking" feels natural;
        int maxDelay = Math.max(minDelay, Config.botDelayMaxMs()); // range is user-tunable
        long delay = minDelay + rnd.nextInt(maxDelay - minDelay + 1);
        BOT.schedule(() -> {
            try {
                applyBotMove(Bot.choose(position, level, rnd), ply);
            } catch (Exception e) {
                e.printStackTrace(); // a scheduled task would otherwise fail silently
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    /** Applies a move computed off-thread, unless the game moved on meanwhile. */
    private synchronized void applyBotMove(Move move, int ply) {
        if (phase != Phase.PLAYING || game.moveHistory().size() != ply
                || !bots.containsKey(game.turn())) {
            return; // resignation, flag fall or a new game while the bot was thinking
        }
        Color mover = game.turn();
        Game before = game.copy();
        game.makeMove(move);
        scheduleAnalysis(before, move);
        afterMove(mover);
    }

    //  move analysis

    /** Grades the move just applied, off-thread; the caller holds the room lock. */
    private void scheduleAnalysis(Game before, Move move) {
        annotations.add(null); // keeps the arrays aligned while the grade is pending
        int index = game.moveHistory().size() - 1;
        int gen = gameGeneration;
        ANALYSIS.execute(() -> {
            try {
                Analysis.MoveAnalysis a = Analysis.analyse(before, move); // slow part, no lock
                applyAnnotation(index, gen, a.judgment().name().toLowerCase());
            } catch (Exception e) {
                e.printStackTrace(); // a queued task would otherwise fail silently
            }
        });
    }

    /** Stores a grade computed off-thread, unless the game was replaced meanwhile. */
    private synchronized void applyAnnotation(int index, int gen, String judgment) {
        if (gen != gameGeneration || index >= annotations.size()) return; // rematch or new game
        annotations.set(index, judgment);
        broadcast(); // deliberately also fires in phase OVER: late grades still show
    }

    //  clocks

    /** Applies the referee's time control and refills both clocks. */
    private void resetClocks() {
        timeControl = nextTimeControl;
        whiteMillis = blackMillis = timeControl.baseMillis();
    }

    /** (Re)starts the clock of the side to move and schedules its flag-fall check. */
    private void startTurnTimer() {
        turnStartedNanos = System.nanoTime();
        cancelFlagCheck();
        if (timeControl.timed()) {
            long remaining = game.turn() == Color.WHITE ? whiteMillis : blackMillis;
            flagTask = CLOCK.schedule(this::flagFall, remaining + 50, TimeUnit.MILLISECONDS);
        }
    }

    private void cancelFlagCheck() {
        if (flagTask != null) flagTask.cancel(false);
        flagTask = null;
    }

    /** How much time a side has left right now (the running clock is deducted live). */
    private long remainingMillis(Color side) {
        long millis = side == Color.WHITE ? whiteMillis : blackMillis;
        if (phase == Phase.PLAYING && timeControl.timed() && game.turn() == side) {
            millis -= (System.nanoTime() - turnStartedNanos) / 1_000_000;
        }
        return Math.max(0, millis);
    }

    private synchronized void flagFall() {
        if (phase != Phase.PLAYING || remainingMillis(game.turn()) > 0) return; // move arrived in time
        Color flagged = game.turn();
        if (flagged == Color.WHITE) whiteMillis = 0;
        else blackMillis = 0;
        cancelFlagCheck();
        phase = Phase.OVER;
        if (bareKing(flagged.opposite())) { // opponent can't mate: draw (simplified FIDE rule)
            result = name(flagged) + " ran out of time — draw ("
                    + name(flagged.opposite()) + " cannot mate)";
            draws++;
        } else {
            winner = flagged.opposite();
            result = name(flagged) + " ran out of time — " + name(winner) + " wins";
            addPoint(winner);
        }
        broadcast();
    }

    private boolean bareKing(Color side) {
        for (int sq = 0; sq < 64; sq++) {
            Piece p = game.pieceAt(sq);
            if (p != null && p.color() == side && p.type() != PieceType.KING) return false;
        }
        return true;
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
        cancelFlagCheck();
        phase = Phase.OVER; // a queued flag-fall must not fire for a discarded room
        gameGeneration++;   // and a queued analysis must not broadcast for it either
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
                game.moveHistory(),
                new ArrayList<>(annotations), // not List.copyOf: pending entries are null
                timeControl.timed() ? timeControl.label() : null,
                remainingMillis(Color.WHITE),
                remainingMillis(Color.BLACK),
                phase == Phase.PLAYING && timeControl.timed() ? game.turn() : null,
                seats.containsKey(Color.WHITE) || bots.containsKey(Color.WHITE),
                seats.containsKey(Color.BLACK) || bots.containsKey(Color.BLACK),
                everConnected.contains(Color.WHITE),
                everConnected.contains(Color.BLACK),
                botLabel(Color.WHITE),
                botLabel(Color.BLACK),
                whiteWins, blackWins, draws,
                EnumSet.copyOf(rematchVotes.isEmpty() ? EnumSet.noneOf(Color.class) : rematchVotes));
    }

    private String botLabel(Color side) {
        Bot.Level level = bots.get(side);
        return level == null ? null : level.name().toLowerCase();
    }

    /** Pushes the current state to both players and the host view. */
    public synchronized void broadcast() {
        RoomState state = snapshot();
        seats.forEach((color, ctx) -> send(ctx, stateJson(state, color)));
        hostListener.accept(state);
    }

    //  JSON

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
        ArrayNode history = n.putArray("history");
        s.history().forEach(history::add);
        ArrayNode annotations = n.putArray("annotations");
        s.annotations().forEach(annotations::add); // add(null) writes a JSON null

        if (s.timeControl() == null) {
            n.putNull("clock");
        } else {
            ObjectNode clock = n.putObject("clock");
            clock.put("control", s.timeControl());
            clock.put("white", s.whiteMillis());
            clock.put("black", s.blackMillis());
            clock.put("running", s.clockRunning() == null ? null : name(s.clockRunning()).toLowerCase());
        }
        ObjectNode connected = n.putObject("connected");
        connected.put("white", s.whiteConnected());
        connected.put("black", s.blackConnected());
        ObjectNode bots = n.putObject("bots");
        bots.put("white", s.whiteBot());
        bots.put("black", s.blackBot());
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
