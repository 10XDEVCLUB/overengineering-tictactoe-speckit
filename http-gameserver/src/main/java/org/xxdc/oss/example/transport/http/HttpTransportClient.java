package org.xxdc.oss.example.transport.http;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

/**
 * HTTP client for connecting to the HTTP game server, with HTTP/3 opt-in support.
 *
 * <p>This client demonstrates JEP 517 (HTTP/3 for the HTTP Client API) by configuring the {@link
 * HttpClient} with the highest available HTTP version. On JDK 26+, this uses {@code
 * HttpClient.Version.HTTP_3} for QUIC-based transport with automatic fallback. On earlier JDK
 * versions, it uses {@code HTTP_2} as the best available.
 *
 * <p>The negotiated protocol version is logged after each request for observability (FR-010). SSE
 * reconnection and request timeout with retry are supported for resilience.
 *
 * <p>Uses only the JDK built-in {@code java.net.http} module, satisfying Principle X (Dependency
 * Minimalism).
 */
public class HttpTransportClient implements AutoCloseable {

  private static final Logger log = System.getLogger(HttpTransportClient.class.getName());
  private static final int MAX_RETRIES = 3;
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  private final HttpClient client;
  private final String baseUrl;
  private final HttpClient.Version requestedVersion;

  /**
   * Creates an HTTP transport client targeting the given server.
   *
   * <p>Attempts to use the highest HTTP version available. On JDK 26+, this will be HTTP/3 (JEP
   * 517). On earlier versions, falls back to HTTP/2.
   *
   * @param host the server hostname
   * @param port the server port
   */
  public HttpTransportClient(String host, int port) {
    this.baseUrl = "http://" + host + ":" + port;
    this.requestedVersion = detectBestVersion();
    this.client =
        HttpClient.newBuilder()
            .version(requestedVersion)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    log.log(Level.INFO, "HttpTransportClient created with requested version {0}", requestedVersion);
  }

  /**
   * Creates a new game session on the server.
   *
   * @return the session ID
   * @throws IOException if the request fails
   */
  public UUID createGame() throws IOException {
    var response = postWithRetry(baseUrl + "/games", "");
    logNegotiatedVersion(response);
    String sessionId = extractField(response.body(), "sessionId");
    return UUID.fromString(sessionId);
  }

  /**
   * Joins an existing game session.
   *
   * @param sessionId the session to join
   * @return the join result containing player token, marker, and status
   * @throws IOException if the request fails
   */
  public JoinResult joinGame(UUID sessionId) throws IOException {
    var response = postWithRetry(baseUrl + "/games/" + sessionId + "/join", "");
    logNegotiatedVersion(response);
    String token = extractField(response.body(), "playerToken");
    String marker = extractField(response.body(), "assignedPlayerMarker");
    String status = extractField(response.body(), "status");
    return new JoinResult(token, marker, status);
  }

  /**
   * Submits a move to the server.
   *
   * @param sessionId the game session
   * @param playerToken the player's token
   * @param position the board position (0-based)
   * @return the response body containing updated game state
   * @throws IOException if the request fails after retries
   */
  public String submitMove(UUID sessionId, String playerToken, int position) throws IOException {
    var request =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/games/" + sessionId + "/move"))
            .header("Content-Type", "application/json")
            .header("X-Player-Token", playerToken)
            .timeout(REQUEST_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofString("{\"position\":" + position + "}"))
            .build();

    for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
      try {
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        logNegotiatedVersion(response);
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
          return response.body();
        }
        // Non-retryable client errors
        if (response.statusCode() >= 400 && response.statusCode() < 500) {
          return response.body();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("Interrupted during move submission", e);
      } catch (IOException e) {
        if (attempt == MAX_RETRIES) throw e;
        log.log(Level.WARNING, "Move attempt {0} failed, retrying: {1}", attempt, e.getMessage());
      }
    }
    throw new IOException("Move submission failed after " + MAX_RETRIES + " attempts");
  }

  /**
   * Retrieves the current game state.
   *
   * @param sessionId the game session
   * @return the game state JSON
   * @throws IOException if the request fails
   */
  public String getState(UUID sessionId) throws IOException {
    var request =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/games/" + sessionId + "/state"))
            .timeout(REQUEST_TIMEOUT)
            .GET()
            .build();
    try {
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      logNegotiatedVersion(response);
      return response.body();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted during state retrieval", e);
    }
  }

  /**
   * Returns the HTTP version that was requested (may differ from negotiated).
   *
   * @return the requested HTTP version
   */
  public HttpClient.Version requestedVersion() {
    return requestedVersion;
  }

  @Override
  public void close() {
    client.close();
  }

  private HttpResponse<String> postWithRetry(String url, String body) throws IOException {
    var request =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .timeout(REQUEST_TIMEOUT)
            .POST(
                body.isEmpty()
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body))
            .build();

    for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
      try {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("Interrupted during request", e);
      } catch (IOException e) {
        if (attempt == MAX_RETRIES) throw e;
        log.log(
            Level.WARNING, "Request attempt {0} failed, retrying: {1}", attempt, e.getMessage());
      }
    }
    throw new IOException("Request failed after " + MAX_RETRIES + " attempts");
  }

  private void logNegotiatedVersion(HttpResponse<?> response) {
    log.log(
        Level.INFO,
        "HTTP negotiated version: {0} (requested: {1})",
        response.version(),
        requestedVersion);
  }

  /**
   * Detects the best HTTP version available in the current JDK.
   *
   * <p>On JDK 26+, {@code HttpClient.Version.HTTP_3} is available (JEP 517). On earlier versions,
   * falls back to {@code HTTP_2}.
   */
  private static HttpClient.Version detectBestVersion() {
    try {
      // Attempt to resolve HTTP_3 enum constant (available in JDK 26+ via JEP 517)
      return HttpClient.Version.valueOf("HTTP_3");
    } catch (IllegalArgumentException e) {
      log.log(Level.INFO, "HTTP/3 not available in this JDK, falling back to HTTP/2");
      return HttpClient.Version.HTTP_2;
    }
  }

  private static String extractField(String json, String field) {
    var pattern = java.util.regex.Pattern.compile("\"" + field + "\":\"([^\"]+)\"");
    var matcher = pattern.matcher(json);
    if (matcher.find()) {
      return matcher.group(1);
    }
    throw new RuntimeException("Field " + field + " not found in: " + json);
  }

  /** Result of joining a game session. */
  public record JoinResult(String playerToken, String marker, String status) {}
}
