# Implementation Plan: HTTP/3 Game Server & Client

**Branch**: `014-http3-game-server` | **Date**: 2026-03-15 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/014-http3-game-server/spec.md`

## Summary

Introduce an `http-gameserver` Gradle module that implements the existing
`TransportServer` interface over HTTP, using JDK built-in
`com.sun.net.httpserver` (JEP 408) for the server and `java.net.http.HttpClient`
with HTTP/3 opt-in (JEP 517) for the client. The server exposes REST endpoints
for game creation, move submission, and state retrieval, with Server-Sent Events
(SSE) for real-time state push. Player identity uses server-assigned opaque
tokens. Virtual threads handle concurrent sessions. Since JEP 517 targets
JDK 26 (pre-GA), this feature branch merges into a `jdk26` branch per
Principle IX (v1.1.1).

## Technical Context

**Language/Version**: Java (JDK 26, Azul Zulu — pre-GA; merges into `jdk26` branch)
**Primary Dependencies**: JDK built-in only — `com.sun.net.httpserver`,
`java.net.http`, `java.util.concurrent`
**Storage**: In-memory (ConcurrentHashMap for game sessions)
**Testing**: TestNG 7.5.1 (integration tests with real HTTP server/client)
**Target Platform**: JVM (localhost development; CI on GitHub Actions)
**Project Type**: Library (independently publishable Gradle module)
**Performance Goals**: <5s full game on localhost; 10+ concurrent sessions
**Constraints**: JDK-only APIs; no external HTTP frameworks; `--enable-preview`
permitted on this feature branch since it targets `jdk26` (per Principle IX v1.1.1)
**Scale/Scope**: Educational project; localhost multiplayer

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | Principle | Status | Notes |
|---|-----------|--------|-------|
| I | Educational Parity with OpenJDK | ✅ PASS | Demonstrates JEP 517 (HTTP/3 Client) and JEP 408 (Simple Web Server). Javadoc will explain *why* each feature is used. |
| II | Modular Decomposition | ✅ PASS | New `http-gameserver` module depends on `api` only. No reverse dependencies. Independently buildable/testable. |
| III | Sealed Types & Exhaustive Patterns | ✅ PASS | Session status (waiting/active/completed) and protocol messages will use sealed types with exhaustive switch. Records for value objects (move request, game state response, player token). |
| IV | Convention-Based Build | ✅ PASS | Will use `buildlogic.java-library-conventions` from `buildSrc/`. `--enable-preview` permitted on this feature branch since it targets `jdk26`. |
| V | Test-Driven Feature Addition | ✅ PASS | TestNG integration tests exercising real HTTP server/client game flows. Test classes follow `*Test.java` convention. |
| VI | Security & Cryptography Standards | ⚠️ N/A | No encryption layer in initial scope. Player tokens are opaque identifiers, not cryptographic credentials. If TLS is added later, MUST use JCE SPI. |
| VII | Performance as a Feature | ✅ PASS | Virtual threads for I/O-bound HTTP handling. JMH benchmarks for request throughput. ZGC as default GC. |
| VIII | Publishing as a First-Class Concern | ✅ PASS | Published as `tictactoe-http-gameserver` to Maven Central and GitHub Packages with JDK suffix versioning. |
| IX | Branch Stability & JDK Versioning | ✅ PASS | JEP 517 targets JDK 26 (pre-GA). This feature branch targets `jdk26` and MAY use preview features per constitution v1.1.1. Merges into `jdk26`, not directly into main. |
| X | Dependency Minimalism | ✅ PASS | Zero external dependencies. `com.sun.net.httpserver` and `java.net.http` are JDK built-in modules. |

**Post-Phase 1 re-check**: All principles still pass. SSE implementation uses
only `HttpHandler` and raw output streams (JDK built-in). `TransportServer`
blocking `accept()` pattern handled by virtual threads — no async framework
needed.

## Project Structure

### Documentation (this feature)

```text
specs/014-http3-game-server/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
│   └── http-api.md
└── tasks.md             # Phase 2 output (/speckit.tasks)
```

### Source Code (repository root)

```text
http-gameserver/
├── build.gradle.kts
└── src/
    ├── main/java/
    │   └── org/xxdc/oss/example/
    │       ├── HttpGameServer.java          # Server entry point (main)
    │       ├── HttpGameClient.java          # Client entry point (main)
    │       └── transport/http/
    │           ├── HttpTransportServer.java  # TransportServer impl
    │           ├── HttpTransportClient.java  # HTTP client wrapper
    │           ├── HttpProtocol.java         # JSON message parsing
    │           ├── HttpTransports.java       # Factory utility
    │           ├── GameSessionManager.java   # Session lifecycle
    │           ├── GameSession.java          # Single session state
    │           ├── PlayerToken.java          # Opaque player identity
    │           └── SseHandler.java           # SSE event stream handler
    └── test/java/
        └── org/xxdc/oss/example/
            ├── HttpGameServerTest.java       # Integration tests
            ├── HttpGameClientTest.java       # Client tests
            └── transport/http/
                ├── HttpTransportServerTest.java
                ├── HttpProtocolTest.java
                ├── GameSessionManagerTest.java
                └── SseHandlerTest.java
```

**Structure Decision**: Follows the established single-module pattern matching
`tcp-gameserver/`. Package hierarchy mirrors `transport.tcp` → `transport.http`.
Server and client entry points at the module package root, transport
implementation in the `transport.http` subpackage.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

No violations. All principles satisfied.
