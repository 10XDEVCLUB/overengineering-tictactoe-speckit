package org.xxdc.oss.example.transport.http;

import java.util.UUID;

/**
 * An opaque token assigned to a player when they join a game session.
 *
 * <p>The client includes this token in every subsequent move request to identify itself. Tokens are
 * generated server-side using {@link UUID#randomUUID()}.
 *
 * @param value the unique token value
 */
public record PlayerToken(UUID value) {

  /** Creates a new random player token. */
  public static PlayerToken generate() {
    return new PlayerToken(UUID.randomUUID());
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
