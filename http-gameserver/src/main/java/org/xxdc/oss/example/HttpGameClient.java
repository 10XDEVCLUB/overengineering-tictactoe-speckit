package org.xxdc.oss.example;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.xxdc.oss.example.transport.http.HttpProtocol;
import org.xxdc.oss.example.transport.http.HttpTransportClient;

/**
 * HTTP game client entry point demonstrating JEP 517 (HTTP/3 for the HTTP Client API).
 *
 * <p>Creates an {@link HttpTransportClient} configured with the highest available HTTP version. On
 * JDK 26+, this uses HTTP/3 with QUIC-based transport and automatic fallback to HTTP/2 or HTTP/1.1.
 * On earlier JDK versions, HTTP/2 is used as the best available.
 *
 * <p>The client creates or joins a game session, then plays using a simple strategy (first
 * available move), logging the negotiated HTTP protocol version for observability (FR-010).
 *
 * <p>This demonstrates that the JDK's {@code java.net.http.HttpClient} API provides transparent
 * version negotiation — the same client code works across HTTP/1.1, HTTP/2, and HTTP/3 without any
 * changes, showcasing the backward-compatible design of JEP 517.
 */
public class HttpGameClient {

  private static final Logger log = System.getLogger(HttpGameClient.class.getName());

  /**
   * Runs the HTTP game client.
   *
   * <p>Usage: {@code HttpGameClient [host] [port] [sessionId]}
   *
   * <p>If no sessionId is provided, creates a new game. Otherwise joins the existing session.
   *
   * @param args optional host (default: localhost), port (default: 8080), sessionId
   */
  public static void main(String[] args) throws IOException {
    String host = args.length > 0 ? args[0] : "localhost";
    int port = args.length > 1 ? Integer.parseInt(args[1]) : 8080;
    UUID sessionId = args.length > 2 ? UUID.fromString(args[2]) : null;

    try (var client = new HttpTransportClient(host, port)) {
      log.log(
          Level.INFO,
          "HTTP Game Client started (requested version: {0})",
          client.requestedVersion());

      // Create or use existing session
      if (sessionId == null) {
        sessionId = client.createGame();
        log.log(Level.INFO, "Created game session: {0}", sessionId);
      }

      // Join the session
      var joinResult = client.joinGame(sessionId);
      log.log(
          Level.INFO,
          "Joined session {0} as {1} (marker: {2}, status: {3})",
          sessionId,
          joinResult.playerToken(),
          joinResult.marker(),
          joinResult.status());

      // Play the game using a simple first-available-move strategy
      boolean gameOver = false;
      while (!gameOver) {
        // Get current state
        String stateJson = client.getState(sessionId);

        // Check if game is complete
        if (stateJson.contains("\"status\":\"COMPLETED\"")) {
          log.log(Level.INFO, "Game completed: {0}", stateJson);
          gameOver = true;
          continue;
        }

        // Parse the state to determine if it's our turn
        Optional<GameState> state = HttpProtocol.fromGameStateJson(stateJson);
        if (state.isEmpty()) {
          // Game might still be waiting for second player
          if (stateJson.contains("\"status\":\"WAITING\"")) {
            log.log(Level.INFO, "Waiting for opponent to join...");
            try {
              Thread.sleep(1000);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              break;
            }
            continue;
          }
          log.log(Level.WARNING, "Could not parse game state: {0}", stateJson);
          break;
        }

        var gameState = state.get();
        String currentMarker = gameState.playerMarkers().get(gameState.currentPlayerIndex());

        if (!currentMarker.equals(joinResult.marker())) {
          // Not our turn — wait and poll
          try {
            Thread.sleep(500);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            break;
          }
          continue;
        }

        // Our turn — pick first available move
        List<Integer> moves = gameState.availableMoves();
        if (moves.isEmpty()) {
          log.log(Level.INFO, "No moves available — game should be over");
          break;
        }

        int move = moves.getFirst();
        log.log(Level.INFO, "Submitting move: position {0}", move);
        String moveResult = client.submitMove(sessionId, joinResult.playerToken(), move);
        log.log(Level.DEBUG, "Move result: {0}", moveResult);

        if (moveResult.contains("\"status\":\"COMPLETED\"")) {
          log.log(Level.INFO, "Game completed after our move: {0}", moveResult);
          gameOver = true;
        }
      }

      log.log(Level.INFO, "HTTP Game Client finished");
    }
  }
}
