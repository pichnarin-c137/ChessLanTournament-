package chess.server;

import java.util.List;

/**
 * A per-player time budget with a Fischer increment (added after each move).
 * {@link #NONE} means the game is played without clocks.
 */
public record TimeControl(long baseMillis, long incMillis) {

    public static final TimeControl NONE = new TimeControl(0, 0);

    /** The choices offered by the referee's dropdown. */
    public static final List<TimeControl> PRESETS = List.of(
            NONE,
            minutes(1, 0), minutes(3, 0), minutes(3, 2), minutes(5, 0), minutes(5, 3),
            minutes(10, 0), minutes(10, 5), minutes(15, 10), minutes(30, 0));

    public static TimeControl minutes(int base, int incSeconds) {
        return new TimeControl(base * 60_000L, incSeconds * 1_000L);
    }

    public boolean timed() {
        return baseMillis > 0;
    }

    /** "No clock", "5 min", "5|3" (minutes | increment seconds). */
    public String label() {
        if (!timed()) return "No clock";
        long min = baseMillis / 60_000;
        return incMillis == 0 ? min + " min" : min + "|" + incMillis / 1000;
    }
}
