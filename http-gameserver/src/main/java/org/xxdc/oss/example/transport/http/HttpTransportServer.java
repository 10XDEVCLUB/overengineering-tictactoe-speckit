package org.xxdc.oss.example.transport.http;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import org.xxdc.oss.example.GameState;
import org.xxdc.oss.example.transport.TransportConfiguration;
import org.xxdc.oss.example.transport.TransportServer;

/**
 * Implements the {@link TransportServer} interface over HTTP using Server-Sent Events for state
 * push and a blocking queue for move reception.
 *
 * <p>This design allows the existing {@code Game} class and {@code PlayerNode.Remote} to work with
 * HTTP transport without any changes — the blocking {@link #accept()} method is handled by virtual
 * threads (JEP 444), avoiding platform thread exhaustion.
 *
 * <p>The server side uses JDK built-in {@code com.sun.net.httpserver} (JEP 408). Move submissions
 * arrive via HTTP POST and are placed onto a {@link SynchronousQueue} that {@link #accept()} blocks
 * on.
 */
public class HttpTransportServer implements TransportServer {

  private static final Logger log = System.getLogger(HttpTransportServer.class.getName());

  private final SynchronousQueue<Integer> moveQueue = new SynchronousQueue<>();
  private volatile SseHandler sseHandler;
  private String playerMarker;

  @Override
  public void initialize(TransportConfiguration config) {
    this.playerMarker = config.playerMarker();
    log.log(Level.INFO, "HttpTransportServer initialized for player {0}", playerMarker);
  }

  @Override
  public void send(GameState state) {
    if (sseHandler != null && sseHandler.isOpen()) {
      try {
        String json = state.asJsonString();
        sseHandler.sendEvent("state", json);
      } catch (Exception e) {
        log.log(Level.WARNING, "Failed to send SSE event: {0}", e.getMessage());
      }
    }
  }

  @Override
  public int accept() {
    try {
      var move = moveQueue.poll(30, TimeUnit.SECONDS);
      if (move == null) {
        throw new RuntimeException("Timed out waiting for move from player " + playerMarker);
      }
      return move;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Interrupted waiting for move", e);
    }
  }

  /**
   * Submits a move from the HTTP POST handler to the blocking game loop.
   *
   * @param position the board position
   * @throws InterruptedException if the thread is interrupted while waiting
   */
  public void offerMove(int position) throws InterruptedException {
    moveQueue.put(position);
  }

  /**
   * Attaches an SSE handler for pushing game state updates to this player.
   *
   * @param handler the SSE handler connected to this player's HTTP exchange
   */
  public void attachSseHandler(SseHandler handler) {
    this.sseHandler = handler;
  }

  /** Returns the assigned player marker. */
  public String playerMarker() {
    return playerMarker;
  }

  @Override
  public void close() {
    if (sseHandler != null) {
      sseHandler.close();
    }
  }
}
