package com.chatapp.websocket;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Keeps track of who's actually connected right now, so we know where to route a message.
 * This is the bit that would normally be handled by a framework (socket.io rooms, STOMP
 * broker, etc) - here it's just a map, but there's a couple of things worth calling out:
 *
 * - one user can have more than one tab/browser open, so this maps username -> a SET of
 *   sessions, not a single session. Learned this the hard way testing with 2 windows - if you
 *   only keep the latest session per user, opening a second tab silently kills the first one's
 *   ability to receive anything.
 * - CopyOnWriteArraySet because we iterate this set every time we broadcast to a user, and
 *   don't want a ConcurrentModificationException if they disconnect mid-send. Reads >> writes
 *   here so the copy-on-write cost is fine.
 * - ConcurrentHashMap + computeIfAbsent so registering two different users at the same time
 *   doesn't block on each other.
 */
@Component
public class SessionRegistry {

    private final ConcurrentHashMap<String, Set<WebSocketSession>> sessionsByUser = new ConcurrentHashMap<>();

    public void register(String username, WebSocketSession session) {
        sessionsByUser.computeIfAbsent(username, u -> new CopyOnWriteArraySet<>()).add(session);
    }

    public void unregister(String username, WebSocketSession session) {
        sessionsByUser.computeIfPresent(username, (u, sessions) -> {
            sessions.remove(session);
            // drop the entry once it's empty, otherwise this map just grows forever
            return sessions.isEmpty() ? null : sessions;
        });
    }

    public Set<WebSocketSession> getSessions(String username) {
        return sessionsByUser.getOrDefault(username, Set.of());
    }

    public boolean isOnline(String username) {
        return !getSessions(username).isEmpty();
    }

    public Set<String> onlineUsernames() {
        return Set.copyOf(sessionsByUser.keySet());
    }
}
