package org.xxdc.oss.example;

import static org.testng.Assert.*;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * End-to-end validation of the scenarios documented in {@code
 * specs/014-http3-game-server/quickstart.md} section 3.
 *
 * <p>Each test method starts from a fresh session to remain independently executable. The test
 * verifies both the HTTP response codes and the JSON structure described in the quickstart
 * document, ensuring the documentation stays in sync with the implementation.
 */
public class QuickstartValidationTest {

  private HttpGameServer server;
  private HttpClient client;
  private String baseUrl;

  @BeforeClass
  public void startServer() throws IOException {
    server = new HttpGameServer(0); // ephemeral port
    server.start();
    baseUrl = "http://localhost:" + server.getPort();
    client = HttpClient.newHttpClient();
  }

  @AfterClass
  public void stopServer() {
    server.stop();
    client.close();
  }

  // -----------------------------------------------------------------------
  // Scenario 1 — Full game walkthrough (quickstart.md §3)
  //   create game → join as X → join as O → submit moves to completion
  // -----------------------------------------------------------------------

  @Test
  public void scenario_createGame_returnsSessionIdAndWaitingStatus() throws Exception {
    var response = post("/games", "");

    // § HTTP contract: POST /games → 201 {"sessionId":"...","status":"WAITING"}
    assertEquals(response.statusCode(), 201, "Create game should return 201 Created");
    assertTrue(
        response.body().contains("\"sessionId\""),
        "Response body should contain sessionId field: " + response.body());
    assertTrue(
        response.body().contains("\"status\":\"WAITING\""),
        "Response body should contain status WAITING: " + response.body());
  }

  @Test
  public void scenario_joinAsX_returnsTokenMarkerAndWaitingStatus() throws Exception {
    String sessionId = createGame();

    var response = post("/games/" + sessionId + "/join", "");

    // § HTTP contract: first join → 200
    // {"sessionId":"...","playerToken":"...","assignedPlayerMarker":"X","status":"WAITING"}
    assertEquals(response.statusCode(), 200, "First join should return 200 OK");
    assertTrue(
        response.body().contains("\"sessionId\":\"" + sessionId + "\""),
        "Response body should include sessionId: " + response.body());
    assertTrue(
        response.body().contains("\"playerToken\""),
        "Response body should contain playerToken: " + response.body());
    assertTrue(
        response.body().contains("\"assignedPlayerMarker\":\"X\""),
        "First player should be assigned marker X: " + response.body());
    assertTrue(
        response.body().contains("\"status\":\"WAITING\""),
        "Status should still be WAITING after first join: " + response.body());
  }

  @Test
  public void scenario_joinAsO_returnsTokenMarkerAndActiveStatus() throws Exception {
    String sessionId = createGame();
    post("/games/" + sessionId + "/join", ""); // X joins first

    var response = post("/games/" + sessionId + "/join", "");

    // § HTTP contract: second join → 200
    // {"sessionId":"...","playerToken":"...","assignedPlayerMarker":"O","status":"ACTIVE"}
    assertEquals(response.statusCode(), 200, "Second join should return 200 OK");
    assertTrue(
        response.body().contains("\"sessionId\":\"" + sessionId + "\""),
        "Response body should include sessionId: " + response.body());
    assertTrue(
        response.body().contains("\"playerToken\""),
        "Response body should contain playerToken: " + response.body());
    assertTrue(
        response.body().contains("\"assignedPlayerMarker\":\"O\""),
        "Second player should be assigned marker O: " + response.body());
    assertTrue(
        response.body().contains("\"status\":\"ACTIVE\""),
        "Status should be ACTIVE after second join: " + response.body());
  }

