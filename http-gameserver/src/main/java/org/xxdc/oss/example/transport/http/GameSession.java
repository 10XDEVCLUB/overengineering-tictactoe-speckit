package org.xxdc.oss.example.transport.http;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.xxdc.oss.example.GameState;

/**
 * Represents an active or completed game session managed by the HTTP game server.
 *
 * <p>Each session tracks its unique identifier, current lifecycle status, game state, the mapping
 * of player tokens to their assigned markers, and the creation timestamp.
 *
 * @param id unique session identifier
 * @param status current lifecycle state
 * @param gameState current board and player state from the api module
 * @param playerTokens mapping of player tokens to assigned markers (e.g., "X", "O")
 * @param createdAt session creation timestamp
 */
public record GameSession(
    UUID id,
    SessionStatus status,
    GameState gameState,
    Map<PlayerToken, String> playerTokens,
    Instant createdAt) {

  /**
   * Creates a new session in the WAITING state with no game state or players.
   *
   * @return a fresh game session awaiting players
   */
  public static GameSession create() {
    return new GameSession(
        UUID.randomUUID(), new SessionStatus.Waiting(), null, Map.of(), Instant.now());
  }

  /**
   * Returns a new session with the given status.
   *
   * @param newStatus the new lifecycle status
   * @return updated session
   */
  public GameSession withStatus(SessionStatus newStatus) {
    return new GameSession(id, newStatus, gameState, playerTokens, createdAt);
  }

  /**
   * Returns a new session with the given game state.
   *
   * @param newState the updated game state
   * @return updated session
   */
  public GameSession withGameState(GameState newState) {
    return new GameSession(id, status, newState, playerTokens, createdAt);
  }

  /**
   * Returns a new session with the given player tokens map.
   *
   * @param newTokens the updated player token mapping
   * @return updated session
   */
  public GameSession withPlayerTokens(Map<PlayerToken, String> newTokens) {
    return new GameSession(id, status, gameState, newTokens, createdAt);
  }
}
