package org.xxdc.oss.example;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.xxdc.oss.example.transport.http.GameSessionManager;
import org.xxdc.oss.example.transport.http.HttpProtocol;
import org.xxdc.oss.example.transport.http.PlayerToken;
import org.xxdc.oss.example.transport.http.SessionStatus;
import org.xxdc.oss.example.transport.http.SseHandler;

/**
 * HTTP-based game server using the JDK built-in {@code com.sun.net.httpserver} (JEP 408).
 *
 * <p>Exposes REST endpoints for game creation, joining, move submission, state retrieval, and
 * Server-Sent Events for real-time state push. Uses virtual threads (JEP 444) for I/O-bound HTTP
 * handling, satisfying Principle VII (Performance as a Feature).
 *
 * <p>Game state is managed entirely by {@link GameSessionManager} via REST requests. Unlike the TCP
 * game server which uses a {@code Game.play()} loop with blocking I/O, this HTTP server uses a
 * stateless request/response model where each POST to {@code /games/{id}/move} advances the game by
 * one turn. SSE handlers are notified after each move for real-time push.
 *
 * <p>This demonstrates JEP 408 (Simple Web Server) by building a complete game server on top of the
 * JDK's built-in HTTP server API without any external frameworks, satisfying Principle X
 * (Dependency Minimalism) and Principle I (Educational Parity).
 */
public class HttpGameServer {

  private static final Logger log = System.getLogger(HttpGameServer.class.getName());
  private static final int DEFAULT_PORT = 8080;

  private final GameSessionManager sessionManager = new GameSessionManager();
  private final ConcurrentHashMap<UUID, List<SseHandler>> sseHandlers = new ConcurrentHashMap<>();
  private final HttpServer server;

