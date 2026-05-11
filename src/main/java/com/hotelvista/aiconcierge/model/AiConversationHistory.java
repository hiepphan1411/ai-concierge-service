package com.hotelvista.aiconcierge.model;

import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

// Mỗi document = 1 phiên chat AI
@Data
@AllArgsConstructor
@NoArgsConstructor
@Document(collection = "ai_conversation_history")
public class AiConversationHistory {

    @Id
    private String id;

    private String userId;
    private String sessionId;

    private List<ConversationEntry> history;

    private LocalDateTime createdAt;
    private LocalDateTime lastUpdated;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ConversationEntry {
        private String role; // "user" hoặc "assistant"
        private String content;
        private LocalDateTime timestamp;
    }

    /**
     * Thêm một entry mới vào lịch sử hội thoại
     */
    public void addEntry(String role, String content) {
        if (this.history == null) {
            this.history = new ArrayList<>();
        }
        this.history.add(new ConversationEntry(role, content, LocalDateTime.now()));
        this.lastUpdated = LocalDateTime.now();
    }
}
