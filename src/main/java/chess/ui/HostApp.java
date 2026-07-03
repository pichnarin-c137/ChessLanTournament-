package chess.ui;

import chess.engine.Color;
import chess.server.GameRoom;
import chess.server.LanIp;
import chess.server.RoomState;
import chess.server.TimeControl;
import chess.server.WebServer;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;
import javafx.util.StringConverter;

import java.net.BindException;
import java.util.ArrayList;
import java.util.List;

/**
 * The referee window: read-only live board, the two QR/link panels with
 * connection indicators, the session scoreboard and a game-over banner.
 * The embedded server runs off the FX thread; room snapshots arrive via
 * Platform.runLater.
 */
public class HostApp extends Application {

    private WebServer server;
    private int port;

    private final BoardCanvas board = new BoardCanvas();
    private final Label banner = new Label("Press “New Game” to host a game.");
    private final Label serverLabel = new Label("server not started");
    private final Label scoreLabel = new Label("White 0 · Draws 0 · Black 0");
    private final Label rematchLabel = new Label("");
    private final Button newGameButton = new Button("New Game");
    private final SeatPanel whitePanel = new SeatPanel("WHITE");
    private final SeatPanel blackPanel = new SeatPanel("BLACK");
    private final ComboBox<TimeControl> timeChoice = new ComboBox<>();
    private final Label whiteClock = new Label();
    private final Label blackClock = new Label();
    private final HBox clockRow = new HBox(24, whiteClock, blackClock);
    private final ListView<String> movesView = new ListView<>();
    private volatile GameRoom room;
    private RoomState lastState;
    private long stateReceivedNanos;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        port = Integer.parseInt(getParameters().getNamed().getOrDefault("port", "8080"));

        banner.setStyle("-fx-font-size: 17px; -fx-font-weight: bold;");
        scoreLabel.setStyle("-fx-font-size: 15px;");
        newGameButton.setOnAction(e -> newGame());

        // The referee picks the time control; it applies to games not yet started.
        timeChoice.getItems().addAll(TimeControl.PRESETS);
        timeChoice.setValue(TimeControl.minutes(10, 0));
        timeChoice.setConverter(new StringConverter<>() {
            @Override public String toString(TimeControl tc) { return tc == null ? "" : tc.label(); }
            @Override public TimeControl fromString(String s) { return null; }
        });
        timeChoice.setOnAction(e -> {
            GameRoom r = room;
            if (r != null) r.setTimeControl(timeChoice.getValue());
        });

