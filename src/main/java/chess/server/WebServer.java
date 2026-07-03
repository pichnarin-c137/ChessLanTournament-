package chess.server;

import chess.engine.Color;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.websocket.WsContext;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.function.Consumer;

/**
 * Embedded HTTP + WebSocket server. Serves the single-file web client at
 * /join/{gameId}/{color} and relays moves over /ws/{gameId}/{color}.
 * Holds at most one active {@link GameRoom}; "New Game" on the host replaces it.
 */
public final class WebServer {

    private static final String PLAY_HTML = loadResource("/web/play.html");
    private static final SecureRandom RANDOM = new SecureRandom();

    private Javalin app;
    private volatile GameRoom room;

    /** Starts listening; throws (e.g. wrapped BindException) if the port is taken. */
    public void start(int port) {
        app = Javalin.create(cfg -> cfg.showJavalinBanner = false)
                .get("/", ctx -> ctx.html(infoPage(
                        "Chess referee is running. Scan a QR code on the host screen to join.")))
                .get("/join/{gameId}/{color}", this::servePlayer)
                .ws("/ws/{gameId}/{color}", ws -> {
                    ws.onConnect(this::wsConnect);
                    ws.onMessage(ctx -> withSeat(ctx, (r, c) -> r.handleMessage(c, ctx, ctx.message())));
                    ws.onClose(ctx -> withSeat(ctx, (r, c) -> r.disconnect(c, ctx)));
                })
                .start(port);
    }

    public void stop() {
        if (app != null) app.stop();
    }

    /** Replaces the active room; old join links stop working and old sockets are told why. */
    public synchronized GameRoom newRoom(Consumer<RoomState> hostListener) {
        GameRoom old = room;
        if (old != null) old.close("The host started a new game. Ask for the new QR code.");
        GameRoom fresh = new GameRoom(HexFormat.of().toHexDigits(RANDOM.nextLong(), 8), hostListener);
        room = fresh;
        fresh.broadcast(); // let the host render the initial position
        return fresh;
    }

    private void servePlayer(Context ctx) {
        GameRoom r = room;
        if (r == null || !r.id().equals(ctx.pathParam("gameId"))
                || parseColor(ctx.pathParam("color")) == null) {
            ctx.status(404).html(infoPage(
                    "This game is not active. Ask the host to start a new game and scan the fresh QR code."));
            return;
        }
        ctx.html(PLAY_HTML);
    }

    private void wsConnect(WsContext ctx) {
        GameRoom r = room;
        Color color = parseColor(ctx.pathParam("color"));
        ctx.session.setIdleTimeout(Duration.ofSeconds(75)); // client pings every 25 s
        if (r == null || !r.id().equals(ctx.pathParam("gameId")) || color == null) {
            ctx.send(GameRoom.rejectedJson("This game is no longer active. Ask the host for a new QR code."));
            ctx.closeSession();
            return;
        }
        if (!r.tryConnect(color, ctx)) {
            ctx.send(GameRoom.rejectedJson(
                    (color == Color.WHITE ? "White" : "Black") + " is already taken on another device."));
            ctx.closeSession();
        }
    }

    private void withSeat(WsContext ctx, java.util.function.BiConsumer<GameRoom, Color> action) {
        GameRoom r = room;
        Color color = parseColor(ctx.pathParam("color"));
        if (r != null && r.id().equals(ctx.pathParam("gameId")) && color != null) {
            action.accept(r, color);
        }
    }

    private static Color parseColor(String s) {
        return switch (s) {
            case "white" -> Color.WHITE;
            case "black" -> Color.BLACK;
            default -> null;
        };
    }

    private static String infoPage(String message) {
        return "<!doctype html><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<body style=\"font-family:system-ui,sans-serif;background:#1e1c1a;color:#eee;"
                + "display:flex;align-items:center;justify-content:center;min-height:100vh;margin:0\">"
                + "<p style=\"max-width:26em;text-align:center;padding:1em\">" + message + "</p>";
    }

    private static String loadResource(String path) {
        try (InputStream in = WebServer.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
