package com.chatapp.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "messages", indexes = {
        // needed for the "get conversation between A and B" query - without it that's a full
        // table scan once there's more than a handful of rows
        @Index(name = "idx_conversation", columnList = "senderUsername, recipientUsername, createdAt")
})
@Getter
@Setter
@NoArgsConstructor
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // client-generated UUID, separate from the DB id. Client needs something to reference in
    // its own optimistic-UI state before the server responds, and it doubles as a de-dupe key
    // if the client ends up retrying a send.
    @Column(nullable = false, unique = true, length = 64)
    private String clientMessageId;

    @Column(nullable = false, length = 64)
    private String senderUsername;

    @Column(nullable = false, length = 64)
    private String recipientUsername;

    @Column(nullable = false, length = 4000)
    private String content;

    @Column(nullable = false)
    private Instant createdAt;

    // PENDING = saved but recipient was offline, delivered later on reconnect.
    // DELIVERED = actually pushed over an open socket.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DeliveryStatus status;

    public enum DeliveryStatus { PENDING, DELIVERED }
}
