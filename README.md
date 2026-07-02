**Prompt:**

Build a **local two-player chess game** where a laptop runs a **JavaFX (Java 17)** app as a **pure host/referee** — it does not play. **Both** players (White and Black) join from their own browser/phone via  **QR code or link** , over the same local network. No database, no persistence — all state in memory, discarded when the app closes. Package the host with  **jpackage** .

**Architecture:**

* **Laptop = JavaFX host + referee.** Runs an **embedded HTTP + WebSocket server** (lightweight embedded server, e.g. Javalin or Jetty, inside the JavaFX process). Renders a **live referee/spectator board** and the scoreboard. Does NOT play a side.
* **Two player clients = browsers.** The server serves a minimal, mobile-friendly HTML/JS chessboard to each. No install for players.
* **Sync:** all moves relay in real time over WebSocket; the server holds authoritative game state and pushes updates to both players **and** the laptop's referee view.
* **All state in memory** , gone on app close.

**Host flow (JavaFX):**

1. Launch → "New Game" → server starts, determine the machine's  **LAN IP** .
2. Generate **two distinct join URLs** and **two QR codes** (generate QR in-app, e.g. ZXing):
   * White: `http://<lan-ip>:<port>/join/<gameId>/white`
   * Black: `http://<lan-ip>:<port>/join/<gameId>/black`
3. Display both QRs/links side by side, each labeled with its color, with a connection indicator ("White: waiting → connected").
4. Show the live board (referee view) and scoreboard. Game begins once **both** sides are connected.

**Player flow (web):**

* Scan the White or Black QR → served a clean, responsive chessboard bound to that color → auto-joined on the assigned side. Reject a second attempt to take an already-taken color.

**Chess engine — FULL rules (server-side, authoritative):**

* All piece moves; **castling** (both sides, all legality conditions),  **en passant** , **pawn promotion** (player picks the piece)
* **Check** ,  **checkmate** ,  **stalemate** ; reject illegal moves; enforce turn order; prevent moving into check
* Optional: draw by insufficient material / 50-move / threefold (state if included)
* Engine is the sole authority — validate every move server-side; never trust the client.

**Scoreboard (session only, in memory):**

* Track wins/losses/draws for the current session across rematches between the two connected players; reset on app close.
* Show it on the laptop referee view and on both player screens.
* On game end (mate/stalemate/resign), offer **Rematch** (optionally swap colors) and update the scoreboard.

**UI requirements:**

* **JavaFX referee view:** live board reflecting every move, turn indicator, both connection statuses, the two QR/link panels, scoreboard, and a game-over banner. Read-only board (host doesn't move pieces).
* **Web player board:** minimal, responsive, mobile-friendly; click/tap-to-move (select piece → legal targets highlighted → select destination); promotion picker; turn indicator; scoreboard; "not your turn" feedback.
* Handle disconnects gracefully (show "White disconnected", allow waiting for reconnect via the same link, or end game).

**Technical requirements:**

* Server/networking off the JavaFX Application Thread; UI updates via `Platform.runLater`.
* Clean separation: **chess engine** (pure Java, no UI/network deps, unit-testable), **server/session layer** (WebSocket + game room with two color slots),  **JavaFX referee UI** ,  **web client** .
* Same-LAN only — encode the correct non-loopback IPv4 in both QRs; if multiple interfaces, pick the active one and note the assumption.
* Clear error if the port is taken; clear handling if someone opens a join link for an already-claimed color.

**Build & packaging:**

* Maven project (`pom.xml`); dev run via `mvn javafx:run`.
* Release: shade into a fat jar (`maven-shade-plugin`), then `jpackage --type deb` (installs to the app menu).
* README: JDK 17 prerequisite, dev run, build steps, and the **same-WiFi requirement** for both players.

Keep code minimal and readable; isolate the chess engine and add a few unit tests for the tricky rules (castling, en passant, promotion, checkmate).

---

Same two honest constraints still apply: the **embedded server is mandatory** (that's what lets browsers join), and it's **same-WiFi only** by design. The only real change from before is the laptop is now a neutral referee handing out two color-specific invites instead of playing one side.
