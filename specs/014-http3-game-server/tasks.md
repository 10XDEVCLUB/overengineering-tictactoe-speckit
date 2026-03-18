# Tasks: HTTP/3 Game Server & Client

**Input**: Design documents from `/specs/014-http3-game-server/`
**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, contracts/

**Tests**: Tests are included — constitution Principle V mandates TestNG tests for every new feature.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- **Module root**: `http-gameserver/`
- **Source**: `http-gameserver/src/main/java/org/xxdc/oss/example/`
- **Tests**: `http-gameserver/src/test/java/org/xxdc/oss/example/`
- **Transport subpackage**: `transport/http/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Create the `http-gameserver` Gradle module and wire it into the multi-project build

- [x] T001 Add `include("http-gameserver")` to `settings.gradle.kts`
- [x] T002 Create `http-gameserver/build.gradle.kts` applying `buildlogic.java-library-conventions` with dependency on `:api` and Maven publishing coordinates `tictactoe-http-gameserver`. Verify ZGC is configured as default GC via convention plugin; if not inherited, add `jvmArgs("-XX:+UseZGC")` to application run tasks. Verify artifact version follows `MAJOR.MINOR.PATCH-jdk26` suffix convention inherited from root project; if not inherited, configure explicitly
- [x] T003 [P] Create package directory structure at `http-gameserver/src/main/java/org/xxdc/oss/example/transport/http/`
- [x] T004 [P] Create test package directory structure at `http-gameserver/src/test/java/org/xxdc/oss/example/transport/http/`
- [x] T005 Verify module builds independently with `./gradlew :http-gameserver:build`

**Checkpoint**: Module compiles, tests run (empty), Spotless formatting active

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core types and protocol shared by all user stories

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [x] T006 [P] Create `SessionStatus` sealed interface (WAITING, ACTIVE, COMPLETED) with exhaustive switch support in `http-gameserver/src/main/java/org/xxdc/oss/example/transport/http/SessionStatus.java`
- [x] T007 [P] Create `PlayerToken` record wrapping a UUID in `http-gameserver/src/main/java/org/xxdc/oss/example/transport/http/PlayerToken.java`
- [x] T008 [P] Create `GameSession` record with fields (id, status, gameState, playerTokens map, createdAt) in `http-gameserver/src/main/java/org/xxdc/oss/example/transport/http/GameSession.java`
- [x] T009 Create `HttpProtocol` class with JSON serialization/deserialization methods mirroring `TcpProtocol` format (version, message type, board, playerMarkers, currentPlayerIndex, outcome) in `http-gameserver/src/main/java/org/xxdc/oss/example/transport/http/HttpProtocol.java`
- [x] T010 Create `HttpProtocolTest` validating JSON round-trip for game state, move request, error responses, and SSE event formatting in `http-gameserver/src/test/java/org/xxdc/oss/example/transport/http/HttpProtocolTest.java`

**Checkpoint**: Foundation ready — all shared types compile, protocol serialization tests pass

---

## Phase 3: User Story 1 — Play a Game Over HTTP (Priority: P1) 🎯 MVP

**Goal**: Two players create, join, and play a complete game over HTTP with SSE state push

**Independent Test**: Start HTTP server, connect two clients, play a full game to win/draw

### Tests for User Story 1

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [x] T011 [P] [US1] Create `GameSessionManagerTest` testing session creation, player join (token assignment), session state transitions (WAITING→ACTIVE→COMPLETED), and rejection when session is full in `http-gameserver/src/test/java/org/xxdc/oss/example/transport/http/GameSessionManagerTest.java`
- [x] T012 [P] [US1] Create `SseHandlerTest` testing SSE event stream output format (`event:` + `data:` + `\n\n`), multiple event writes, and stream closure on game end in `http-gameserver/src/test/java/org/xxdc/oss/example/transport/http/SseHandlerTest.java`
- [x] T013 [P] [US1] Create `HttpTransportServerTest` testing `TransportServer` interface contract: `initialize()` assigns player marker, `send()` pushes SSE event, `accept()` blocks until move submitted then returns position in `http-gameserver/src/test/java/org/xxdc/oss/example/transport/http/HttpTransportServerTest.java`
- [x] T014 [US1] Create `HttpGameServerTest` integration test: start server on ephemeral port, two clients join a session, submit alternating moves via POST, receive SSE events, game completes with correct outcome in `http-gameserver/src/test/java/org/xxdc/oss/example/HttpGameServerTest.java`

### Implementation for User Story 1

- [x] T015 [US1] Implement `GameSessionManager` with methods: `createSession()`, `joinSession(UUID)` returning PlayerToken + marker, `getSession(UUID)`, `submitMove(UUID, PlayerToken, int)` with validation (turn order, bounds, occupied), session cleanup on completion — backed by `ConcurrentHashMap<UUID, GameSession>` in `http-gameserver/src/main/java/org/xxdc/oss/example/transport/http/GameSessionManager.java`
- [x] T016 [US1] Implement `SseHandler` implementing `HttpHandler` — sets `Content-Type: text/event-stream`, holds output stream open, provides `sendEvent(String type, String jsonData)` method writing `event: {type}\ndata: {data}\n\n` frames in `http-gameserver/src/main/java/org/xxdc/oss/example/transport/http/SseHandler.java`
- [x] T017 [US1] Implement `HttpTransportServer` implementing `TransportServer` — `initialize()` registers player and opens SSE stream, `send(GameState)` writes SSE event via `SseHandler`, `accept()` blocks on per-player `SynchronousQueue<Integer>` until move arrives via POST in `http-gameserver/src/main/java/org/xxdc/oss/example/transport/http/HttpTransportServer.java`
- [x] T018 [US1] Implement `HttpTransports` factory utility with static method to create `HttpTransportServer` instances from HTTP exchange context in `http-gameserver/src/main/java/org/xxdc/oss/example/transport/http/HttpTransports.java`
- [x] T019 [US1] Implement `HttpGameServer` with `main()` entry point — creates `HttpServer.create()` on configurable port, sets virtual thread executor via `Thread.ofVirtual().factory()`, registers handlers for POST `/games`, POST `/games/{id}/join`, POST `/games/{id}/move`, GET `/games/{id}/state`, GET `/games/{id}/events` in `http-gameserver/src/main/java/org/xxdc/oss/example/HttpGameServer.java`
- [x] T020 [US1] Add Javadoc to all public classes and methods explaining JEP 408 usage (Simple Web Server / `com.sun.net.httpserver`) and why each design choice was made

**Checkpoint**: Full game playable over HTTP. Server starts, two clients join, play to completion via REST + SSE. All US1 tests pass.

---

## Phase 4: User Story 2 — Connect Using HTTP/3 with Fallback (Priority: P2)

**Goal**: Client uses `HttpClient.Version.HTTP_3` opt-in with automatic fallback to HTTP/1.1

**Independent Test**: HTTP/3-configured client connects to HTTP/1.1 server, falls back transparently, game completes

### Tests for User Story 2

- [x] T021 [US2] Create `HttpGameClientTest` testing: client configured with `HTTP_3` preference connects to HTTP/1.1 server and completes a game, negotiated protocol version is logged, HTTP/3-only mode reports failure against HTTP/1.1 server in `http-gameserver/src/test/java/org/xxdc/oss/example/HttpGameClientTest.java`

### Implementation for User Story 2

- [x] T022 [US2] Implement `HttpTransportClient` wrapping `java.net.http.HttpClient` with `HttpClient.newBuilder().version(HttpClient.Version.HTTP_3).build()`, methods to POST moves, GET state, and consume SSE event stream via `HttpResponse.BodyHandlers.ofLines()`, logging negotiated `HttpResponse.version()`, with SSE stream reconnection on unexpected disconnect (re-subscribe to `/games/{id}/events` and GET `/games/{id}/state` to resynchronize) and configurable HTTP request timeout with retry-on-failure for move submissions (report failure to user after max retries) in `http-gameserver/src/main/java/org/xxdc/oss/example/transport/http/HttpTransportClient.java`
- [x] T023 [US2] Implement `HttpGameClient` with `main()` entry point — creates HTTP/3-configured client, joins a session, subscribes to SSE events, plays game using bot strategy, reports negotiated protocol version in `http-gameserver/src/main/java/org/xxdc/oss/example/HttpGameClient.java`
- [x] T024 [US2] Add Javadoc to `HttpTransportClient` and `HttpGameClient` explaining JEP 517 (HTTP/3 Client API), opt-in mechanism, and fallback strategies

**Checkpoint**: Client connects with HTTP/3 preference, falls back to HTTP/1.1, game completes, protocol version logged. All US2 tests pass.

---

## Phase 5: User Story 3 — Run Concurrent Games Over HTTP (Priority: P3)

**Goal**: Server handles 10+ concurrent game sessions without cross-game interference

**Independent Test**: Launch server, connect 10 client pairs concurrently, all games complete independently

### Tests for User Story 3

- [x] T025 [US3] Add concurrency integration test to `HttpGameServerTest`: launch 10 concurrent game sessions with `CompletableFuture.allOf()`, verify all complete independently, no cross-session state leakage in `http-gameserver/src/test/java/org/xxdc/oss/example/HttpGameServerTest.java`

### Implementation for User Story 3

- [x] T026 [US3] Add session capacity limit to `GameSessionManager` with configurable max concurrent sessions, return HTTP 503 when at capacity in `http-gameserver/src/main/java/org/xxdc/oss/example/transport/http/GameSessionManager.java`
- [x] T027 [US3] Add session timeout and cleanup — idle sessions older than configurable threshold are removed, disconnected player sessions are cleaned up in `http-gameserver/src/main/java/org/xxdc/oss/example/transport/http/GameSessionManager.java`
- [x] T028 [US3] Add `System.getLogger()` logging to `HttpGameServer` and `GameSessionManager` for session lifecycle events (create, join, move, complete, timeout, cleanup) in `http-gameserver/src/main/java/org/xxdc/oss/example/HttpGameServer.java` and `GameSessionManager.java`

**Checkpoint**: 10 concurrent games complete successfully. Session capacity limits enforced. Abandoned sessions cleaned up. All US3 tests pass.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Improvements that affect multiple user stories

- [x] T029 [P] Add JMH benchmark for HTTP request throughput: (1) add `jmh` plugin and `jmh` source set to `http-gameserver/build.gradle.kts` using `me.champeau.jmh` plugin with `jmhImplementation("org.openjdk.jmh:jmh-core:1.37")`, (2) create `HttpGameServerBenchmark` with `@BenchmarkMode(Mode.Throughput)` and `@OutputTimeUnit(TimeUnit.SECONDS)` benchmarking a full game round-trip (create session, two players join, submit moves to win/draw), (3) add a second benchmark measuring single POST `/games/{id}/move` latency with pre-warmed session using `@BenchmarkMode(Mode.AverageTime)`, targeting ≥100 moves/sec throughput and ≤50ms avg move latency on localhost — in `http-gameserver/src/jmh/java/org/xxdc/oss/example/HttpGameServerBenchmark.java`
- [x] T030 [P] Validate quickstart.md scenarios end-to-end: write `QuickstartValidationTest` that (1) starts the `HttpGameServer` on an ephemeral port, (2) executes each scenario from `specs/014-http3-game-server/quickstart.md` section 3 (create game → join as X → join as O → subscribe to events → submit moves to completion) using `java.net.http.HttpClient`, verifying HTTP response codes and JSON structure match the documented examples, (3) verifies the `HttpGameClient` main entry point connects, logs the negotiated protocol version (HTTP/1.1 or HTTP/3), and exits cleanly — in `http-gameserver/src/test/java/org/xxdc/oss/example/QuickstartValidationTest.java`; update `specs/014-http3-game-server/quickstart.md` if any response shape discrepancies are found
- [x] T031 [P] Extend `QuickstartValidationTest` to cover the two remaining quickstart sections: (1) Section 2 — verify `./gradlew :http-gameserver:run --args="<port>"` starts the server by invoking `HttpGameServer.main(new String[]{String.valueOf(port)})` directly and asserting the server accepts a `POST /games` request within 2 seconds; (2) Section 3 SSE stream — subscribe to `GET /games/{sessionId}/events` on a virtual thread using `HttpResponse.BodyHandlers.ofLines()`, play a complete game on the main thread, then assert the SSE output contains `event: start`, at least one `event: state`, and `event: end` lines in order — in `http-gameserver/src/test/java/org/xxdc/oss/example/QuickstartValidationTest.java`
- [x] T032 Run `./gradlew :http-gameserver:build :http-gameserver:test :http-gameserver:javadoc` to confirm independent module build, all tests pass, and Javadoc generates cleanly
- [x] T033 Create `.github/workflows/http-gameserver.yml` GitHub Actions workflow that runs `./gradlew :http-gameserver:build :http-gameserver:test -PskipSigning=true` on push and pull_request events targeting the `014-http3-game-server` and `jdk26` branches, using `azul/setup-zulu` action with JDK 26
- [x] T034 Update `README.md` JEP index with two entries for this feature (Principle XI): (1) JEP 408 — Simple Web Server, demonstrated by `HttpGameServer` in `http-gameserver/`, showing how to build a REST + SSE game server using only `com.sun.net.httpserver` with no external frameworks; (2) JEP 517 — HTTP/3 Client, demonstrated by `HttpTransportClient` and `HttpGameClient` in `http-gameserver/`, showing HTTP/3 opt-in via `HttpClient.Version.HTTP_3` with automatic fallback to HTTP/1.1 — in `README.md`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion — BLOCKS all user stories
- **User Stories (Phase 3+)**: All depend on Foundational phase completion
  - US1 (Phase 3): Can start after Phase 2
  - US2 (Phase 4): Can start after Phase 2 (independent of US1 for client code, but server from US1 needed for integration tests)
  - US3 (Phase 5): Depends on US1 (needs working server for concurrency testing)
- **Polish (Phase 6)**: Depends on all user stories being complete

### Within Each User Story

- Tests MUST be written and FAIL before implementation
- Records/models before services
- Services before handlers/endpoints
- Core implementation before integration
- Javadoc after implementation stabilizes

### Parallel Opportunities

```bash
# Phase 2 — all foundation types in parallel:
T006: SessionStatus sealed interface
T007: PlayerToken record
T008: GameSession record

