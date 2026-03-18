package org.xxdc.oss.example.transport.http;

import static org.testng.Assert.*;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/** Tests session creation, player join, state transitions, move validation, and rejection. */
public class GameSessionManagerTest {

  private GameSessionManager manager;

  @BeforeMethod
  public void setUp() {
    manager = new GameSessionManager();
  }

  @Test
  public void createSessionReturnsWaitingStatus() {
    var session = manager.createSession();
    assertNotNull(session.id());
    assertTrue(session.status() instanceof SessionStatus.Waiting);
    assertTrue(session.playerTokens().isEmpty());
  }

  @Test
  public void firstPlayerJoinKeepsWaiting() {
    var session = manager.createSession();
    var result = manager.joinSession(session.id());
    assertEquals(result.marker(), "X");
    assertNotNull(result.token());
    assertTrue(result.session().status() instanceof SessionStatus.Waiting);
    assertEquals(result.session().playerTokens().size(), 1);
  }

  @Test
  public void secondPlayerJoinActivatesSession() {
    var session = manager.createSession();
    manager.joinSession(session.id());
    var result2 = manager.joinSession(session.id());
    assertEquals(result2.marker(), "O");
    assertTrue(result2.session().status() instanceof SessionStatus.Active);
    assertEquals(result2.session().playerTokens().size(), 2);
    assertNotNull(result2.session().gameState());
  }

  @Test(
      expectedExceptions = IllegalStateException.class,
      expectedExceptionsMessageRegExp = "Session full")
  public void thirdPlayerJoinRejected() {
    var session = manager.createSession();
    manager.joinSession(session.id());
    manager.joinSession(session.id());
    manager.joinSession(session.id()); // should throw
  }

  @Test(
      expectedExceptions = IllegalStateException.class,
      expectedExceptionsMessageRegExp = "Session not found.*")
  public void joinNonexistentSessionThrows() {
    manager.joinSession(java.util.UUID.randomUUID());
  }

  @Test
  public void submitValidMove() {
    var session = manager.createSession();
    var p1 = manager.joinSession(session.id());
    var p2 = manager.joinSession(session.id());

    // X moves first (currentPlayerIndex=0)
    var updated = manager.submitMove(session.id(), p1.token(), 4);
    assertTrue(updated.status() instanceof SessionStatus.Active);
    assertEquals(updated.gameState().currentPlayerIndex(), 1);
  }

  @Test(
      expectedExceptions = IllegalStateException.class,
      expectedExceptionsMessageRegExp = "Not your turn")
  public void submitMoveWrongTurnRejected() {
    var session = manager.createSession();
    var p1 = manager.joinSession(session.id());
    var p2 = manager.joinSession(session.id());

    // O tries to move when it's X's turn
    manager.submitMove(session.id(), p2.token(), 0);
  }

  @Test(
      expectedExceptions = IllegalStateException.class,
      expectedExceptionsMessageRegExp = ".*occupied.*")
  public void submitMoveOccupiedCellRejected() {
    var session = manager.createSession();
    var p1 = manager.joinSession(session.id());
    var p2 = manager.joinSession(session.id());

    manager.submitMove(session.id(), p1.token(), 4); // X at 4
    manager.submitMove(session.id(), p2.token(), 4); // O at 4 — occupied
  }

  @Test
  public void gameCompletesWithWin() {
    var session = manager.createSession();
    var p1 = manager.joinSession(session.id());
    var p2 = manager.joinSession(session.id());

    // X wins with top row: 0, 1, 2
    manager.submitMove(session.id(), p1.token(), 0); // X
    manager.submitMove(session.id(), p2.token(), 3); // O
    manager.submitMove(session.id(), p1.token(), 1); // X
    manager.submitMove(session.id(), p2.token(), 4); // O
    var final_ = manager.submitMove(session.id(), p1.token(), 2); // X wins

    assertTrue(final_.status() instanceof SessionStatus.Completed);
    assertEquals(((SessionStatus.Completed) final_.status()).outcome(), "win:X");
  }

  @Test(
      expectedExceptions = IllegalStateException.class,
      expectedExceptionsMessageRegExp = "Game over.*")
  public void submitMoveAfterGameOverRejected() {
    var session = manager.createSession();
    var p1 = manager.joinSession(session.id());
    var p2 = manager.joinSession(session.id());

    manager.submitMove(session.id(), p1.token(), 0);
    manager.submitMove(session.id(), p2.token(), 3);
    manager.submitMove(session.id(), p1.token(), 1);
    manager.submitMove(session.id(), p2.token(), 4);
    manager.submitMove(session.id(), p1.token(), 2); // X wins

    manager.submitMove(session.id(), p2.token(), 5); // should throw
  }
}
