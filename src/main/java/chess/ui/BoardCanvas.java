package chess.ui;

import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

/**
 * Read-only referee board. White is always at the bottom. The canvas redraws
 * whenever its size changes; the container decides the size (see HostApp).
 */
public final class BoardCanvas extends Canvas {

    private static final Color LIGHT = Color.web("#f0d9b5");
    private static final Color DARK = Color.web("#b58863");
    private static final Color LAST_MOVE = Color.web("#ffd500", 0.4);
    private static final String GLYPHS = "kqrbnp"; // filled set, colored by fill

    private String board = ".".repeat(64);
    private String lastMove = null;

    public BoardCanvas() {
        widthProperty().addListener(o -> draw());
        heightProperty().addListener(o -> draw());
    }

    /** @param board64 64 chars, index 0 = a1; {@code lastMoveUci} may be null. */
    public void update(String board64, String lastMoveUci) {
        this.board = board64;
        this.lastMove = lastMoveUci;
        draw();
    }

    private void draw() {
        double cell = Math.min(getWidth(), getHeight()) / 8;
        if (cell <= 0) return;
        GraphicsContext g = getGraphicsContext2D();
        g.clearRect(0, 0, getWidth(), getHeight());
        g.setTextAlign(TextAlignment.CENTER);
        g.setTextBaseline(VPos.CENTER);
        Font pieceFont = Font.font("DejaVu Sans", FontWeight.NORMAL, cell * 0.78);
        Font coordFont = Font.font("System", cell * 0.16);

        for (int square = 0; square < 64; square++) {
            int file = square & 7, rank = square >> 3;
            double x = file * cell, y = (7 - rank) * cell;
            g.setFill((file + rank) % 2 == 0 ? DARK : LIGHT);
            g.fillRect(x, y, cell, cell);
            if (isLastMoveSquare(square)) {
                g.setFill(LAST_MOVE);
                g.fillRect(x, y, cell, cell);
            }
            g.setFont(coordFont);
            g.setFill((file + rank) % 2 == 0 ? LIGHT : DARK);
            g.setTextAlign(TextAlignment.LEFT);
            if (file == 0) g.fillText(String.valueOf(rank + 1), x + 2, y + cell * 0.14);
            if (rank == 0) g.fillText(String.valueOf((char) ('a' + file)), x + 2, y + cell - cell * 0.12);
            g.setTextAlign(TextAlignment.CENTER);

            char c = board.charAt(square);
            if (c == '.') continue;
            String glyph = String.valueOf((char) ('♚' + GLYPHS.indexOf(Character.toLowerCase(c))));
            boolean white = Character.isUpperCase(c);
            g.setFont(pieceFont);
            g.setStroke(white ? Color.web("#333") : Color.web("#ddd"));
            g.setLineWidth(cell * 0.02);
            g.setFill(white ? Color.web("#fafafa") : Color.web("#2b2b2b"));
            double cx = x + cell / 2, cy = y + cell / 2 + cell * 0.03;
            g.fillText(glyph, cx, cy);
            g.strokeText(glyph, cx, cy);
        }
    }

    private boolean isLastMoveSquare(int square) {
        if (lastMove == null || lastMove.length() < 4) return false;
        int from = (lastMove.charAt(1) - '1') * 8 + (lastMove.charAt(0) - 'a');
        int to = (lastMove.charAt(3) - '1') * 8 + (lastMove.charAt(2) - 'a');
        return square == from || square == to;
    }
}
