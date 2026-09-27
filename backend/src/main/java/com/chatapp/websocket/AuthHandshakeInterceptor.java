package com.chatapp.websocket;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * Grabs the username off the handshake request and validates it before the upgrade even
 * happens - a bad/missing username means the connection never opens, instead of opening an
 * anonymous socket that then has to police its first message.
 *
 * Has to be a query param (?username=...) because the browser WebSocket API doesn't let you
 * set custom headers on the handshake the way a normal fetch/XHR would.
 *
 * No real auth here - there's no password, so "username" is just whatever the client claims.
 * Fine for this MVP (see README), but obviously not something to ship as-is.
 */
@Component
public class AuthHandshakeInterceptor implements HandshakeInterceptor {

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                    WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String username = extractUsername(request);
        if (username == null || username.isBlank() || username.length() > 32) {
            response.setStatusCode(org.springframework.http.HttpStatus.BAD_REQUEST);
            return false;
        }
        attributes.put(ChatWebSocketHandler.USERNAME_ATTR, username.trim());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                WebSocketHandler wsHandler, Exception exception) {
        // nothing to do here
    }

    private String extractUsername(ServerHttpRequest request) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            return servletRequest.getServletRequest().getParameter("username");
        }
        return null;
    }
}
