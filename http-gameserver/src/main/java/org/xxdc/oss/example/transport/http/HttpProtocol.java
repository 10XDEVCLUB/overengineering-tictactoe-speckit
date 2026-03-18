package org.xxdc.oss.example.transport.http;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.xxdc.oss.example.GameBoard;
import org.xxdc.oss.example.GameState;

/**
 * Provides JSON serialization and deserialization for the HTTP game protocol.
 *
 * <p>The wire format mirrors the TCP protocol ({@code TcpProtocol}) for consistency (FR-012), using
 * version-tagged JSON messages with board state, player markers, and current player index.
 *
 * <p>This class uses the JDK built-in {@link Pattern} and {@link Matcher} for JSON parsing rather
 * than external libraries, satisfying Principle X (Dependency Minimalism).
 */
public final class HttpProtocol {

  private HttpProtocol() {}

  // -- Game State JSON --

  private static final String GAME_STATE_JSON_FORMAT =
      "{\"version\":1,\"sessionId\":\"%s\",\"status\":\"%s\","
          + "\"board\":[%s],\"dimension\":%d,"
          + "\"playerMarkers\":[%s],\"currentPlayerIndex\":%d,"
          + "\"outcome\":%s}";

  private static final Pattern GAME_STATE_JSON_PATTERN =
      Pattern.compile(
          "\\{\"version\":(\\d+),\"sessionId\":\"([^\"]+)\",\"status\":\"([^\"]+)\","
              + "\"board\":\\[([^\\]]*)\\],\"dimension\":(\\d+),"
              + "\"playerMarkers\":\\[([^\\]]*)\\],\"currentPlayerIndex\":(\\d+),"
              + "\"outcome\":(null|\"[^\"]*\")\\}");

  // -- Move Request JSON --

  private static final Pattern MOVE_REQUEST_PATTERN = Pattern.compile("\\{\"position\":(\\d+)\\}");

  // -- Session Response JSON --

  private static final String SESSION_CREATED_FORMAT = "{\"sessionId\":\"%s\",\"status\":\"%s\"}";

  private static final String JOIN_RESPONSE_FORMAT =
      "{\"sessionId\":\"%s\",\"playerToken\":\"%s\","
          + "\"assignedPlayerMarker\":\"%s\",\"status\":\"%s\"}";

  private static final String ERROR_FORMAT = "{\"error\":\"%s\"}";

  private static final String ERROR_WITH_OUTCOME_FORMAT = "{\"error\":\"%s\",\"outcome\":\"%s\"}";

  // -- SSE Event Format --

  private static final String SSE_EVENT_FORMAT = "event: %s\ndata: %s\n\n";

  /**
   * Serializes a game state into the HTTP protocol JSON format.
   *
   * @param sessionId the session this state belongs to
   * @param status the current session status
   * @param state the game state to serialize
   * @param outcome the game outcome (null if in progress)
   * @return JSON string
   */
  public static String toGameStateJson(
      UUID sessionId, SessionStatus status, GameState state, String outcome) {
    String boardJson = toBoardJson(state.board());
    String markersJson = toMarkersJson(state.playerMarkers());
    String outcomeJson = outcome == null ? "null" : "\"" + outcome + "\"";
    return GAME_STATE_JSON_FORMAT.formatted(
        sessionId,
        status.label(),
        boardJson,
        state.board().dimension(),
        markersJson,
        state.currentPlayerIndex(),
        outcomeJson);
  }

  /**
   * Parses a game state from the HTTP protocol JSON format.
   *
   * @param json the JSON string to parse
   * @return the parsed game state, or empty if the format is invalid
   */
  public static Optional<GameState> fromGameStateJson(String json) {
    Matcher matcher = GAME_STATE_JSON_PATTERN.matcher(json);
    if (!matcher.matches()) {
      return Optional.empty();
    }
    String[] playerMarkers = matcher.group(6).replace("\"", "").split(",");
    int currentPlayerIndex = Integer.parseInt(matcher.group(7));
    int dimension = Integer.parseInt(matcher.group(5));
    var board = GameBoard.withDimension(dimension);
    String[] rawContent = matcher.group(4).split(",");
    for (int i = 0; i < rawContent.length; i++) {
      String cell = rawContent[i].trim();
      if (!cell.equals("null")) {
        board = board.withMove(cell.replace("\"", ""), i);
      }
    }
    return Optional.of(new GameState(board, List.of(playerMarkers), currentPlayerIndex));
  }

  /**
   * Parses a move position from a move request JSON body.
   *
   * @param json the JSON request body (e.g., {@code {"position":4}})
   * @return the board position, or empty if invalid
   */
  public static Optional<Integer> fromMoveRequestJson(String json) {
    Matcher matcher = MOVE_REQUEST_PATTERN.matcher(json.trim());
    if (!matcher.matches()) {
      return Optional.empty();
    }
    return Optional.of(Integer.parseInt(matcher.group(1)));
  }

  /** Formats a session-created response. */
  public static String toSessionCreatedJson(UUID sessionId, SessionStatus status) {
    return SESSION_CREATED_FORMAT.formatted(sessionId, status.label());
  }

  /** Formats a join-session response with the assigned player token and marker. */
  public static String toJoinResponseJson(
      UUID sessionId, PlayerToken token, String marker, SessionStatus status) {
    return JOIN_RESPONSE_FORMAT.formatted(sessionId, token.value(), marker, status.label());
  }

  /** Formats an error response. */
  public static String toErrorJson(String message) {
    return ERROR_FORMAT.formatted(message);
  }

  /** Formats an error response with a game outcome. */
  public static String toErrorWithOutcomeJson(String message, String outcome) {
    return ERROR_WITH_OUTCOME_FORMAT.formatted(message, outcome);
  }

  /** Formats a Server-Sent Event frame. */
  public static String toSseEvent(String eventType, String data) {
    return SSE_EVENT_FORMAT.formatted(eventType, data);
  }

  // -- Internal helpers --

  private static String toBoardJson(GameBoard board) {
    var sb = new StringBuilder();
    String[] content = board.content();
    for (int i = 0; i < content.length; i++) {
      if (i > 0) sb.append(",");
      sb.append(content[i] == null ? "null" : "\"" + content[i] + "\"");
    }
    return sb.toString();
  }

  private static String toMarkersJson(List<String> markers) {
    var sb = new StringBuilder();
    for (int i = 0; i < markers.size(); i++) {
      if (i > 0) sb.append(",");
      sb.append("\"").append(markers.get(i)).append("\"");
    }
    return sb.toString();
  }
}
