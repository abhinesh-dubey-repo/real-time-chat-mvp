package com.chatapp.websocket;

import com.chatapp.dto.WsMessage;
import com.chatapp.model.Message;
import com.chatapp.repository.MessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This is the actual core feature - everything else in the project is either supporting this
 * (SessionRegistry, WsMessage) or just normal CRUD around it (controllers/repos).
 *
 * What it does, roughly:
 *   1. tracks who's connected / cleans up on disconnect
 *   2. routes a CHAT message to the right session(s) - including more than one tab for the
 *      same user
 *   3. if the recipient isn't online, still saves the message so it's not lost, and pushes it
 *      the moment they reconnect
 *   4. runs its own ping/pong loop so we notice dead connections instead of waiting on TCP/OS
 *      timeouts, which can take way longer than makes sense for a chat app
 *
 * Went with Spring's plain TextWebSocketHandler (just wraps the servlet websocket upgrade) on
 * purpose instead of STOMP + SimpMessagingTemplate - that would basically hand me pub/sub
 * routing for free, which defeats the point of the exercise.
 */
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);

    public static final String USERNAME_ATTR = "username";

    private final SessionRegistry sessionRegistry;
    private final MessageRepository messageRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // last-seen-alive timestamp + the actual session, per session id. Separate from
    // SessionRegistry (which is keyed by username for routing) because the heartbeat sweep
    // needs to scan every open connection regardless of who owns it.
    private final Map<String, Long> lastActivityBySessionId = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> sessionsById = new ConcurrentHashMap<>();

    @Value("${chatapp.websocket.heartbeat-timeout-ms:90000}")
    private long heartbeatTimeoutMs;

    public ChatWebSocketHandler(SessionRegistry sessionRegistry, MessageRepository messageRepository) {
        this.sessionRegistry = sessionRegistry;
        this.messageRepository = messageRepository;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String username = (String) session.getAttributes().get(USERNAME_ATTR);
        // shouldn't happen - the handshake interceptor already rejects bad/missing usernames
        // before we get here. If this fires it's a wiring bug, not bad user input.
        if (username == null) {
            closeQuietly(session, CloseStatus.POLICY_VIOLATION.withReason("unauthenticated"));
            return;
        }
        sessionRegistry.register(username, session);
        lastActivityBySessionId.put(session.getId(), System.currentTimeMillis());
        sessionsById.put(session.getId(), session);
        log.info("ws connected: user={} session={} openSessionsForUser={}",
                username, session.getId(), sessionRegistry.getSessions(username).size());

        // catch them up on anything that came in while they were offline. doing this on
        // connect (not just relying on the REST history call) means a client that only speaks
        // websocket still gets queued stuff right away.
        deliverPendingMessages(username, session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        lastActivityBySessionId.put(session.getId(), System.currentTimeMillis());
        String username = (String) session.getAttributes().get(USERNAME_ATTR);

        WsMessage incoming;
        try {
            incoming = objectMapper.readValue(message.getPayload(), WsMessage.class);
        } catch (Exception e) {
            sendError(session, "malformed_json");
            return;
        }

        switch (incoming.getType() == null ? "" : incoming.getType()) {
            case "PING" -> sendRaw(session, new WsMessage("PONG", null, null, null, null, null, System.currentTimeMillis()));
            case "CHAT" -> handleChat(session, username, incoming);
            default -> sendError(session, "unknown_type:" + incoming.getType());
        }
    }

    private void handleChat(WebSocketSession session, String fromUsername, WsMessage incoming) throws IOException {
        if (incoming.getTo() == null || incoming.getContent() == null || incoming.getContent().isBlank()) {
            sendError(session, "missing_to_or_content");
            return;
        }
        if (incoming.getContent().length() > 4000) {
            sendError(session, "content_too_long");
            return;
        }

        String clientMessageId = incoming.getClientMessageId() != null
                ? incoming.getClientMessageId() : UUID.randomUUID().toString();

        boolean recipientOnline = sessionRegistry.isOnline(incoming.getTo());

        // save first, THEN try to deliver live. reasoning: if we delivered first and the app
        // died before the DB write landed, the recipient could've already seen a message that
        // then vanishes from history on reload - much worse than the alternative (a duplicate
        // delivery attempt on retry, which clientMessageId's unique constraint below de-dupes).
        Message saved = new Message();
        saved.setClientMessageId(clientMessageId);
        saved.setSenderUsername(fromUsername);
        saved.setRecipientUsername(incoming.getTo());
        saved.setContent(incoming.getContent());
        saved.setCreatedAt(Instant.now());
        saved.setStatus(recipientOnline ? Message.DeliveryStatus.DELIVERED : Message.DeliveryStatus.PENDING);

        try {
            messageRepository.save(saved);
        } catch (org.springframework.dao.DataIntegrityViolationException dup) {
            // client probably retried a send it never got an ACK for (e.g. brief disconnect
            // right after sending). the message is already saved from the first attempt, so
            // just swallow this instead of erroring out.
            log.info("duplicate clientMessageId={}, ignoring resend", clientMessageId);
        }

        WsMessage outbound = new WsMessage("CHAT", fromUsername, incoming.getTo(),
                incoming.getContent(), clientMessageId, saved.getStatus().name(),
                saved.getCreatedAt().toEpochMilli());

        if (recipientOnline) {
            // push to every open tab/session the recipient has, not just "a" session
            for (WebSocketSession recipientSession : sessionRegistry.getSessions(incoming.getTo())) {
                sendRaw(recipientSession, outbound);
            }
        }

        // ack goes back to the sender only - lets their UI flip "sending..." -> "sent"/"delivered"
        sendRaw(session, new WsMessage("ACK", fromUsername, incoming.getTo(), null,
                clientMessageId, saved.getStatus().name(), outbound.getTimestamp()));
    }

    private void deliverPendingMessages(String username, WebSocketSession session) {
        var pending = messageRepository.findByRecipientUsernameAndStatus(username, Message.DeliveryStatus.PENDING);
        for (Message m : pending) {
            sendRaw(session, new WsMessage("CHAT", m.getSenderUsername(), username, m.getContent(),
                    m.getClientMessageId(), "DELIVERED", m.getCreatedAt().toEpochMilli()));
            m.setStatus(Message.DeliveryStatus.DELIVERED);
        }
        if (!pending.isEmpty()) {
            messageRepository.saveAll(pending);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String username = (String) session.getAttributes().get(USERNAME_ATTR);
        if (username != null) {
            sessionRegistry.unregister(username, session);
            log.info("ws disconnected: user={} session={} reason={}", username, session.getId(), status);
        }
        lastActivityBySessionId.remove(session.getId());
        sessionsById.remove(session.getId());
    }

    /**
     * Heartbeat sweep - runs on its own timer, not tied to any one connection's lifecycle.
     * The whole point is catching sockets that never get a clean close (laptop lid shut,
     * wifi just dies) - the OS/container network stack can sit on those for a lot longer than
     * a chat app should tolerate before noticing, and meanwhile we'd keep "successfully"
     * routing messages into a socket that's actually dead.
     *
     * Every sweep: ping anything that's been quiet for less than the timeout, force-close
     * anything that's been quiet for longer (which then runs through afterConnectionClosed
     * for cleanup like normal).
     */
    @Scheduled(fixedDelayString = "${chatapp.websocket.heartbeat-interval-ms:30000}")
    public void heartbeatSweep() {
        long now = System.currentTimeMillis();
        lastActivityBySessionId.forEach((sessionId, lastSeen) -> {
            WebSocketSession session = sessionsById.get(sessionId);
            if (session == null) return;
            if (now - lastSeen > heartbeatTimeoutMs) {
                log.info("session {} timed out (quiet for {}ms), closing", sessionId, now - lastSeen);
                closeQuietly(session, CloseStatus.GOING_AWAY.withReason("heartbeat_timeout"));
                String username = (String) session.getAttributes().get(USERNAME_ATTR);
                if (username != null) sessionRegistry.unregister(username, session);
                lastActivityBySessionId.remove(sessionId);
                sessionsById.remove(sessionId);
            } else {
                sendRaw(session, new WsMessage("PING", null, null, null, null, null, now));
            }
        });
    }

    private void sendRaw(WebSocketSession session, WsMessage payload) {
        if (!session.isOpen()) return;
        try {
            // WebSocketSession isn't thread-safe for concurrent sends, and a single session can
            // get written to from more than one place at once (an incoming chat + a heartbeat
            // ping landing at the same time, say) - so every write for a given session goes
            // through this synchronized block.
            synchronized (session) {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
            }
        } catch (IOException e) {
            log.warn("send failed for session {}, closing it", session.getId(), e);
            closeQuietly(session, CloseStatus.SERVER_ERROR);
            String username = (String) session.getAttributes().get(USERNAME_ATTR);
            if (username != null) sessionRegistry.unregister(username, session);
        }
    }

    private void sendError(WebSocketSession session, String reason) {
        sendRaw(session, new WsMessage("ERROR", null, null, reason, null, null, System.currentTimeMillis()));
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try { session.close(status); } catch (IOException ignored) { }
    }
}
