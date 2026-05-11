package com.hotelvista.aiconcierge.model;

import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;


@Data
@AllArgsConstructor
@NoArgsConstructor
@Document(collection = "chat_messages")
public class ChatMessage {

    @Id
    private String id;

    private String sessionId;

    private String senderId;
    private SenderType senderType; // CUSTOMER, AI, STAFF

    private String content;
    private LocalDateTime timestamp;

    private boolean isRead;

    private boolean showRoomCards;

    // Metadata mở rộng
    private String messageType;   // TEXT, IMAGE, FILE
    private String attachmentUrl;

    public enum SenderType {
        CUSTOMER,
        AI,
        STAFF
    }
}
