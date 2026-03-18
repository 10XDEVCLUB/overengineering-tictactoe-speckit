package org.xxdc.oss.example.transport.http;

import static org.testng.Assert.*;

import java.util.List;
import java.util.UUID;
import org.testng.annotations.Test;
import org.xxdc.oss.example.GameBoard;
import org.xxdc.oss.example.GameState;

/**
 * Validates JSON round-trip for game state, move request, error responses, and SSE event
 * formatting.
 */
public class HttpProtocolTest {

  @Test
  public void gameStateJsonRoundTrip() {
    var board = GameBoard.withDimension(3).withMove("X", 0).withMove("O", 4);
    var state = new GameState(board, List.of("X", "O"), 0);
    var sessionId = UUID.randomUUID();
    var status = new SessionStatus.Active();

    String json = HttpProtocol.toGameStateJson(sessionId, status, state, null);

    assertTrue(json.contains("\"version\":1"));
    assertTrue(json.contains("\"sessionId\":\"" + sessionId + "\""));
    assertTrue(json.contains("\"status\":\"ACTIVE\""));
    assertTrue(json.contains("\"dimension\":3"));
    assertTrue(json.contains("\"outcome\":null"));

    var parsed = HttpProtocol.fromGameStateJson(json);
    assertTrue(parsed.isPresent());
    var roundTripped = parsed.get();
    assertEquals(roundTripped.currentPlayerIndex(), 0);
    assertEquals(roundTripped.playerMarkers(), List.of("X", "O"));
    assertEquals(roundTripped.board().dimension(), 3);
  }

  @Test
  public void gameStateJsonWithOutcome() {
    var board = GameBoard.withDimension(3).withMove("X", 0).withMove("X", 1).withMove("X", 2);
    var state = new GameState(board, List.of("X", "O"), 1);
    var sessionId = UUID.randomUUID();
    var status = new SessionStatus.Completed("win:X");

    String json = HttpProtocol.toGameStateJson(sessionId, status, state, "win:X");

    assertTrue(json.contains("\"outcome\":\"win:X\""));
    assertTrue(json.contains("\"status\":\"COMPLETED\""));
  }

  @Test
  public void moveRequestParsing() {
    var pos = HttpProtocol.fromMoveRequestJson("{\"position\":4}");
    assertTrue(pos.isPresent());
    assertEquals(pos.get().intValue(), 4);
  }

  @Test
  public void moveRequestParsingInvalid() {
    var pos = HttpProtocol.fromMoveRequestJson("not json");
    assertTrue(pos.isEmpty());
  }

  @Test
  public void sessionCreatedJson() {
    var sessionId = UUID.randomUUID();
    var json = HttpProtocol.toSessionCreatedJson(sessionId, new SessionStatus.Waiting());
    assertTrue(json.contains("\"sessionId\":\"" + sessionId + "\""));
    assertTrue(json.contains("\"status\":\"WAITING\""));
  }

  @Test
  public void joinResponseJson() {
    var sessionId = UUID.randomUUID();
    var token = PlayerToken.generate();
    var json = HttpProtocol.toJoinResponseJson(sessionId, token, "X", new SessionStatus.Waiting());
    assertTrue(json.contains("\"playerToken\":\"" + token.value() + "\""));
    assertTrue(json.contains("\"assignedPlayerMarker\":\"X\""));
  }

  @Test
  public void errorJson() {
    var json = HttpProtocol.toErrorJson("Position 4 is already occupied");
    assertEquals(json, "{\"error\":\"Position 4 is already occupied\"}");
  }

  @Test
  public void errorWithOutcomeJson() {
    var json = HttpProtocol.toErrorWithOutcomeJson("Game over", "win:X");
    assertTrue(json.contains("\"error\":\"Game over\""));
    assertTrue(json.contains("\"outcome\":\"win:X\""));
  }

  @Test
  public void sseEventFormat() {
    var event = HttpProtocol.toSseEvent("state", "{\"version\":1}");
    assertEquals(event, "event: state\ndata: {\"version\":1}\n\n");
  }
}
