package com.hotelvista.aiconcierge.dto.aichat;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatHistoryDTO {
    private String sessionId;
    private String title;
    private String lastMessage;
    private LocalDateTime lastMessageTime;
    private int messageCount;
}
