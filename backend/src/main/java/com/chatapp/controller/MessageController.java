package com.chatapp.controller;

import com.chatapp.dto.MessageDto;
import com.chatapp.repository.MessageRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Just history - the websocket handles anything live. Need this for when a client first loads
 * the page (no socket open yet) so the conversation isn't empty until something new happens.
 */
@RestController
@RequestMapping("/api/messages")
public class MessageController {

    private final MessageRepository messageRepository;

    public MessageController(MessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    @GetMapping("/{otherUsername}")
    public List<MessageDto> conversation(@RequestParam String self, @PathVariable String otherUsername) {
        return messageRepository
                .findBySenderUsernameAndRecipientUsernameOrRecipientUsernameAndSenderUsernameOrderByCreatedAtAsc(
                        self, otherUsername, self, otherUsername)
                .stream()
                .map(MessageDto::from)
                .toList();
    }
}
