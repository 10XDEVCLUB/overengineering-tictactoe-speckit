# Research: HTTP/3 Game Server & Client

**Feature**: 014-http3-game-server
**Date**: 2026-03-15

## R1: HTTP Server — JDK Built-in Options

**Decision**: Use `com.sun.net.httpserver.HttpServer` (JEP 408)

**Rationale**: The JDK ships a built-in HTTP server in the
`com.sun.net.httpserver` package (formalized in JEP 408 as the Simple Web
Server). It supports custom `HttpHandler` implementations, is sufficient for
REST-style endpoints, and requires zero external dependencies — satisfying
Principle X. The Simple Web Server CLI (`jwebserver`) is a convenience wrapper;
the programmatic API via `HttpServer.create()` is what we use.

**Alternatives considered**:
- *Javalin / Spark*: Lightweight frameworks but violate Principle X (external
  dependency with direct imports).
- *Spring Boot WebFlux*: Overkill and violates Principle X.
- *Raw `ServerSocket` with HTTP parsing*: Possible but reinvents HTTP; the
  JDK already provides a correct implementation.

## R2: SSE (Server-Sent Events) via Built-in HTTP Server

**Decision**: Implement SSE manually on top of `HttpHandler`

**Rationale**: `com.sun.net.httpserver` does not have native SSE support, but
SSE is a simple text-based protocol over HTTP/1.1. An `HttpHandler` can:
1. Set `Content-Type: text/event-stream` and `Transfer-Encoding: chunked`
2. Hold the `HttpExchange` output stream open
3. Write `data: {...}\n\n` frames as game state changes

This is straightforward to implement and aligns with the spec's SSE
requirement. The client side uses `HttpClient` to consume the event stream
via `HttpResponse.BodyHandlers.ofLines()` or similar streaming body handler.

**Alternatives considered**:
- *WebSocket*: Requires a WebSocket server implementation not present in the
  JDK built-in HTTP server. Would need an external library.
- *Polling*: Rejected in clarification — SSE was explicitly chosen.
- *Long-polling*: More complex than SSE for the same push semantics.

## R3: HTTP/3 Client — JEP 517

**Decision**: Use `HttpClient.Version.HTTP_3` opt-in (JEP 517, JDK 26)

**Rationale**: JEP 517 extends the existing `java.net.http.HttpClient` API
with HTTP/3 support. Opt-in is via `HttpClient.newBuilder().version(HTTP_3)`.
The implementation handles QUIC transport transparently. Four discovery
strategies are available:
1. Direct HTTP/3 attempt with fallback
2. Parallel connection attempts (HTTP/3 + HTTP/2/1.1)
3. Progressive discovery via Alt-Svc headers
4. HTTP/3-only mode for known servers

The client reports the negotiated version via `HttpResponse.version()`,
satisfying FR-010.

**Alternatives considered**:
- *Netty QUIC*: Violates Principle X.
- *HTTP/2 only*: Misses the primary JEP demonstration goal.

**Key constraint**: The JDK built-in `com.sun.net.httpserver` does NOT support
HTTP/3 on the server side (no QUIC). The server will operate on HTTP/1.1.
HTTP/3 client demonstration can be validated against external HTTP/3 endpoints
or by noting the fallback behavior when connecting to the HTTP/1.1 game server.
This is explicitly acknowledged in the spec assumptions.

## R4: TransportServer Interface Compatibility

**Decision**: `HttpTransportServer` implements `TransportServer` with
HTTP-specific adaptations

**Rationale**: The existing `TransportServer` interface has three methods:
- `initialize(TransportConfiguration config)` — assign player marker
- `send(GameState state)` — push game state to the player
- `accept()` — receive a move (board position) from the player

The TCP implementation maps these to socket read/write operations. The HTTP
implementation maps them to:
- `initialize` → register the player in a session, assign token, open SSE stream
- `send` → write SSE event with JSON game state to the player's stream
- `accept` → block (via virtual thread) until the player submits a move via
  POST request, then return the board position

This preserves the existing `Game` class integration — `PlayerNode.Remote`
wraps a `TransportServer` and calls `send`/`accept` in the game loop.

**Alternatives considered**:
- *New interface*: Unnecessary — the existing abstraction fits HTTP semantics
  with the blocking `accept()` pattern handled by virtual threads.
- *Async/callback model*: Would require changes to the `Game` class; the
  blocking model with virtual threads is simpler and consistent with TCP.

## R5: Player Token Design

**Decision**: UUID-based opaque token returned at session join

**Rationale**: When a player joins a game session via POST, the server
generates a `UUID` token and returns it alongside the assigned player marker.
The client includes this token in the `Authorization` header (or query
parameter) of subsequent move requests. The server validates the token against
the session's registered players.

Using `UUID.randomUUID()` provides sufficient uniqueness for an educational
project without cryptographic overhead. The token is a `record` type per
Principle III.

**Alternatives considered**:
- *Session cookies*: Rejected in clarification.
- *JWT*: Overkill for in-memory session state; adds complexity without benefit.
- *Sequential IDs*: Predictable; UUIDs are more robust against accidental
  collision.

## R6: Concurrency Model

**Decision**: Virtual thread per HTTP connection + ConcurrentHashMap for sessions

**Rationale**: Mirrors the TCP `GameServer` pattern using
`Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory())`.
`HttpServer.setExecutor()` accepts a custom executor — we provide the virtual
thread executor. Game sessions are stored in a `ConcurrentHashMap<UUID, GameSession>`.
Move submissions use a per-session blocking queue (`SynchronousQueue` or
`LinkedBlockingQueue`) so `accept()` blocks the game thread until a move
arrives via HTTP POST.

**Alternatives considered**:
- *Platform threads*: Wasteful for I/O-bound HTTP handling; virtual threads
  are mandated by Principle VII.
- *Reactive/async*: More complex, less readable, and inconsistent with the
  TCP module's blocking model.

## R7: Branch Strategy

**Decision**: Develop on `014-http3-game-server` for planning; implement on
`jdk26` branch

**Rationale**: Per Principle IX, JEP 517 targets JDK 26 (pre-GA), so
implementation code MUST reside on a `jdk26` branch. The spec/plan artifacts
on `014-http3-game-server` are documentation only. When implementation begins,
work should branch from or merge into `jdk26`. The `jdk26` branch merges to
main only after JDK 26 reaches GA and JEP 517 is finalized.
