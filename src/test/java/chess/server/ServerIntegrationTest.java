package chess.server;

import chess.engine.Bot;
import chess.engine.Color;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** End-to-end test of the room protocol over real HTTP/WebSocket connections. */
class ServerIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static WebServer server;
    private static int port;
    private static GameRoom room;

    @BeforeAll
    static void startServer() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        server = new WebServer();
        server.start(port);
        room = server.newRoom(state -> { });
    }

    @AfterAll
    static void stopServer() {
        server.stop();
    }

    /** Collects incoming text frames into a queue. */
    private static final class Client implements WebSocket.Listener {
        final BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
        final StringBuilder partial = new StringBuilder();
        WebSocket ws;

        static Client join(String color) throws Exception {
            Client c = new Client();
            c.ws = HttpClient.newHttpClient().newWebSocketBuilder()
                    .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws/" + room.id() + "/" + color), c)
                    .get(5, TimeUnit.SECONDS);
            return c;
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                try {
                    messages.add(JSON.readTree(partial.toString()));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                partial.setLength(0);
            }
            ws.request(1);
            return null;
        }

        void send(String json) {
            ws.sendText(json, true).join();
        }

        JsonNode await(Predicate<JsonNode> matches) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                JsonNode m = messages.poll(200, TimeUnit.MILLISECONDS);
                if (m != null && matches.test(m)) return m;
            }
            return fail("expected message did not arrive within 5s");
        }

        JsonNode awaitState(Predicate<JsonNode> matches) throws InterruptedException {
            return await(m -> "state".equals(m.path("type").asText()) && matches.test(m));
        }
    }

    @Test
    void fullSessionOverTheWire() throws Exception {
        // Join page is served for a valid link, 404 for a stale gameId.
        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<String> ok = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/join/" + room.id() + "/white")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, ok.statusCode());
        assertTrue(ok.body().contains("<title>Chess</title>"));
        HttpResponse<String> stale = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/join/nope/white")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, stale.statusCode());

        // Both players join -> game starts.
        Client white = Client.join("white");
        white.awaitState(m -> "waiting".equals(m.path("phase").asText()));
        Client black = Client.join("black");
        JsonNode started = black.awaitState(m -> "playing".equals(m.path("phase").asText()));
        assertEquals("black", started.path("yourColor").asText());
        white.awaitState(m -> "playing".equals(m.path("phase").asText()));

        // A second device on a taken color is rejected.
        Client intruder = Client.join("white");
        JsonNode rejected = intruder.await(m -> "rejected".equals(m.path("type").asText()));
        assertTrue(rejected.path("message").asText().contains("already taken"));

        // Illegal move and out-of-turn move are refused.
        white.send("{\"type\":\"move\",\"move\":\"e2e5\"}");
        white.await(m -> "error".equals(m.path("type").asText()));
        black.send("{\"type\":\"move\",\"move\":\"e7e5\"}");
        JsonNode notYourTurn = black.await(m -> "error".equals(m.path("type").asText()));
        assertEquals("Not your turn.", notYourTurn.path("message").asText());

        // Fool's mate over the wire.
        white.send("{\"type\":\"move\",\"move\":\"f2f3\"}");
        black.awaitState(m -> "black".equals(m.path("turn").asText()));
        black.send("{\"type\":\"move\",\"move\":\"e7e5\"}");
        white.awaitState(m -> "white".equals(m.path("turn").asText()));
        white.send("{\"type\":\"move\",\"move\":\"g2g4\"}");
        black.awaitState(m -> "black".equals(m.path("turn").asText()));
        black.send("{\"type\":\"move\",\"move\":\"d8h4\"}");
        JsonNode over = white.awaitState(m -> "over".equals(m.path("phase").asText()));
        assertEquals("black", over.path("winner").asText());
        assertTrue(over.path("result").asText().contains("Checkmate"));
        assertEquals(1, over.path("score").path("black").asInt());
        assertEquals(0, over.path("score").path("white").asInt());
        assertEquals("[\"f3\",\"e5\",\"g4\",\"Qh4#\"]", over.path("history").toString());
        assertTrue(over.path("clock").isNull()); // this room is untimed

        // Rematch needs both votes; the board resets and the score stays.
        white.send("{\"type\":\"rematch\"}");
        JsonNode oneVote = black.awaitState(m -> m.path("rematchVotes").size() == 1);
        assertEquals("over", oneVote.path("phase").asText());
        black.send("{\"type\":\"rematch\"}");
        JsonNode fresh = white.awaitState(m -> "playing".equals(m.path("phase").asText()));
        assertEquals("white", fresh.path("turn").asText());
        assertEquals(1, fresh.path("score").path("black").asInt());
        assertTrue(fresh.path("board").asText().startsWith("RNBQKBNR"));

        // Resignation ends the new game and scores for the opponent.
        white.send("{\"type\":\"resign\"}");
        JsonNode resigned = black.awaitState(m -> "over".equals(m.path("phase").asText()));
        assertTrue(resigned.path("result").asText().contains("resigned"));
        assertEquals(2, resigned.path("score").path("black").asInt()); // mate + resignation

        // A dropped seat can be re-claimed through the same link.
        black.ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
        white.awaitState(m -> !m.path("connected").path("black").asBoolean());
        Client back = Client.join("black");
        JsonNode rejoined = back.awaitState(m -> m.path("connected").path("black").asBoolean());
        assertNotNull(rejoined);

        // A timed room: White never moves, the flag falls and Black gets the win.
        room = server.newRoom(state -> { });
        room.setTimeControl(new TimeControl(400, 0));
        Client tWhite = Client.join("white");
        Client tBlack = Client.join("black");
        JsonNode timed = tWhite.awaitState(m -> "playing".equals(m.path("phase").asText()));
        assertEquals("white", timed.path("clock").path("running").asText());
        JsonNode flagged = tBlack.awaitState(m -> "over".equals(m.path("phase").asText()));
        assertTrue(flagged.path("result").asText().contains("ran out of time"));
        assertEquals("black", flagged.path("winner").asText());
        assertEquals(0, flagged.path("clock").path("white").asLong());

        // A bot room: the computer seats Black, answers moves and accepts rematches.
        room = server.newRoom(state -> { });
        room.setBot(Color.BLACK, Bot.Level.EASY);
        Client solo = Client.join("white");
        JsonNode botGame = solo.awaitState(m -> "playing".equals(m.path("phase").asText()));
        assertEquals("easy", botGame.path("bots").path("black").asText());
        assertTrue(botGame.path("connected").path("black").asBoolean());
        solo.send("{\"type\":\"move\",\"move\":\"e2e4\"}");
        JsonNode replied = solo.awaitState(m -> m.path("history").size() == 2);
        assertEquals("white", replied.path("turn").asText());
        solo.send("{\"type\":\"resign\"}");
        solo.awaitState(m -> "over".equals(m.path("phase").asText()));
        solo.send("{\"type\":\"rematch\"}"); // the bot votes along, one click restarts
        JsonNode again = solo.awaitState(m -> "playing".equals(m.path("phase").asText())
                && m.path("history").size() == 0);
        assertEquals(1, again.path("score").path("black").asInt());
    }
}