# Phase 3 (US1) — all tests in parallel:
T011: GameSessionManagerTest
T012: SseHandlerTest
T013: HttpTransportServerTest

# Phase 6 — polish tasks in parallel:
T029: JMH benchmark
T030: Quickstart validation (sections 3+4)
T031: Quickstart validation (sections 2+SSE stream) — extends T030's test class
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (T001–T005)
2. Complete Phase 2: Foundational types (T006–T010)
3. Complete Phase 3: User Story 1 (T011–T020)
4. **STOP and VALIDATE**: Full game over HTTP works end-to-end
5. This is a deployable, demonstrable MVP

### Incremental Delivery

1. Setup + Foundational → Foundation ready
2. Add User Story 1 → Test independently → MVP (HTTP game works)
3. Add User Story 2 → Test independently → HTTP/3 client demo
4. Add User Story 3 → Test independently → Concurrent games
5. Polish → Benchmarks, docs validation

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- Each user story is independently completable and testable
- Verify tests fail before implementing
- Commit after each task or logical group
- `--enable-preview` is permitted on this feature branch (constitution v1.1.1)
- This feature branch merges into `jdk26`, not directly into `main`

## Next Steps

- T029–T031: Assign owners and estimate effort for benchmarks, quickstart validation, and full module build verification.
- T032: Add CI workflow for `http-gameserver` to run `./gradlew :http-gameserver:build :http-gameserver:test` on PRs.
- T033: Add release notes and changelog entries under `specs/014-http3-game-server/` for the feature.
- T034: Decide whether to implement code for T015–T020 immediately or proceed iteratively; create issues for each T0xx in the issue tracker.

Please confirm if you want me to implement any specific task next (suggest starting with T015: `GameSessionManager`).
