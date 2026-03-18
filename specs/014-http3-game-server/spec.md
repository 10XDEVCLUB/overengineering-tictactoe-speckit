# Feature Specification: HTTP/3 Game Server & Client

**Feature Branch**: `014-http3-game-server`
**Created**: 2026-03-15
**Status**: Implemented
**Input**: User description: "HTTP/3 Game Client & Server demonstrating JEP 517 (HTTP/3 for the HTTP Client API, JDK 26) and JEP 408 (Simple Web Server)"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Play a Game Over HTTP (Priority: P1)

A player launches the HTTP game server and connects a game client over HTTP. Two clients join a game session, take turns submitting moves via HTTP requests, and receive game state updates via Server-Sent Events (SSE). The server pushes state changes to connected clients in real time. The game proceeds to completion (win or draw) entirely over the HTTP transport.

**Why this priority**: This is the foundational flow — without HTTP-based game play, no other stories are meaningful. It validates the new transport module end-to-end and demonstrates JEP 408 (Simple Web Server) on the server side.

**Independent Test**: Can be fully tested by starting the HTTP server, connecting two clients, and playing a complete game. Delivers a working HTTP-based multiplayer game as a standalone capability.

**Acceptance Scenarios**:

1. **Given** the HTTP game server is running, **When** two clients connect and submit alternating moves, **Then** the game progresses to a win or draw and both clients receive the final result.
2. **Given** the HTTP game server is running, **When** a client submits an invalid move (occupied cell or out-of-bounds), **Then** the server responds with an error and the game state remains unchanged.
3. **Given** the HTTP game server is running with no game in progress, **When** a single client connects, **Then** the server acknowledges the connection and waits for a second player before starting.

---

### User Story 2 - Connect Using HTTP/3 with Fallback (Priority: P2)

A client configured for HTTP/3 connects to the game server. If the server supports HTTP/3 (QUIC), the connection uses HTTP/3 transport. If HTTP/3 is unavailable, the client automatically falls back to HTTP/2 or HTTP/1.1 without user intervention.

**Why this priority**: This story demonstrates the core JEP 517 value — HTTP/3 opt-in with graceful degradation. It builds on the working HTTP transport from US1 and showcases QUIC-based communication and discovery/fallback strategies.

**Independent Test**: Can be tested by connecting an HTTP/3-configured client to both an HTTP/3-capable server and an HTTP/1.1-only server, verifying successful game play in both cases and confirming the negotiated protocol version.

**Acceptance Scenarios**:

1. **Given** a server that does not support HTTP/3 (JDK built-in `com.sun.net.httpserver` is HTTP/1.1 only per JEP 408), **When** a client connects with HTTP/3 preference, **Then** the client falls back transparently, reports the negotiated protocol as HTTP/1.1, and completes the game — demonstrating JEP 517 opt-in and fallback behaviour. Full HTTP/3 end-to-end (QUIC) is deferred until a JDK built-in HTTP/3 server is available.
2. **Given** an HTTP/1.1-only server, **When** a client connects with HTTP/3 preference, **Then** the client falls back to HTTP/1.1 transparently and the game completes successfully.
3. **Given** a client configured for HTTP/3-only mode, **When** the server does not support HTTP/3, **Then** the client reports a clear connection failure with a meaningful message.

---

### User Story 3 - Run Concurrent Games Over HTTP (Priority: P3)

Multiple pairs of players connect to the HTTP game server simultaneously. Each pair plays an independent game session. The server handles all concurrent sessions without interference between games.

**Why this priority**: Validates that the HTTP transport supports concurrent multiplayer sessions, mirroring the existing TCP game server's concurrency model (virtual threads). Lower priority because concurrency is an enhancement over the basic single-game flow.

**Independent Test**: Can be tested by launching the server and connecting multiple client pairs concurrently, verifying each game completes independently with correct results.

**Acceptance Scenarios**:

1. **Given** the HTTP server is running, **When** 10 pairs of clients connect simultaneously, **Then** all 10 games complete independently without cross-game interference.
2. **Given** multiple concurrent games in progress, **When** one game encounters an error, **Then** other in-progress games are unaffected.

---

### Edge Cases

