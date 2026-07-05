package chess.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfigTest {

    @Test
    void readsOverridesLiveAndFallsBackPerKey(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("application.properties");
        Files.writeString(file, "bot.move.delay.min.ms=5\nbot.move.delay.max.ms=7\n");
        System.setProperty("chess.config", file.toString());
        try {
            assertEquals(5, Config.botDelayMinMs());
            assertEquals(7, Config.botDelayMaxMs());
            Files.writeString(file, "bot.move.delay.min.ms=9\n"); // edited while "running"
            assertEquals(9, Config.botDelayMinMs()); // picked up without a restart
            assertEquals(800, Config.botDelayMaxMs()); // dropped key: built-in default
        } finally {
            System.clearProperty("chess.config");
        }
    }

    @Test
    void missingFileAndGarbageUseDefaults(@TempDir Path dir) throws Exception {
        System.setProperty("chess.config", dir.resolve("nope.properties").toString());
        try {
            assertEquals(400, Config.botDelayMinMs());
            assertEquals(800, Config.botDelayMaxMs());
            Path file = dir.resolve("bad.properties");
            Files.writeString(file, "bot.move.delay.min.ms=fast\nbot.move.delay.max.ms=-3\n");
            System.setProperty("chess.config", file.toString());
            assertEquals(400, Config.botDelayMinMs()); // not a number
            assertEquals(0, Config.botDelayMaxMs());   // negative clamps to zero
        } finally {
            System.clearProperty("chess.config");
        }
    }
}