  /**
   * Creates and configures the HTTP game server on the given port.
   *
   * @param port the port to bind to
   * @throws IOException if the server cannot be created
   */
  public HttpGameServer(int port) throws IOException {
    server = HttpServer.create(new InetSocketAddress(port), 0);
    server.setExecutor(
        Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("http-game-", 1).factory()));
    server.createContext("/games", this::handleGames);
    log.log(Level.INFO, "HTTP Game Server configured on port {0}", port);
  }

  /** Starts the server. */
  public void start() {
    server.start();
    log.log(Level.INFO, "HTTP Game Server started on port {0}", server.getAddress().getPort());
  }

  /** Stops the server. */
  public void stop() {
    server.stop(0);
    log.log(Level.INFO, "HTTP Game Server stopped");
  }

  /** Returns the port the server is listening on. */
  public int getPort() {
    return server.getAddress().getPort();
  }

  /** Returns the session manager for testing purposes. */
  GameSessionManager sessionManager() {
    return sessionManager;
  }

  private void handleGames(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    String method = exchange.getRequestMethod();

    try {
      if (path.equals("/games") && method.equals("POST")) {
        handleCreateGame(exchange);
      } else if (path.matches("/games/[^/]+/join") && method.equals("POST")) {
        handleJoinGame(exchange, extractSessionId(path));
      } else if (path.matches("/games/[^/]+/move") && method.equals("POST")) {
        handleMove(exchange, extractSessionId(path));
      } else if (path.matches("/games/[^/]+/state") && method.equals("GET")) {
        handleGetState(exchange, extractSessionId(path));
      } else if (path.matches("/games/[^/]+/events") && method.equals("GET")) {
        handleEvents(exchange, extractSessionId(path));
      } else {
        sendResponse(exchange, 404, HttpProtocol.toErrorJson("Not found"));
      }
    } catch (IllegalStateException e) {
      int code = statusCodeForError(e.getMessage());
      sendResponse(exchange, code, HttpProtocol.toErrorJson(e.getMessage()));
    } catch (Exception e) {
      log.log(Level.ERROR, "Unexpected error handling {0} {1}: {2}", method, path, e.getMessage());
      sendResponse(exchange, 500, HttpProtocol.toErrorJson("Internal server error"));
    }
  }

  private void handleCreateGame(HttpExchange exchange) throws IOException {
    var session = sessionManager.createSession();
    sseHandlers.put(session.id(), new CopyOnWriteArrayList<>());
    sendResponse(exchange, 201, HttpProtocol.toSessionCreatedJson(session.id(), session.status()));
  }

  private void handleJoinGame(HttpExchange exchange, UUID sessionId) throws IOException {
    var result = sessionManager.joinSession(sessionId);

    // Notify existing SSE subscribers that a player joined
    if (result.session().status() instanceof SessionStatus.Active) {
      String startJson =
          HttpProtocol.toGameStateJson(
              sessionId, result.session().status(), result.session().gameState(), null);
      broadcastSse(sessionId, "start", startJson);
    }

    sendResponse(
        exchange,
        200,
        HttpProtocol.toJoinResponseJson(
            sessionId, result.token(), result.marker(), result.session().status()));
  }

  private void handleMove(HttpExchange exchange, UUID sessionId) throws IOException {
    String tokenHeader = exchange.getRequestHeaders().getFirst("X-Player-Token");
    if (tokenHeader == null) {
      sendResponse(exchange, 403, HttpProtocol.toErrorJson("Missing X-Player-Token header"));
      return;
    }

    PlayerToken playerToken = new PlayerToken(UUID.fromString(tokenHeader));
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    var position = HttpProtocol.fromMoveRequestJson(body);

    if (position.isEmpty()) {
      sendResponse(exchange, 400, HttpProtocol.toErrorJson("Invalid move request format"));
      return;
    }

    var updated = sessionManager.submitMove(sessionId, playerToken, position.get());

    String outcome = null;
    if (updated.status() instanceof SessionStatus.Completed c) {
      outcome = c.outcome();
    }

    String stateJson =
        HttpProtocol.toGameStateJson(sessionId, updated.status(), updated.gameState(), outcome);

    // Push state update to all SSE subscribers
    String eventType = outcome != null ? "end" : "state";
    broadcastSse(sessionId, eventType, stateJson);

    sendResponse(exchange, 200, stateJson);
  }

  private void handleGetState(HttpExchange exchange, UUID sessionId) throws IOException {
    var session = sessionManager.getSession(sessionId);
    if (session == null) {
      sendResponse(exchange, 404, HttpProtocol.toErrorJson("Session not found"));
      return;
    }

    String outcome = null;
    if (session.status() instanceof SessionStatus.Completed c) {
      outcome = c.outcome();
    }

    if (session.gameState() == null) {
      sendResponse(exchange, 200, HttpProtocol.toSessionCreatedJson(sessionId, session.status()));
      return;
    }

    sendResponse(
        exchange,
        200,
        HttpProtocol.toGameStateJson(sessionId, session.status(), session.gameState(), outcome));
  }

  private void handleEvents(HttpExchange exchange, UUID sessionId) throws IOException {
    var handlers = sseHandlers.get(sessionId);
    if (handlers == null) {
      sendResponse(exchange, 404, HttpProtocol.toErrorJson("Session not found"));
      return;
    }

    var sseHandler = new SseHandler(exchange);
    handlers.add(sseHandler);

    // Block until the game ends or connection drops (virtual thread — no waste)
    while (sseHandler.isOpen()) {
      try {
        Thread.sleep(500);
        var session = sessionManager.getSession(sessionId);
        if (session == null || session.status() instanceof SessionStatus.Completed) {
          break;
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    handlers.remove(sseHandler);
    sseHandler.close();
  }

  private void broadcastSse(UUID sessionId, String eventType, String data) {
    var handlers = sseHandlers.get(sessionId);
    if (handlers == null) return;
    for (var handler : handlers) {
      try {
        handler.sendEvent(eventType, data);
      } catch (IOException e) {
        log.log(Level.DEBUG, "Failed to send SSE to subscriber: {0}", e.getMessage());
      }
    }
  }

  private static UUID extractSessionId(String path) {
    String[] parts = path.split("/");
    return UUID.fromString(parts[2]);
  }

  private static int statusCodeForError(String message) {
    if (message == null) return 500;
    if (message.startsWith("Session not found")) return 404;
    if (message.equals("Session full")) return 409;
    if (message.startsWith("Game over")) return 410;
    if (message.equals("Not your turn") || message.equals("Invalid player token")) return 403;
    if (message.equals("Server at capacity")) return 503;
    if (message.contains("occupied") || message.contains("not started")) return 400;
    return 400;
  }

  private static void sendResponse(HttpExchange exchange, int statusCode, String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(statusCode, bytes.length);
    try (var os = exchange.getResponseBody()) {
      os.write(bytes);
    }
  }

  /**
   * Starts the HTTP game server.
   *
   * @param args optional port number (default: 8080)
   */
  public static void main(String[] args) throws IOException {
    int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
    var gameServer = new HttpGameServer(port);
    gameServer.start();
    log.log(Level.INFO, "HTTP Game Server running on http://localhost:{0}", port);
  }
}
