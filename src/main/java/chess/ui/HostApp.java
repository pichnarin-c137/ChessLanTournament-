package chess.ui;

import chess.server.GameRoom;
import chess.server.LanIp;
import chess.server.RoomState;
import chess.server.WebServer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.net.BindException;

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
    private RoomState lastState;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        port = Integer.parseInt(getParameters().getNamed().getOrDefault("port", "8080"));

        banner.setStyle("-fx-font-size: 17px; -fx-font-weight: bold;");
        scoreLabel.setStyle("-fx-font-size: 15px;");
        newGameButton.setOnAction(e -> newGame());

        Label title = new Label("♞ Chess Referee");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(12, title, spacer, serverLabel, newGameButton);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(0, 0, 12, 0));

        // The board fills whatever space the window gives it, staying square.
        StackPane boardHolder = new StackPane(board);
        boardHolder.setMinSize(320, 320);
        board.widthProperty().bind(Bindings.min(boardHolder.widthProperty(), boardHolder.heightProperty()));
        board.heightProperty().bind(board.widthProperty());
        VBox.setVgrow(boardHolder, Priority.ALWAYS);

        VBox center = new VBox(10, banner, boardHolder);
        center.setAlignment(Pos.TOP_CENTER);

        Label scoreTitle = new Label("Scoreboard (this session)");
        scoreTitle.setStyle("-fx-font-weight: bold;");
        VBox scoreBox = new VBox(4, scoreTitle, scoreLabel, rematchLabel);
        scoreBox.setPadding(new Insets(10));
        scoreBox.setStyle("-fx-border-color: #bbb; -fx-border-radius: 6;");

        VBox right = new VBox(12, whitePanel, blackPanel, scoreBox);
        right.setPrefWidth(300);
        right.setPadding(new Insets(0, 0, 0, 16));

        BorderPane root = new BorderPane(center, toolbar, right, null, null);
        root.setPadding(new Insets(14));

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
        Thread worker = new Thread(() -> {
            try {
                if (server == null) {
                    WebServer fresh = new WebServer();
                    fresh.start(port); // throws if the port is taken
                    server = fresh;
                }
                GameRoom room = server.newRoom(state -> Platform.runLater(() -> render(state)));
                String base = "http://" + LanIp.detect() + ":" + port;
                Platform.runLater(() -> {
                    serverLabel.setText(base);
                    whitePanel.setLink(base + "/join/" + room.id() + "/white");
                    blackPanel.setLink(base + "/join/" + room.id() + "/black");
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
        board.update(s.board(), s.lastMove());
        whitePanel.setStatus(s.whiteConnected(), s.whiteEverConnected());
        blackPanel.setStatus(s.blackConnected(), s.blackEverConnected());
        scoreLabel.setText("White " + s.whiteWins() + " · Draws " + s.draws()
                + " · Black " + s.blackWins());
        banner.setText(switch (s.phase()) {
            case WAITING -> "Waiting for both players to join…";
            case PLAYING -> (s.turn() == chess.engine.Color.WHITE ? "White" : "Black")
                    + " to move" + (s.check() ? " — check!" : "");
            case OVER -> s.result();
        });
        rematchLabel.setText(s.phase() == GameRoom.Phase.OVER
                ? "Rematch votes: " + s.rematchVotes().size() + "/2"
                : "");
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
