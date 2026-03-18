package org.xxdc.oss.example.transport.http;

/**
 * Represents the lifecycle state of a game session managed by the HTTP game server.
 *
 * <p>Uses a sealed interface with record implementations to enable exhaustive pattern matching in
 * switch expressions, per project convention (Principle III).
 */
public sealed interface SessionStatus {

  /** The session is waiting for a second player to join. */
  record Waiting() implements SessionStatus {}

  /** The session is active — both players have joined and the game is in progress. */
  record Active() implements SessionStatus {}

  /** The session has completed — the game ended in a win or draw. */
  record Completed(String outcome) implements SessionStatus {}

  /** Returns a human-readable label for this status. */
  default String label() {
    return switch (this) {
      case Waiting _ -> "WAITING";
      case Active _ -> "ACTIVE";
      case Completed _ -> "COMPLETED";
    };
  }
}