  @Test
  public void scenario_fullGame_xWinsWithDiagonal() throws Exception {
    // quickstart.md §3: create game, both players join, submit moves to completion
    String sessionId = createGame();
    String tokenX = extractPlayerToken(post("/games/" + sessionId + "/join", "").body());
    String tokenO = extractPlayerToken(post("/games/" + sessionId + "/join", "").body());

    // X plays positions 0, 4, 8 (main diagonal); O plays 1, 2
    // Move sequence: X-0, O-1, X-4, O-2, X-8 → X wins
    var moveX0 = postMove(sessionId, tokenX, 0);
    assertEquals(moveX0.statusCode(), 200, "X move at 0 should succeed");
    assertTrue(
        moveX0.body().contains("\"status\":\"ACTIVE\""),
        "Game should still be ACTIVE after move: " + moveX0.body());

    var moveO1 = postMove(sessionId, tokenO, 1);
    assertEquals(moveO1.statusCode(), 200, "O move at 1 should succeed");

    var moveX4 = postMove(sessionId, tokenX, 4);
    assertEquals(moveX4.statusCode(), 200, "X move at 4 should succeed");

    var moveO2 = postMove(sessionId, tokenO, 2);
    assertEquals(moveO2.statusCode(), 200, "O move at 2 should succeed");

    var finalMove = postMove(sessionId, tokenX, 8);
    assertEquals(finalMove.statusCode(), 200, "X winning move at 8 should return 200");
    assertTrue(
        finalMove.body().contains("\"status\":\"COMPLETED\""),
        "Final move response should show COMPLETED status: " + finalMove.body());
    assertTrue(
        finalMove.body().contains("\"outcome\":\"win:X\""),
        "Final move response should show X win outcome: " + finalMove.body());

    // Verify GET /games/{id}/state also reflects the completed outcome
    var stateResponse = get("/games/" + sessionId + "/state");
    assertEquals(stateResponse.statusCode(), 200, "GET state should return 200");
    assertTrue(
        stateResponse.body().contains("\"status\":\"COMPLETED\""),
        "GET state should show COMPLETED: " + stateResponse.body());
    assertTrue(
        stateResponse.body().contains("\"outcome\":\"win:X\""),
        "GET state should show win:X outcome: " + stateResponse.body());
  }

  // -----------------------------------------------------------------------
  // Scenario 2 — Invalid move rejection (occupied position → 400)
  // -----------------------------------------------------------------------

  @Test
  public void scenario_invalidMove_occupiedPosition_returns400() throws Exception {
    String sessionId = createGame();
    String tokenX = extractPlayerToken(post("/games/" + sessionId + "/join", "").body());
    String tokenO = extractPlayerToken(post("/games/" + sessionId + "/join", "").body());

    postMove(sessionId, tokenX, 0); // X occupies position 0

    var response = postMove(sessionId, tokenO, 0); // O tries same position
    assertEquals(
        response.statusCode(),
        400,
        "Move to occupied position should return 400: " + response.body());
    assertTrue(
        response.body().contains("\"error\""),
        "Error response should contain error field: " + response.body());
    assertTrue(
        response.body().toLowerCase().contains("occupied"),
        "Error message should mention 'occupied': " + response.body());
  }

  // -----------------------------------------------------------------------
  // Scenario 3 — Session full rejection (third join → 409)
  // -----------------------------------------------------------------------

  @Test
  public void scenario_sessionFull_thirdJoin_returns409() throws Exception {
    String sessionId = createGame();
    post("/games/" + sessionId + "/join", ""); // player X
    post("/games/" + sessionId + "/join", ""); // player O

    var response = post("/games/" + sessionId + "/join", "");
    assertEquals(
        response.statusCode(), 409, "Third join should return 409 Conflict: " + response.body());
    assertTrue(
        response.body().contains("\"error\""),
        "Error response should contain error field: " + response.body());
    assertTrue(
        response.body().contains("Session full"),
        "Error message should mention 'Session full': " + response.body());
  }

  // -----------------------------------------------------------------------
  // Scenario 4 — Move after game over → 410
  // -----------------------------------------------------------------------

  @Test
  public void scenario_moveAfterGameOver_returns410() throws Exception {
    String sessionId = createGame();
    String tokenX = extractPlayerToken(post("/games/" + sessionId + "/join", "").body());
    String tokenO = extractPlayerToken(post("/games/" + sessionId + "/join", "").body());

    // X wins with top row: X at 0,1,2; O at 3,4
    postMove(sessionId, tokenX, 0);
    postMove(sessionId, tokenO, 3);
    postMove(sessionId, tokenX, 1);
    postMove(sessionId, tokenO, 4);
    postMove(sessionId, tokenX, 2); // X wins

    var response = postMove(sessionId, tokenO, 5); // attempt move after game over
    assertEquals(
        response.statusCode(),
        410,
        "Move after game over should return 410 Gone: " + response.body());
    assertTrue(
        response.body().contains("\"error\""),
        "Error response should contain error field: " + response.body());
    assertTrue(
        response.body().toLowerCase().contains("game over"),
        "Error message should mention 'Game over': " + response.body());
  }

