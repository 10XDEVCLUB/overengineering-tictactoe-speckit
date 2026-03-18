# Quickstart: HTTP/3 Game Server & Client

**Prerequisites**: JDK 26 (Azul Zulu), Gradle 8.7+

## 1. Build the module

```bash
./gradlew :http-gameserver:build
```

## 2. Start the server

```bash
./gradlew :http-gameserver:run --args="8080"
```

Or directly:

```bash
java --enable-preview -cp http-gameserver/build/libs/tictactoe-http-gameserver-*.jar \
  org.xxdc.oss.example.HttpGameServer 8080
```

Server starts on `http://localhost:8080`.

## 3. Play a game (two terminal windows)

**Terminal 1 — Create and join a game:**

```bash
# Create a new game session
curl -X POST http://localhost:8080/games
# → {"sessionId":"a1b2c3d4-...","status":"WAITING"}

# Join as Player X
curl -X POST http://localhost:8080/games/a1b2c3d4-.../join
# → {"sessionId":"a1b2c3d4-...","playerToken":"tok1-...","assignedPlayerMarker":"X","status":"WAITING"}

# Subscribe to events (stays open)
curl -N http://localhost:8080/games/a1b2c3d4-.../events \
  -H "X-Player-Token: tok1-..."
```

**Terminal 2 — Join and play:**

```bash
# Join as Player O
curl -X POST http://localhost:8080/games/a1b2c3d4-.../join
# → {"sessionId":"a1b2c3d4-...","playerToken":"tok2-...","assignedPlayerMarker":"O","status":"ACTIVE"}

# Subscribe to events in background, then submit moves
curl -N http://localhost:8080/games/a1b2c3d4-.../events \
  -H "X-Player-Token: tok2-..." &

# Player X moves to center (position 4)
curl -X POST http://localhost:8080/games/a1b2c3d4-.../move \
  -H "X-Player-Token: tok1-..." \
  -H "Content-Type: application/json" \
  -d '{"position": 4}'

# Player O moves to top-left (position 0)
curl -X POST http://localhost:8080/games/a1b2c3d4-.../move \
  -H "X-Player-Token: tok2-..." \
  -H "Content-Type: application/json" \
  -d '{"position": 0}'
```

## 4. Use the Java client with HTTP/3

```bash
java --enable-preview -cp http-gameserver/build/libs/tictactoe-http-gameserver-*.jar \
  org.xxdc.oss.example.HttpGameClient localhost 8080
```

The client creates an `HttpClient` with `Version.HTTP_3` preference. It logs
the negotiated protocol version on connection. When connecting to the JDK
built-in HTTP/1.1 server, it automatically falls back and reports the
fallback.

## 5. Run tests

```bash
./gradlew :http-gameserver:test
```

## Verification checklist

- [ ] Server starts and listens on configured port
- [ ] Two players can join a session and play to completion
- [ ] SSE events arrive for each state change
- [ ] Invalid moves return appropriate error responses
- [ ] Client logs negotiated HTTP version (HTTP/1.1 or HTTP/3)
- [ ] Module builds and tests independently
