# Real-time chat MVP

1:1 messaging, WhatsApp-style. Spring Boot backend + plain HTML/JS frontend, real-time part
is hand-rolled over raw WebSocket (no STOMP, no socket.io, no broker).

No login - you just type a username and go. Wasn't part of the requirements so didn't want to
burn time on it; see "what I'd add" at the bottom for what that's actually missing.

## Running it

```
docker compose up --build
```

- frontend: http://localhost:8081
- backend: http://localhost:8080

Open two browser windows (or one normal + one incognito so localStorage doesn't clash),
pick two different names, message between them.

## Opening in IntelliJ

`backend/pom.xml` -> Open as Project. Runs fine straight from the IDE against the embedded H2
db, don't need docker for just working on the backend. `frontend/` is static files, no build
step.

## How it's put together

```
frontend (nginx, static)  --HTTP-->  backend /api/**   (contacts + history)
                          --WS-->    backend /ws/chat   (the actual messaging)
                                          |
                                     H2 file db (just a messages table)
```

Core piece is `ChatWebSocketHandler` + `SessionRegistry` - that's the part I built from
scratch per the "no framework doing the whole thing" rule. Everything using Spring/JPA/H2
is the non-core plumbing the brief says is fine to use as-is.

Went with WebSocket over SSE/polling since this needs to be two-way (either side can message
at any time) - SSE is server->client only so you'd still need something else for the other
direction anyway.

## Notes on why I did things a certain way

Wrote most of this as comments in the actual files, but the short version:

- **sessions map is username -> a set of sessions**, not one session per user, because someone
  can have two tabs open. Found this out testing with 2 windows - if you only track one
  session per user, opening a second tab kills the first one's ability to get messages.
- **save the message before trying to deliver it live**, not after. If it saved after and the
  app crashed right after pushing it over the socket, the recipient could've seen a message
  that then just isn't in history anymore. Worst case with save-first is a duplicate send
  attempt, which the clientMessageId unique constraint handles.
- **offline recipients still get their message saved** (status = PENDING) and pushed the second
  they reconnect, instead of just... dropped. Kind of the whole point of "real messaging app"
  vs a toy demo.
- **own ping/pong loop** instead of trusting TCP to notice a dead connection - closing a laptop
  lid doesn't send a clean close frame, and the OS can take a while to notice on its own. Server
  pings every 30s, kills anything quiet for 90s.
- **username goes in the websocket connect URL as a query param**, checked before the socket
  even opens (in `AuthHandshakeInterceptor`) - browsers won't let you set custom headers on a
  websocket handshake the way you can on a normal request, so query param it is.
- **clientMessageId** is generated on the frontend, not the backend, so the UI can show
  "sending..." immediately without waiting on a round trip, and it also works as a de-dupe key
  if a message gets sent twice by accident.
- **synchronized around session.sendMessage()** - WebSocketSession isn't safe for concurrent
  writes and a session can get written to from two places at once (e.g. an incoming chat and a
  heartbeat ping landing together), so all sends for one session go through one lock.
- **no real accounts** - contact list is just "who's online" + "who you've talked to before",
  pulled straight from the messages table. There's no signup because there's nothing to sign up
  for.

## What I'd add with more time / what's missing

- Real auth. Right now anyone can type any username and there's nothing stopping impersonation.
  Would need actual credentials + a proper session token checked at the handshake instead of a
  bare claimed name.
- Doesn't scale past one instance - SessionRegistry is just an in-memory map, so with two
  backend pods behind a load balancer you'd need sticky sessions at minimum, or better, push
  this through something shared like Redis pub/sub so a message can hop from instance A to B.
- Multi-tab ordering for the *same* sender isn't reconciled on the frontend - each tab gets its
  own clientMessageId and they land fine in the DB, but there's no sequence number tying them
  together for strict ordering. Fine for a demo, wouldn't be for a real product.
- No typing indicators / read receipts, though the protocol has room to add message types for
  that later.
- No rate limiting on message sends or on join attempts.
- Minimal input handling - frontend escapes on render, content's length-capped, but no
  server-side spam/profanity filtering.
- Everything's plain HTTP/WS, no TLS - fine for local, would need a reverse proxy in front for
  anything public.