- What happens when a client disconnects mid-game? The server MUST clean up the abandoned session and free resources.
- What happens when the server reaches its maximum concurrent game capacity? New connection attempts MUST receive a clear "server full" response rather than hanging.
- What happens when a client sends a move for a game that has already ended? The server MUST respond with a "game over" status.
- What happens when network latency causes a move submission timeout? The client MUST retry or report the failure to the user.
- What happens when the SSE event stream disconnects unexpectedly? The client MUST reconnect and request the current game state to resynchronize.

## Clarifications

### Session 2026-03-15

- Q: How does a client learn about opponent moves and game state changes — polling, long-polling, or SSE? → A: Server-Sent Events (SSE) — server pushes state updates to clients via a persistent event stream.
- Q: SC-005 references Principle XI (Javadoc snippets) which does not exist in constitution v1.1.0 — keep or remove? → A: Remove SC-005 — the constitution amendment is on an unmerged branch.
- Q: How does the server identify Player X vs Player O across stateless HTTP requests? → A: Server-assigned player token returned at join time; client includes it in each move request.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST provide an HTTP-based game server that accepts client connections and manages game sessions over HTTP.
- **FR-002**: The system MUST expose endpoints for game creation, move submission, and game state retrieval. The server MUST push game state updates to connected clients via Server-Sent Events (SSE) rather than requiring clients to poll.
- **FR-003**: The system MUST implement the existing `TransportServer` interface over HTTP, maintaining compatibility with the project's transport abstraction.
- **FR-004**: The game client MUST support connecting with HTTP/3 preference using the JDK HTTP Client API's HTTP/3 opt-in mechanism.
- **FR-005**: The game client MUST fall back to HTTP/2 or HTTP/1.1 when HTTP/3 is unavailable, without requiring user configuration changes.
- **FR-006**: The server MUST handle multiple concurrent game sessions using one thread per connection.
- **FR-007**: The server MUST validate all incoming moves and reject invalid moves with descriptive error responses. Move requests MUST include a server-assigned player token for identity verification.
- **FR-008**: The system MUST use only JDK built-in APIs for both server and client — no external HTTP frameworks.
- **FR-009**: The system MUST exist as a new independently buildable and testable module within the project.
- **FR-010**: The game client MUST report the negotiated HTTP protocol version (HTTP/1.1, HTTP/2, or HTTP/3) for observability.
- **FR-011**: Each game session MUST track its state (waiting for players, in progress, completed) and expose it through the state retrieval endpoint.
- **FR-012**: The server MUST use the same JSON-based game state representation used by the existing TCP transport.

### Key Entities

- **Game Session**: Represents an active or completed game. Key attributes: unique session identifier, current game state, list of connected players, session status (waiting/active/completed), creation timestamp.
- **Player Token**: A server-assigned opaque token returned when a player joins a session. The client MUST include this token in every subsequent move request to identify itself. Key attributes: token value, associated session, assigned player marker.
- **HTTP Move Request**: A client-submitted move. Key attributes: session identifier, player token, target board position.
- **HTTP Game State Response**: The server's representation of current game state returned to clients. Key attributes: board layout, current player turn, game outcome (if finished).

## Assumptions

- The HTTP server binds to a configurable port (default: 8080) on localhost.
- Game state is held in memory — no persistent storage is required for game sessions.
- The JSON message format mirrors the existing TCP protocol's `TcpProtocol` structure for consistency.
- HTTP/3 server-side support depends on the JDK's built-in HTTP server capabilities in JDK 26; if the built-in server does not support HTTP/3 natively, the server operates on HTTP/1.1 while the client-side HTTP/3 demonstration targets an external HTTP/3-capable endpoint or a future JDK server upgrade.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Two players can complete a full game (9 moves maximum) over HTTP in under 5 seconds on localhost.
- **SC-002**: The server handles at least 10 concurrent game sessions without errors or cross-game interference.
- **SC-003**: An HTTP/3-configured client attempts HTTP/3, falls back to HTTP/1.1 against the built-in server, logs the negotiated protocol version (HTTP/1.1), and completes the game — demonstrating JEP 517 opt-in with graceful degradation.
- **SC-004**: When HTTP/3 is unavailable, the client falls back to HTTP/1.1 and completes the game without user intervention.
- **SC-006**: The module builds, tests, and generates Javadoc independently from other modules.
