package chess.server;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Runtime knobs, read from {@code application.properties} in the working
 * directory (or the file named by {@code -Dchess.config=...}). The file is
 * re-read on every use, so edits apply live — no restart. A missing file or
 * a bad value silently falls back to the built-in default.
 */
public final class Config {

    private Config() {
    }

    /** Shortest "thinking" pause before a bot answers, in milliseconds. */
    public static int botDelayMinMs() {
        return intValue("bot.move.delay.min.ms", 400);
    }

    /** Longest "thinking" pause before a bot answers, in milliseconds. */
    public static int botDelayMaxMs() {
        return intValue("bot.move.delay.max.ms", 800);
    }

    private static int intValue(String key, int fallback) {
        try {
            return Math.max(0, Integer.parseInt(load().getProperty(key).trim()));
        } catch (RuntimeException e) { // absent key, blank file, garbage value
            return fallback;
        }
    }

    private static Properties load() {
        Properties props = new Properties();
        Path file = Path.of(System.getProperty("chess.config", "application.properties"));
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            } catch (Exception ignored) { // unreadable: defaults apply
            }
        }
        return props;
    }
}
