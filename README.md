# LAN Chess Referee

A local two-player chess game. A laptop runs a JavaFX app that acts as a **pure
host/referee** — it never plays. Both players join from their own phone or
browser by scanning a QR code, over the same Wi-Fi. The app embeds an HTTP +
WebSocket server that serves the mobile board and holds the **authoritative
chess engine**: every move is validated server-side, and every change is pushed
live to both players and the referee's read-only board.

No database, no files — all state lives in memory and disappears when the app
closes.

## Requirements

- **JDK 17** (with `jpackage` for building the installer)
- **Maven 3.8+**
- Host laptop and both players on the **same Wi-Fi / LAN** — this is by design;
  nothing is exposed to the internet. If the LAN blocks client-to-client
  traffic (guest networks often do) or a firewall blocks the port, players
  can't join. Allow inbound TCP on the chosen port (default **8080**).

## Run (development)

```bash
mvn javafx:run                                 # default port 8080
mvn javafx:run -Djavafx.args="--port=8081"     # if 8080 is taken (e.g. Jenkins)
```

Click **New Game**: the server starts, and two QR codes appear — one per
color. Each player scans theirs and plays in the browser. The game starts once
both are connected.

Pick a **time control** in the toolbar before starting (default 10 min;
"5|3" means 5 minutes plus 3 seconds per move; "No clock" disables clocks).
Changing it later applies to the next game to start — the next rematch, or
immediately while still waiting for players.

## Build & run the fat jar

```bash
mvn clean package
java -jar target/chess-referee-1.0.0-all.jar            # default port 8080
java -jar target/chess-referee-1.0.0-all.jar --port=8081
```

The shaded jar bundles JavaFX's Linux natives, so it is Linux-x64 specific.

## Package as a .deb

```bash
./package.sh       # needs jpackage (JDK 17), fakeroot and dpkg
sudo apt install ./target/chess-referee_1.0.0-1_amd64.deb
```

Installs "chess-referee" into the application menu (Games).

## How it works

- **`chess.engine`** — pure-Java rules engine, no UI/network imports. Full
  rules: castling (all legality conditions), en passant, promotion with piece
  choice, check/checkmate/stalemate, turn order, no moving into check.
  **Draw rules included**: stalemate, insufficient material, the 50-move rule
  and threefold repetition are declared automatically. It also records the
  **move history** in standard algebraic notation ("Nf3", "O-O", "Qh4#"),
  shown live on the referee window and both player screens.
- **`chess.server`** — Javalin (embedded Jetty) serving the one-file web client
  at `/join/<gameId>/<color>` and relaying JSON over `/ws/<gameId>/<color>`.
  One `GameRoom` with two color seats: a taken color rejects further devices;
  a disconnected player reconnects through the same link/QR. Runs off the
  JavaFX thread; UI updates arrive via `Platform.runLater`.
- **`chess.ui`** — the referee window: live read-only board, both QR/link
  panels with connection indicators, turn/check banner and the session
  scoreboard. QR codes are generated in-app with ZXing.

Design notes:

- **Rematch keeps colors** — seats are bound to their color-specific link so
  reconnecting always works; the scoreboard (wins/draws) persists across
  rematches and resets on **New Game** or app exit.
- **Clocks are server-owned.** The clock starts when both players have joined,
  keeps running if a player disconnects (as a physical chess clock would), and
  refills on rematch. Fischer increments are added after each completed move.
  Both browsers and the referee tick the display locally and resync on every
  server update. Running out of time loses — unless the opponent has only a
  bare king, which is scored as a draw (a simplification of the FIDE
  insufficient-material-on-timeout rule).
- **LAN IP pick** — the app advertises the first active, non-loopback,
  site-local IPv4 it finds. With several active interfaces the first one wins;
  the chosen address is shown in the toolbar so you can verify it.
- If the port is taken you get a clear error dialog suggesting `--port=`.

## Tests

```bash
mvn test
```

Engine unit tests cover the tricky rules (castling legality, en passant,
promotion, checkmate, stalemate, pins, draws), plus an integration test that
drives a real WebSocket session end to end (join, reject taken seat, fool's
mate, rematch, resign, reconnect).
