package chess;

import chess.ui.HostApp;
import javafx.application.Application;

/**
 * Plain launcher. Keeping the Main-Class free of the Application superclass
 * lets the shaded fat jar start without the JavaFX launcher's module checks.
 */
public final class Main {

    public static void main(String[] args) {
        Application.launch(HostApp.class, args);
    }
}
