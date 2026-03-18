# Data Model: HTTP/3 Game Server & Client

**Feature**: 014-http3-game-server
**Date**: 2026-03-15

## Entities

### GameSession (record)

Represents an active or completed game managed by the HTTP server.

| Field | Type | Description |
|-------|------|-------------|
| id | UUID | Unique session identifier |
| status | SessionStatus | Current lifecycle state |
| gameState | GameState | Current board and player state (from api module) |
| playerTokens | Map<PlayerToken, String> | Token → player marker mapping |
| createdAt | Instant | Session creation timestamp |

**Identity**: `id` (UUID, server-generated)
**Uniqueness**: One session per UUID; enforced by ConcurrentHashMap key

### SessionStatus (sealed interface / enum)

Lifecycle states for a game session.

| Value | Description | Transitions to |
|-------|-------------|----------------|
| WAITING | Fewer than 2 players have joined | ACTIVE |
| ACTIVE | Both players joined; game in progress | COMPLETED |
| COMPLETED | Game finished (win or draw) | (terminal) |

**Exhaustive switch required** per Principle III.

### PlayerToken (record)

Opaque identity token assigned to a player when joining a session.

| Field | Type | Description |
|-------|------|-------------|
| value | UUID | Unique token value |

**Identity**: `value` (UUID, server-generated via `UUID.randomUUID()`)

### HttpMoveRequest (record)

Represents a client-submitted move parsed from an HTTP POST body.

| Field | Type | Description |
|-------|------|-------------|
| sessionId | UUID | Target game session |
| playerToken | UUID | Player identity token |
| position | int | Target board position (0-based) |

**Validation rules**:
- `sessionId` MUST reference an existing ACTIVE session
- `playerToken` MUST match a registered player in the session
- `position` MUST be within board bounds and unoccupied
- It MUST be the requesting player's turn

### HttpGameStateResponse (record)

Server response containing current game state, serialized as JSON.

| Field | Type | Description |
|-------|------|-------------|
| version | int | Protocol version (1) |
| sessionId | UUID | Session identifier |
| status | SessionStatus | Current session status |
| board | String[] | Board content array (nullable cells) |
| dimension | int | Board dimension (default 3) |
| playerMarkers | String[] | Ordered player markers |
| currentPlayerIndex | int | Index of player whose turn it is |
| outcome | String | null, "win:X", "win:O", or "draw" |

**JSON format**: Mirrors `TcpProtocol` structure for consistency (FR-012).

### SseEvent (record)

A Server-Sent Event frame pushed to a connected client.

| Field | Type | Description |
|-------|------|-------------|
| event | String | Event type: "state", "start", "end", "error" |
| data | String | JSON payload (HttpGameStateResponse serialized) |

**Wire format**: `event: {event}\ndata: {data}\n\n`

## Relationships

```text
GameSession 1──* PlayerToken     (each session has exactly 2 tokens)
GameSession 1──1 GameState       (from api module; current board state)
GameSession 1──1 SessionStatus   (lifecycle state)
PlayerToken 1──* HttpMoveRequest (player submits moves using their token)
GameSession 1──* SseEvent        (state changes pushed to connected clients)
```

## State Transitions

```text
             join (1st player)        join (2nd player)
  [created] ──────────────────► WAITING ──────────────────► ACTIVE
                                                              │
                                              win/draw/error  │
                                                              ▼
                                                          COMPLETED
```

- WAITING → ACTIVE: triggered when the second player joins
- ACTIVE → COMPLETED: triggered by game outcome (win/draw) or error (disconnect)
- No transition back from COMPLETED; session is retained briefly then cleaned up