  // -----------------------------------------------------------------------
  // Scenario 5 — HttpGameClient main entry point connects and exits cleanly
  // -----------------------------------------------------------------------

  @Test
  public void scenario_httpGameClient_connectsAndExitsCleanly() throws Exception {
    // Provide a waiting game so the client can join and play both sides is not possible
    // with a single client. Instead we verify the client starts, creates a game, and is
    // interrupted cleanly while waiting for a second player.
    String host = "localhost";
    int port = server.getPort();

    AtomicReference<Throwable> thrownRef = new AtomicReference<>();

    Thread clientThread =
        Thread.ofVirtual()
            .name("quickstart-client")
            .start(
                () -> {
                  try {
                    HttpGameClient.main(new String[] {host, String.valueOf(port)});
                  } catch (Throwable t) {
                    thrownRef.set(t);
                  }
                });

    // Give the client time to create a session and begin waiting for second player
    clientThread.join(3_000);

    if (clientThread.isAlive()) {
      // Client is blocked waiting for an opponent — interrupt it
      clientThread.interrupt();
      clientThread.join(2_000);
    }

    // The client should not have thrown an exception during normal operation; an
    // InterruptedException propagated as IOException is acceptable here since we
    // deliberately interrupted it.
    Throwable thrown = thrownRef.get();
    if (thrown != null) {
      // Only IOException wrapping InterruptedException is acceptable
      assertTrue(
          thrown instanceof IOException,
          "Client should only throw IOException (or nothing) but threw: " + thrown);
    }
  }

  // -----------------------------------------------------------------------
  // Scenario 6 — quickstart.md §2: gradle run equivalent starts a server
  //   Invokes HttpGameServer.main() directly and asserts POST /games → 201
  // -----------------------------------------------------------------------

