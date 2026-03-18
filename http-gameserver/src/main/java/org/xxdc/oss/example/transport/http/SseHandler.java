package org.xxdc.oss.example.transport.http;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;

/**
 * Handles Server-Sent Events (SSE) for pushing game state updates to connected clients.
 *
 * <p>SSE is a simple text-based protocol over HTTP/1.1. This handler sets the {@code Content-Type:
 * text/event-stream} header, holds the connection open, and writes {@code event:} / {@code data:}
 * frames as game state changes occur.
 *
 * <p>Uses the JDK built-in {@code com.sun.net.httpserver} API (JEP 408) rather than external
 * frameworks, satisfying Principle X (Dependency Minimalism).
 */
public class SseHandler {

  private static final Logger log = System.getLogger(SseHandler.class.getName());

  private final OutputStream outputStream;
  private volatile boolean open = true;

  /**
   * Creates an SSE handler for the given HTTP exchange.
   *
   * <p>Sends the initial response headers (200 OK, text/event-stream) and prepares the output
   * stream for writing SSE events.
   *
   * @param exchange the HTTP exchange to use for SSE
   * @throws IOException if headers cannot be sent
   */
  public SseHandler(HttpExchange exchange) throws IOException {
    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
    exchange.getResponseHeaders().set("Cache-Control", "no-cache");
    exchange.getResponseHeaders().set("Connection", "keep-alive");
    exchange.sendResponseHeaders(200, 0);
    this.outputStream = exchange.getResponseBody();
  }

  /**
   * Sends an SSE event to the connected client.
   *
   * @param eventType the event type (e.g., "state", "start", "end", "error")
   * @param data the JSON data payload
   * @throws IOException if writing fails
   */
  public void sendEvent(String eventType, String data) throws IOException {
    if (!open) {
      throw new IOException("SSE stream is closed");
    }
    String frame = HttpProtocol.toSseEvent(eventType, data);
    outputStream.write(frame.getBytes(StandardCharsets.UTF_8));
    outputStream.flush();
    log.log(Level.DEBUG, "SSE event sent: type={0}", eventType);
  }

  /** Closes the SSE stream. */
  public void close() {
    open = false;
    try {
      outputStream.close();
    } catch (IOException e) {
      log.log(Level.DEBUG, "Error closing SSE stream: {0}", e.getMessage());
    }
  }

  /** Returns whether the SSE stream is still open. */
  public boolean isOpen() {
    return open;
  }
}
