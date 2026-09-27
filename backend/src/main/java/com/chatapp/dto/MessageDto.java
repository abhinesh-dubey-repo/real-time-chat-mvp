package com.chatapp.dto;

import com.chatapp.model.Message;

public record MessageDto(String clientMessageId, String from, String to, String content, long timestamp, String status) {
    public static MessageDto from(Message m) {
        return new MessageDto(m.getClientMessageId(), m.getSenderUsername(), m.getRecipientUsername(),
                m.getContent(), m.getCreatedAt().toEpochMilli(), m.getStatus().name());
    }
}
