package com.chatapp.repository;

import com.chatapp.model.Message;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MessageRepository extends JpaRepository<Message, Long> {

    // full conversation between two users, either direction
    List<Message> findBySenderUsernameAndRecipientUsernameOrRecipientUsernameAndSenderUsernameOrderByCreatedAtAsc(
            String senderA, String recipientA, String senderB, String recipientB);

    List<Message> findByRecipientUsernameAndStatus(String recipientUsername, Message.DeliveryStatus status);
}
