# LAN Chess Referee

JavaFX host/referee app + embedded Javalin server; two players join from
browsers over LAN. All state in memory. See README.md for user-facing docs.

## Commands

```bash
mvn javafx:run -Djavafx.args="--port=8081"   # dev run — ALWAYS use 8081 (see Gotchas)
mvn test                                     # 34 tests: engine rules, bot, real-WebSocket integration
mvn clean package                            # fat jar: target/chess-referee-1.0.0-all.jar
./package.sh                                 # .deb via jpackage → chess-referee_1.0.0-1_amd64.deb
```

## Architecture

- `chess.engine` — pure rules engine + `Bot` (negamax search), no UI/network imports.
  Server is authoritative. `Game.copy()`/`applyUnchecked` exist only for the bot's search.
- `chess.server` — Javalin HTTP+WS. `GameRoom` = one session, all methods synchronized.
- `chess.ui` — JavaFX referee window. Server runs off the FX thread; room snapshots
  arrive via `Platform.runLater`.
- `src/main/resources/web/play.html` — the entire web client (inline CSS/JS, zero chess
  logic: it only renders `state` messages and echoes legal moves the server sent).

## Conventions

- Squares are `0..63`, **a1 = 0**, h8 = 63. Board wire format: 64-char string in that
  order (FEN letters, `.` = empty) — this is NOT FEN rank ordering.
- Moves cross the wire as UCI (`e2e4`, `e7e8q`); history is SAN, generated in `Game`.
- Clocks are server-owned (`GameRoom`); clients only animate between `state` broadcasts.

## Gotchas

- **Port 8080 is permanently taken by Jenkins on this machine** — always run/test the
  app with `--port=8081`.
- `chess.Main` must stay a plain (non-`Application`) launcher class, or the shaded fat
  jar fails to start JavaFX.
- `play.html` contains an invisible U+FE0E variation selector (`const TEXT`) — editing
  that line with exact-match tools fails; it forces text (non-emoji) glyph rendering.
- `play.html` is loaded once into a static field (`WebServer.PLAY_HTML`) — restart the
  app to see web-client changes.
- `pom.xml` pins maven-compiler-plugin 3.11.0; removing it breaks the build on
  Maven 3.8.7 ("Source option 5 is no longer supported").
