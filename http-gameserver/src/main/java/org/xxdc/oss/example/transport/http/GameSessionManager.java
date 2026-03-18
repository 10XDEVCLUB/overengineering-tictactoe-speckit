package org.xxdc.oss.example.transport.http;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.xxdc.oss.example.GameBoard;
import org.xxdc.oss.example.GameState;

/**
 * Manages game session lifecycle for the HTTP game server.
 *
 * <p>Sessions are stored in a {@link ConcurrentHashMap} for thread-safe concurrent access. Each
 * session progresses through WAITING → ACTIVE → COMPLETED states. Player identity is tracked via
 * server-assigned {@link PlayerToken} instances.
 *
 * <p>This class is thread-safe. Move validation ensures turn order, bounds checking, and
 * occupied-cell rejection per FR-007.
 */
public class GameSessionManager {

  private static final Logger log = System.getLogger(GameSessionManager.class.getName());

  private static final String[] MARKERS = {"X", "O"};
  private static final int DEFAULT_MAX_SESSIONS = 100;
  private static final Duration DEFAULT_SESSION_TIMEOUT = Duration.ofMinutes(10);

  private final ConcurrentHashMap<UUID, GameSession> sessions = new ConcurrentHashMap<>();
  private final int maxSessions;
  private final Duration sessionTimeout;

  /** Creates a session manager with default capacity (100) and timeout (10 minutes). */
  public GameSessionManager() {
    this(DEFAULT_MAX_SESSIONS, DEFAULT_SESSION_TIMEOUT);
  }

  /**
   * Creates a session manager with configurable capacity and timeout.
   *
   * @param maxSessions maximum concurrent sessions (returns 503 when exceeded)
   * @param sessionTimeout idle timeout after which sessions are cleaned up
   */
  public GameSessionManager(int maxSessions, Duration sessionTimeout) {
    this.maxSessions = maxSessions;
    this.sessionTimeout = sessionTimeout;
  }

  /**
   * Creates a new game session in the WAITING state.
   *
   * @return the created session
   */
  public GameSession createSession() {
    if (sessions.size() >= maxSessions) {
      throw new IllegalStateException("Server at capacity");
    }
    var session = GameSession.create();
    sessions.put(session.id(), session);
    log.log(
        Level.INFO,
        "Session created: {0} (active: {1}/{2})",
        session.id(),
        sessions.size(),
        maxSessions);
    return session;
  }

  /**
   * Joins an existing session, assigning a player token and marker.
   *
   * @param sessionId the session to join
   * @return a record containing the assigned token, marker, and updated session
   * @throws IllegalStateException if the session is full or not found
   */
  public JoinResult joinSession(UUID sessionId) {
    var session = sessions.get(sessionId);
    if (session == null) {
      throw new IllegalStateException("Session not found: " + sessionId);
    }
    if (!(session.status() instanceof SessionStatus.Waiting)) {
      throw new IllegalStateException("Session full");
    }

    var token = PlayerToken.generate();
    int playerIndex = session.playerTokens().size();
    String marker = MARKERS[playerIndex];

    var newTokens = new HashMap<>(session.playerTokens());
    newTokens.put(token, marker);

    SessionStatus newStatus;
    GameState gameState = session.gameState();
    if (newTokens.size() == 2) {
      newStatus = new SessionStatus.Active();
      gameState = new GameState(GameBoard.withDimension(3), List.of(MARKERS), 0);
    } else {
      newStatus = session.status();
    }

    var updated =
        session
            .withPlayerTokens(Map.copyOf(newTokens))
            .withStatus(newStatus)
            .withGameState(gameState);
    sessions.put(sessionId, updated);
    log.log(Level.INFO, "Player {0} joined session {1} as {2}", token, sessionId, marker);
    return new JoinResult(token, marker, updated);
  }

  /**
   * Submits a move for a player in a session.
   *
   * @param sessionId the session
   * @param playerToken the player's token
   * @param position the board position (0-based)
   * @return the updated session after the move
   * @throws IllegalStateException if the move is invalid
   */
  public GameSession submitMove(UUID sessionId, PlayerToken playerToken, int position) {
    var session = sessions.get(sessionId);
    if (session == null) {
      throw new IllegalStateException("Session not found: " + sessionId);
    }
    if (session.status() instanceof SessionStatus.Completed c) {
      throw new IllegalStateException("Game over:" + c.outcome());
    }
    if (!(session.status() instanceof SessionStatus.Active)) {
      throw new IllegalStateException("Game not started");
    }

    String marker = session.playerTokens().get(playerToken);
    if (marker == null) {
      throw new IllegalStateException("Invalid player token");
    }

    var state = session.gameState();
    String currentMarker = state.playerMarkers().get(state.currentPlayerIndex());
    if (!marker.equals(currentMarker)) {
      throw new IllegalStateException("Not your turn");
    }

    if (!state.board().isValidMove(position)) {
      throw new IllegalStateException("Position " + position + " is already occupied");
    }

    var newState = state.afterPlayerMoves(position);
    String outcome = null;
    SessionStatus newStatus = session.status();

    if (newState.lastPlayerHasChain()) {
      outcome = "win:" + marker;
      newStatus = new SessionStatus.Completed(outcome);
    } else if (!newState.hasMovesAvailable()) {
      outcome = "draw";
      newStatus = new SessionStatus.Completed(outcome);
    }

    var updated = session.withGameState(newState).withStatus(newStatus);
    sessions.put(sessionId, updated);
    log.log(
        Level.INFO,
        "Move: session={0}, player={1}, position={2}, outcome={3}",
        sessionId,
        marker,
        position,
        outcome);
    return updated;
  }

  /**
   * Retrieves a session by ID.
   *
   * @param sessionId the session ID
   * @return the session, or null if not found
   */
  public GameSession getSession(UUID sessionId) {
    return sessions.get(sessionId);
  }

  /**
   * Removes a completed or abandoned session.
   *
   * @param sessionId the session to remove
   */
  public void removeSession(UUID sessionId) {
    sessions.remove(sessionId);
    log.log(Level.INFO, "Session removed: {0}", sessionId);
  }

  /** Returns the number of active sessions. */
  public int sessionCount() {
    return sessions.size();
  }

  /** Returns the maximum allowed concurrent sessions. */
  public int maxSessions() {
    return maxSessions;
  }

  /**
   * Removes sessions that have been idle beyond the configured timeout.
   *
   * @return the number of sessions removed
   */
  public int cleanupExpiredSessions() {
    var now = Instant.now();
    int removed = 0;
    for (var entry : sessions.entrySet()) {
      var session = entry.getValue();
      if (Duration.between(session.createdAt(), now).compareTo(sessionTimeout) > 0) {
        sessions.remove(entry.getKey());
        log.log(Level.INFO, "Session expired and removed: {0}", entry.getKey());
        removed++;
      }
    }
    if (removed > 0) {
      log.log(
          Level.INFO, "Cleaned up {0} expired sessions (remaining: {1})", removed, sessions.size());
    }
    return removed;
  }

  /** Result of joining a session. */
  public record JoinResult(PlayerToken token, String marker, GameSession session) {}
}
