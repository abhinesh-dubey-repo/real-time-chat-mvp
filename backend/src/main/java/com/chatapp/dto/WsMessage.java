package com.chatapp.dto;

/**
 * Wire format for the websocket protocol. Kept flat and small on purpose - no STOMP frames,
 * just plain JSON with only the fields the routing logic actually needs.
 *
 * type values:
 *   CHAT  - sending a message (client->server) or receiving one (server->client)
 *   ACK   - server confirming a CHAT was saved + whether it got delivered live or queued
 *   PING/PONG - heartbeat, see ChatWebSocketHandler
 *   ERROR - something was wrong with the message we got
 */
public class WsMessage {
    private String type;
    private String from;
    private String to;
    private String content;
    private String clientMessageId;
    private String status;
    private long timestamp;

    public WsMessage() {}

    public WsMessage(String type, String from, String to, String content, String clientMessageId, String status, long timestamp) {
        this.type = type;
        this.from = from;
        this.to = to;
        this.content = content;
        this.clientMessageId = clientMessageId;
        this.status = status;
        this.timestamp = timestamp;
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getFrom() { return from; }
    public void setFrom(String from) { this.from = from; }
    public String getTo() { return to; }
    public void setTo(String to) { this.to = to; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getClientMessageId() { return clientMessageId; }
    public void setClientMessageId(String clientMessageId) { this.clientMessageId = clientMessageId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}
