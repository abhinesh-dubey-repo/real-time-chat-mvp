package com.chatapp.controller;

import com.chatapp.repository.MessageRepository;
import com.chatapp.websocket.SessionRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * No accounts table (no auth at all, really - see the handshake interceptor), so "contacts"
 * isn't a real directory - it's just "who's online right now" + "who you've messaged before".
 * Enough to demo two people chatting without needing a signup flow first.
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final MessageRepository messageRepository;
    private final SessionRegistry sessionRegistry;

    public UserController(MessageRepository messageRepository, SessionRegistry sessionRegistry) {
        this.messageRepository = messageRepository;
        this.sessionRegistry = sessionRegistry;
    }

    @GetMapping
    public List<Map<String, Object>> listContacts(@RequestParam String self) {
        // findAll() + filter in memory - fine at this scale, would need an indexed query
        // (or an actual users table) once there's real data volume
        Set<String> names = new LinkedHashSet<>(sessionRegistry.onlineUsernames());
        messageRepository.findAll().forEach(m -> {
            if (m.getSenderUsername().equals(self)) names.add(m.getRecipientUsername());
            if (m.getRecipientUsername().equals(self)) names.add(m.getSenderUsername());
        });
        names.remove(self);
        return names.stream()
                .map(u -> Map.<String, Object>of("username", u, "online", sessionRegistry.isOnline(u)))
                .toList();
    }
}
