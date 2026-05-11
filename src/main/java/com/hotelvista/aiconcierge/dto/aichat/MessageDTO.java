package com.hotelvista.aiconcierge.dto.aichat;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MessageDTO {
    private String sender; // "user" hoặc "assistant"
    private String content;
    private LocalDateTime timestamp;
    private boolean showRoomCards;
}