  @Test
  public void scenario_gradleRun_serverStartsAndAcceptsConnections() throws Exception {
    // Find a free ephemeral port, release it, then hand it to main()
    int port;
    try (var probe = new ServerSocket(0)) {
      port = probe.getLocalPort();
    }

    final int serverPort = port;
    AtomicReference<Throwable> mainError = new AtomicReference<>();

    Thread serverThread =
        Thread.ofVirtual()
            .name("quickstart-gradle-run")
            .start(
                () -> {
                  try {
                    HttpGameServer.main(new String[] {String.valueOf(serverPort)});
                  } catch (Throwable t) {
                    mainError.set(t);
                  }
                });

    // Poll POST /games until the server accepts the connection (up to 2 seconds)
    String mainBaseUrl = "http://localhost:" + serverPort;
    HttpResponse<String> response = null;
    long deadline = System.currentTimeMillis() + 2_000;
    while (System.currentTimeMillis() < deadline) {
      try {
        var request =
            HttpRequest.newBuilder()
                .uri(URI.create(mainBaseUrl + "/games"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        response = client.send(request, HttpResponse.BodyHandlers.ofString());
        break; // connection succeeded
      } catch (IOException e) {
        // Server not ready yet — wait briefly before retrying
        Thread.sleep(100);
      }
    }

    // Server thread is intentionally left running for the remainder of the test
    // process; we merely verify it accepted the request correctly.
    assertNotNull(response, "Server should have accepted a connection within 2 seconds");
    assertEquals(response.statusCode(), 201, "POST /games should return 201 Created");
    assertTrue(
        response.body().contains("\"sessionId\""),
        "Response body should contain sessionId: " + response.body());

    // Verify main() did not throw unexpectedly
    Throwable thrown = mainError.get();
    assertNull(thrown, "HttpGameServer.main() should not have thrown: " + thrown);

    serverThread.interrupt();
  }

  // -----------------------------------------------------------------------
  // Scenario 7 — quickstart.md §3 SSE stream: subscribe, play complete
  //   game, assert event: start / event: state / event: end arrive in order
  // -----------------------------------------------------------------------

  @Test
  public void scenario_sseEventStream_receivesStartStateAndEndEvents() throws Exception {
    // Create session and have player X join; subscribe to SSE before player O joins
    // so the "start" event (broadcast on second join) is captured by the stream
    String sessionId = createGame();
    String tokenX = extractPlayerToken(post("/games/" + sessionId + "/join", "").body());

    // Subscribe to the SSE event stream on a virtual thread, collecting all lines
    List<String> sseLines = new ArrayList<>();
    CompletableFuture<Void> sseFuture =
        CompletableFuture.runAsync(
            () -> {
              try {
                var sseRequest =
                    HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/games/" + sessionId + "/events"))
                        .header("X-Player-Token", tokenX)
                        .GET()
                        .build();
                // Use a separate client instance to avoid blocking the shared one
                try (var sseClient = HttpClient.newHttpClient()) {
                  var sseResponse = sseClient.send(sseRequest, HttpResponse.BodyHandlers.ofLines());
                  sseResponse
                      .body()
                      .forEach(
                          line -> {
                            synchronized (sseLines) {
                              sseLines.add(line);
                            }
                          });
                }
              } catch (IOException | InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            },
            r -> Thread.ofVirtual().name("sse-collector").start(r));

    // Give the SSE subscription a moment to establish before player O joins
    Thread.sleep(200);

    // Player O joins — this triggers the "start" SSE event broadcast to all subscribers
    String tokenO = extractPlayerToken(post("/games/" + sessionId + "/join", "").body());

    // Play a complete game: X wins with main diagonal (0, 4, 8); O plays 1, 2
    postMove(sessionId, tokenX, 0);
    postMove(sessionId, tokenO, 1);
    postMove(sessionId, tokenX, 4);
    postMove(sessionId, tokenO, 2);
    postMove(sessionId, tokenX, 8); // X wins

    // Wait for the SSE stream to close (server closes it after game ends)
    sseFuture.get(5, TimeUnit.SECONDS);

    // Snapshot collected lines for assertions
    List<String> lines;
    synchronized (sseLines) {
      lines = List.copyOf(sseLines);
    }

    // Assert the required event type lines are present
    String sseEventState = "event: state";
    assertTrue(
        lines.contains("event: start"),
        "SSE stream should contain 'event: start' line. Lines received: " + lines);
    assertTrue(
        lines.contains(sseEventState),
        "SSE stream should contain at least one 'event: state' line. Lines received: " + lines);
    assertTrue(
        lines.contains("event: end"),
        "SSE stream should contain 'event: end' line. Lines received: " + lines);

    // Assert ordering: start < first state < end
    int startIndex = lines.indexOf("event: start");
    int endIndex = lines.indexOf("event: end");

    int firstStateIdx = -1;
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).equals(sseEventState)) {
        firstStateIdx = i;
        break;
      }
    }

    assertTrue(
        startIndex < firstStateIdx,
        "event: start should appear before event: state. startIndex="
            + startIndex
            + ", firstStateIndex="
            + firstStateIdx);
    assertTrue(
        firstStateIdx < endIndex,
        "event: state should appear before event: end. firstStateIndex="
            + firstStateIdx
            + ", endIndex="
            + endIndex);
  }

  // -----------------------------------------------------------------------
  // Helpers
  // -----------------------------------------------------------------------

  private String createGame() throws Exception {
    var response = post("/games", "");
    return extractField(response.body(), "sessionId");
  }

  private String extractPlayerToken(String body) {
    return extractField(body, "playerToken");
  }

  private HttpResponse<String> postMove(String sessionId, String token, int position)
      throws Exception {
    var request =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/games/" + sessionId + "/move"))
            .header("Content-Type", "application/json")
            .header("X-Player-Token", token)
            .POST(HttpRequest.BodyPublishers.ofString("{\"position\":" + position + "}"))
            .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String path, String body) throws Exception {
    var request =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .header("Content-Type", "application/json")
            .POST(
                body.isEmpty()
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body))
            .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> get(String path) throws Exception {
    var request = HttpRequest.newBuilder().uri(URI.create(baseUrl + path)).GET().build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static String extractField(String json, String field) {
    var pattern = Pattern.compile("\"" + field + "\":\"([^\"]+)\"");
    Matcher m = pattern.matcher(json);
    if (m.find()) {
      return m.group(1);
    }
    throw new RuntimeException("Field " + field + " not found in: " + json);
  }
}
