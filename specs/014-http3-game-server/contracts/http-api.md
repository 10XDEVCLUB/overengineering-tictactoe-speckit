# HTTP API Contract: Game Server

**Base URL**: `http://localhost:8080`
**Content-Type**: `application/json` (requests/responses), `text/event-stream` (SSE)

## Endpoints

### POST /games

Create a new game session.

**Request**: Empty body

**Response** (201 Created):
```json
{
  "sessionId": "a1b2c3d4-...",
  "status": "WAITING"
}
```

### POST /games/{sessionId}/join

Join an existing game session as a player.

**Request**: Empty body

**Response** (200 OK):
```json
{
  "sessionId": "a1b2c3d4-...",
  "playerToken": "e5f6g7h8-...",
  "assignedPlayerMarker": "X",
  "status": "WAITING"
}
```

When the second player joins, status becomes `"ACTIVE"` and an SSE
`start` event is pushed to both connected clients.

**Error** (409 Conflict): Session already has two players.
```json
{
  "error": "Session full"
}
```

**Error** (404 Not Found): Session does not exist.

### POST /games/{sessionId}/move

Submit a move.

**Headers**: `X-Player-Token: {playerToken}`

**Request**:
```json
{
  "position": 4
}
```

**Response** (200 OK):
```json
{
  "version": 1,
  "sessionId": "a1b2c3d4-...",
  "status": "ACTIVE",
  "board": ["X", null, null, null, "O", null, null, null, null],
  "dimension": 3,
  "playerMarkers": ["X", "O"],
  "currentPlayerIndex": 0,
  "outcome": null
}
```

**Error** (400 Bad Request): Invalid move (occupied, out of bounds).
```json
{
  "error": "Position 4 is already occupied"
}
```

**Error** (403 Forbidden): Not this player's turn, or invalid token.
```json
{
  "error": "Not your turn"
}
```

**Error** (410 Gone): Game already completed.
```json
{
  "error": "Game over",
  "outcome": "win:X"
}
```

### GET /games/{sessionId}/state

Retrieve current game state.

**Response** (200 OK):
```json
{
  "version": 1,
  "sessionId": "a1b2c3d4-...",
  "status": "ACTIVE",
  "board": ["X", "O", null, null, "X", null, null, null, null],
  "dimension": 3,
  "playerMarkers": ["X", "O"],
  "currentPlayerIndex": 1,
  "outcome": null
}
```

### GET /games/{sessionId}/events

Subscribe to game state updates via Server-Sent Events.

**Headers**: `X-Player-Token: {playerToken}`

**Response** (200 OK, `Content-Type: text/event-stream`):

```text
event: start
data: {"sessionId":"a1b2c3d4-...","status":"ACTIVE","playerMarkers":["X","O"]}

event: state
data: {"version":1,"board":["X",null,null,...],"currentPlayerIndex":1,...}

event: state
data: {"version":1,"board":["X","O",null,...],"currentPlayerIndex":0,...}

event: end
data: {"outcome":"win:X"}
```

**Event types**:
| Event | When | Data |
|-------|------|------|
| `start` | Second player joins; game begins | Session info with player assignments |
| `state` | After each valid move | Full game state (HttpGameStateResponse) |
| `end` | Game concludes (win/draw) | Final outcome |
| `error` | Server-side error | Error description |

**Reconnection**: Client MUST reconnect on stream drop and GET
`/games/{sessionId}/state` to resynchronize.

## HTTP Status Codes

| Code | Usage |
|------|-------|
| 200 | Successful retrieval or action |
| 201 | Session created |
| 400 | Invalid move or malformed request |
| 403 | Wrong turn or invalid player token |
| 404 | Session not found |
| 409 | Session full (join attempt) |
| 410 | Game already completed |
| 503 | Server at capacity |

## Protocol Versioning

All game state responses include a `"version": 1` field, consistent with
the TCP protocol's versioning scheme. Future protocol changes increment
this version number.
