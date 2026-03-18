package org.xxdc.oss.example;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * JMH benchmarks for {@link HttpGameServer} measuring HTTP request throughput and move latency.
 *
 * <p>Benchmark 1 ({@link #fullGameRoundTrip}) measures end-to-end throughput of a complete game
 * session: create session → two players join → submit moves until X wins via the diagonal
 * (positions 0, 4, 8). Targets ≥100 complete game round-trips per second under localhost
 * conditions.
 *
 * <p>Benchmark 2 ({@link #singleMoveLatency}) measures the average time for a single POST to {@code
 * /games/{id}/move} on a pre-warmed session. A fresh session with two joined players is created
 * before each invocation so the server is always in a valid ACTIVE state. Targets ≤50ms average
 * move latency on localhost.
 *
 * <p>Run via: {@code ./gradlew :http-gameserver:jmh}
 */
@State(Scope.Benchmark)
public class HttpGameServerBenchmark {

  private HttpGameServer server;
  private HttpClient client;
  private String baseUrl;

  // Pre-warmed session state for singleMoveLatency benchmark
  private String preWarmedSessionId;
  private String preWarmedTokenX;
  private String preWarmedTokenO;

  /**
   * Starts the HTTP game server on an ephemeral port and creates a shared {@link HttpClient}.
   * Called once per benchmark trial (fork).
   */
  @Setup(Level.Trial)
  public void setUpTrial() throws IOException {
    server = new HttpGameServer(0); // ephemeral port — OS assigns a free port
    server.start();
    baseUrl = "http://localhost:" + server.getPort();
    client = HttpClient.newHttpClient();
  }

  /**
   * Stops the HTTP game server and closes the shared {@link HttpClient}. Called once per benchmark
   * trial after all iterations complete.
   */
  @TearDown(Level.Trial)
  public void tearDownTrial() {
    server.stop();
    client.close();
  }

  /**
   * Creates a fresh pre-warmed session with two joined players before each invocation of {@link
   * #singleMoveLatency}. Ensures the server is in ACTIVE state and ready to accept move #1 (X's
   * first move).
   *
   * <p>Using {@link Level#Invocation} here is appropriate because each invocation must start with a
   * fresh, unplayed ACTIVE session. The setup overhead is excluded from the latency measurement.
   */
  @Setup(Level.Invocation)
  public void setUpInvocation() throws Exception {
    preWarmedSessionId = createGame();
    preWarmedTokenX = joinGame(preWarmedSessionId);
    preWarmedTokenO = joinGame(preWarmedSessionId);
    // Allow game thread / session to reach ACTIVE state
    Thread.sleep(10);
  }

  /**
   * Benchmark 1: Full game round-trip throughput.
   *
   * <p>Measures the number of complete game sessions that can be executed per second. Each
   * invocation: creates a new session, joins as both X and O, then submits moves in alternating
   * order until X wins via the top-left to bottom-right diagonal (positions 0, 4, 8):
   *
   * <pre>
   *   X | O | O
   *   - + - + -
   *     | X |
   *   - + - + -
   *     |   | X
   * </pre>
   *
   * <p>Targets: ≥100 complete game round-trips per second on localhost.
   *
   * @throws Exception if any HTTP request fails
   */
  @Benchmark
  @BenchmarkMode(Mode.Throughput)
  @OutputTimeUnit(TimeUnit.SECONDS)
  public void fullGameRoundTrip() throws Exception {
    String sessionId = createGame();
    String tokenX = joinGame(sessionId);
    String tokenO = joinGame(sessionId);

    // Allow session to become ACTIVE
    Thread.sleep(10);

    // X wins diagonal: 0, 4, 8
    // Move sequence:  X@0, O@1, X@4, O@2, X@8
    postMove(sessionId, tokenX, 0);
    postMove(sessionId, tokenO, 1);
    postMove(sessionId, tokenX, 4);
    postMove(sessionId, tokenO, 2);
    postMove(sessionId, tokenX, 8); // X wins
  }

  /**
   * Benchmark 2: Single move latency.
   *
   * <p>Measures the average latency of one POST to {@code /games/{id}/move} on a pre-warmed, ACTIVE
   * session. The session is freshly created and both players joined in the {@link Level#Invocation}
   * setup phase, so the overhead of session setup is not included.
   *
   * <p>Targets: ≤50ms average latency on localhost.
   *
   * @throws Exception if the HTTP request fails
   */
  @Benchmark
  @BenchmarkMode(Mode.AverageTime)
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  public HttpResponse<String> singleMoveLatency() throws Exception {
    return postMove(preWarmedSessionId, preWarmedTokenX, 0);
  }

  // -- HTTP helpers (JDK built-in only) --

  private String createGame() throws Exception {
    var response = post("/games", "");
    return extractField(response.body(), "sessionId");
  }

  private String joinGame(String sessionId) throws Exception {
    var response = post("/games/" + sessionId + "/join", "");
    return extractField(response.body(), "playerToken");
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

  private static String extractField(String json, String field) {
    var pattern = Pattern.compile("\"" + field + "\":\"([^\"]+)\"");
    Matcher m = pattern.matcher(json);
    if (m.find()) {
      return m.group(1);
    }
    throw new RuntimeException("Field " + field + " not found in: " + json);
  }
}