        Label title = new Label("♞ Chess Referee");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(12, title, spacer, new Label("Time:"), timeChoice,
                serverLabel, newGameButton);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(0, 0, 12, 0));

        // The board fills whatever space the window gives it, staying square.
        StackPane boardHolder = new StackPane(board);
        boardHolder.setMinSize(320, 320);
        board.widthProperty().bind(Bindings.min(boardHolder.widthProperty(), boardHolder.heightProperty()));
        board.heightProperty().bind(board.widthProperty());
        VBox.setVgrow(boardHolder, Priority.ALWAYS);

        clockRow.setAlignment(Pos.CENTER);
        VBox center = new VBox(10, banner, clockRow, boardHolder);
        center.setAlignment(Pos.TOP_CENTER);

        Label movesTitle = new Label("Moves");
        movesTitle.setStyle("-fx-font-weight: bold;");
        movesView.setFocusTraversable(false);
        movesView.setPlaceholder(new Label("No moves yet"));
        VBox.setVgrow(movesView, Priority.ALWAYS);
        VBox movesBox = new VBox(6, movesTitle, movesView);
        movesBox.setPrefWidth(170);
        movesBox.setPadding(new Insets(0, 16, 0, 0));

        Label scoreTitle = new Label("Scoreboard (this session)");
        scoreTitle.setStyle("-fx-font-weight: bold;");
        VBox scoreBox = new VBox(4, scoreTitle, scoreLabel, rematchLabel);
        scoreBox.setPadding(new Insets(10));
        scoreBox.setStyle("-fx-border-color: #bbb; -fx-border-radius: 6;");

        VBox right = new VBox(12, whitePanel, blackPanel, scoreBox);
        right.setPrefWidth(300);
        right.setPadding(new Insets(0, 0, 0, 16));

        BorderPane root = new BorderPane(center, toolbar, right, null, movesBox);
        root.setPadding(new Insets(14));

        // Smooth countdown between server snapshots; the server stays authoritative.
        Timeline ticker = new Timeline(new KeyFrame(Duration.millis(200), e -> updateClocks()));
        ticker.setCycleCount(Timeline.INDEFINITE);
        ticker.play();
        updateClocks();

        stage.setTitle("Chess Referee");
        stage.setScene(new Scene(root, 1180, 740));
        stage.setMinWidth(920);
        stage.setMinHeight(620);
        stage.show();
    }

    private void newGame() {
        if (lastState != null && lastState.phase() == GameRoom.Phase.PLAYING) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "A game is in progress. Start a new game anyway? "
                            + "Current links and the scoreboard will be discarded.",
                    ButtonType.OK, ButtonType.CANCEL);
            if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        }
        newGameButton.setDisable(true);
        TimeControl tc = timeChoice.getValue(); // read on the FX thread
        Thread worker = new Thread(() -> {
            try {
                if (server == null) {
                    WebServer fresh = new WebServer();
                    fresh.start(port); // throws if the port is taken
                    server = fresh;
                }
                GameRoom fresh = server.newRoom(state -> Platform.runLater(() -> render(state)));
                fresh.setTimeControl(tc);
                room = fresh;
                String base = "http://" + LanIp.detect() + ":" + port;
                Platform.runLater(() -> {
                    serverLabel.setText(base);
                    whitePanel.setLink(base + "/join/" + fresh.id() + "/white");
                    blackPanel.setLink(base + "/join/" + fresh.id() + "/black");
                });
            } catch (Exception e) {
                Platform.runLater(() -> showStartError(e));
            } finally {
                Platform.runLater(() -> newGameButton.setDisable(false));
            }
        }, "server-start");
        worker.setDaemon(true);
        worker.start();
    }

    private void render(RoomState s) {
        lastState = s;
        stateReceivedNanos = System.nanoTime();
        board.update(s.board(), s.lastMove());
        whitePanel.setStatus(s.whiteConnected(), s.whiteEverConnected());
        blackPanel.setStatus(s.blackConnected(), s.blackEverConnected());
        scoreLabel.setText("White " + s.whiteWins() + " · Draws " + s.draws()
                + " · Black " + s.blackWins());
        banner.setText(switch (s.phase()) {
            case WAITING -> "Waiting for both players to join…";
            case PLAYING -> (s.turn() == Color.WHITE ? "White" : "Black")
                    + " to move" + (s.check() ? " — check!" : "");
            case OVER -> s.result();
        });
        rematchLabel.setText(s.phase() == GameRoom.Phase.OVER
                ? "Rematch votes: " + s.rematchVotes().size() + "/2"
                : "");
        movesView.getItems().setAll(numberedRows(s.history()));
        if (!movesView.getItems().isEmpty()) movesView.scrollTo(movesView.getItems().size() - 1);
        updateClocks();
    }

    /** ["e4","e5","Nf3"] → ["1. e4 e5", "2. Nf3"]. */
    private static List<String> numberedRows(List<String> history) {
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < history.size(); i += 2) {
            rows.add((i / 2 + 1) + ". " + history.get(i)
                    + (i + 1 < history.size() ? "  " + history.get(i + 1) : ""));
        }
        return rows;
    }

    /** Repaints both clock labels from the last snapshot, ticking the running side locally. */
    private void updateClocks() {
        RoomState s = lastState;
        boolean timed = s != null && s.timeControl() != null;
        clockRow.setVisible(timed);
        clockRow.setManaged(timed);
        if (!timed) return;
        long elapsed = (System.nanoTime() - stateReceivedNanos) / 1_000_000;
        styleClock(whiteClock, "White",
                s.whiteMillis() - (s.clockRunning() == Color.WHITE ? elapsed : 0),
                s.clockRunning() == Color.WHITE);
        styleClock(blackClock, "Black",
                s.blackMillis() - (s.clockRunning() == Color.BLACK ? elapsed : 0),
                s.clockRunning() == Color.BLACK);
    }

    private static void styleClock(Label label, String side, long millis, boolean running) {
        label.setText(side + " " + clockText(millis));
        String color = millis < 30_000 ? "#cc3333" : running ? "#1a7a1a" : "#666";
        label.setStyle("-fx-font-size: 16px; -fx-text-fill: " + color
                + "; -fx-font-weight: " + (running ? "bold" : "normal") + ";");
    }

    private static String clockText(long millis) {
        long seconds = Math.max(0, millis + 999) / 1000; // ceil: 0:00 only at zero
        return seconds / 60 + ":" + String.format("%02d", seconds % 60);
    }

    private void showStartError(Exception e) {
        boolean portTaken = false;
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof BindException) portTaken = true;
        }
        String message = portTaken
                ? "Port " + port + " is already in use by another program.\n\n"
                + "Close that program or start this app with a different port, e.g. --port=8081."
                : "Could not start the server:\n" + e.getMessage();
        new Alert(Alert.AlertType.ERROR, message, ButtonType.OK).showAndWait();
    }

    @Override
    public void stop() {
        if (server != null) server.stop();
        Platform.exit();
        System.exit(0); // make sure no stray server thread keeps the JVM alive
    }

    /** QR code + join link + connection indicator for one color. */
    private static final class SeatPanel extends VBox {
        private final ImageView qr = new ImageView();
        private final TextField link = new TextField();
        private final Label status = new Label("● no game");

        SeatPanel(String colorName) {
            super(6);
            Label name = new Label(colorName);
            name.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
            link.setEditable(false);
            qr.setFitWidth(190);
            qr.setFitHeight(190);
            setStatusStyle("#b58900", "● no game");
            setPadding(new Insets(10));
            setStyle("-fx-border-color: #bbb; -fx-border-radius: 6;");
            setAlignment(Pos.CENTER);
            getChildren().addAll(name, qr, link, status);
        }

        void setLink(String url) {
            link.setText(url);
            qr.setImage(Qr.encode(url, 380));
            setStatusStyle("#b58900", "● waiting to join…");
        }

        void setStatus(boolean connected, boolean everConnected) {
            if (connected) setStatusStyle("#2a9d2a", "● connected");
            else if (everConnected) setStatusStyle("#cc3333", "● disconnected — same link reconnects");
            else setStatusStyle("#b58900", "● waiting to join…");
        }

        private void setStatusStyle(String color, String text) {
            status.setText(text);
            status.setStyle("-fx-text-fill: " + color + "; -fx-font-weight: bold;");
        }
    }
}
