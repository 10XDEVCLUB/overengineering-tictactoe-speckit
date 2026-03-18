package org.xxdc.oss.example;

import static org.testng.Assert.*;

import java.io.IOException;
import java.net.http.HttpClient;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import org.xxdc.oss.example.transport.http.HttpTransportClient;

/**
 * Tests the HTTP game client with HTTP/3 preference (fallback to HTTP/2 on JDK 25), verifying
 * protocol version negotiation and game completion.
 */
public class HttpGameClientTest {

  private HttpGameServer server;
  private int port;

  @BeforeMethod
  public void setUp() throws IOException {
    server = new HttpGameServer(0);
    server.start();
    port = server.getPort();
  }

  @AfterMethod
  public void tearDown() {
    server.stop();
  }

  @Test
  public void clientConnectsAndCompletesGame() throws IOException {
    try (var client1 = new HttpTransportClient("localhost", port);
        var client2 = new HttpTransportClient("localhost", port)) {

      // Both clients create/join same session
      var sessionId = client1.createGame();
      var join1 = client1.joinGame(sessionId);
      var join2 = client2.joinGame(sessionId);

      assertEquals(join1.marker(), "X");
      assertEquals(join2.marker(), "O");
      assertEquals(join2.status(), "ACTIVE");

      // Play a full game — X wins with top row (0, 1, 2)
      client1.submitMove(sessionId, join1.playerToken(), 0); // X
      client2.submitMove(sessionId, join2.playerToken(), 3); // O
      client1.submitMove(sessionId, join1.playerToken(), 1); // X
      client2.submitMove(sessionId, join2.playerToken(), 4); // O
      String finalMove = client1.submitMove(sessionId, join1.playerToken(), 2); // X wins

      assertTrue(finalMove.contains("\"status\":\"COMPLETED\""));
      assertTrue(finalMove.contains("\"outcome\":\"win:X\""));
    }
  }

  @Test
  public void clientReportsRequestedVersion() throws IOException {
    try (var client = new HttpTransportClient("localhost", port)) {
      // On JDK 25, HTTP_3 is not available — should fall back to HTTP_2
      var version = client.requestedVersion();
      assertNotNull(version);
      // Either HTTP_2 (JDK 25) or HTTP_3 (JDK 26+) is acceptable
      assertTrue(
          version == HttpClient.Version.HTTP_2 || version.name().equals("HTTP_3"),
          "Expected HTTP_2 or HTTP_3 but got: " + version);
    }
  }

  @Test
  public void clientRetrievesGameState() throws IOException {
    try (var client = new HttpTransportClient("localhost", port)) {
      var sessionId = client.createGame();
      client.joinGame(sessionId);
      client.joinGame(sessionId);

      String state = client.getState(sessionId);
      assertNotNull(state);
      assertTrue(state.contains("\"status\":\"ACTIVE\""));
    }
  }

  @Test
  public void clientHandlesInvalidMoveGracefully() throws IOException {
    try (var client1 = new HttpTransportClient("localhost", port);
        var client2 = new HttpTransportClient("localhost", port)) {

      var sessionId = client1.createGame();
      var join1 = client1.joinGame(sessionId);
      var join2 = client2.joinGame(sessionId);

      client1.submitMove(sessionId, join1.playerToken(), 0); // X at 0

      // O tries to take occupied position
      String result = client2.submitMove(sessionId, join2.playerToken(), 0);
      assertTrue(result.contains("occupied"));
    }
  }
}
