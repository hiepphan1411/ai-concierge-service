package com.hotelvista.aiconcierge.dto.chat;

import com.hotelvista.aiconcierge.model.ChatMessage;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ChatMessageDTO {
    private String id;
    private String sessionId;
    private String senderId;
    private ChatMessage.SenderType senderType;
    private String content;
    private LocalDateTime timestamp;
    private boolean isRead;
    private boolean showRoomCards;
}
