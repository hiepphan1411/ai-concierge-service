package com.hotelvista.aiconcierge.model;

import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Document(collection = "chat_sessions")
public class ChatSession {

    @Id
    private String id;

    private String sessionId;

    private CustomerInfo customer;

    private ChatStatus status; // WAITING, ACTIVE, RESOLVED

    private StaffInfo assignedStaff;

    private String lastMessage;
    private LocalDateTime lastMessageTime;

    private int unreadCount;
    private Priority priority; // LOW, MEDIUM, HIGH
    private String aiHandoffReason;

    private List<String> conversationContext;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime resolvedAt;

    private Integer rating;
    private String feedback;

    public enum ChatStatus {
        WAITING,
        ACTIVE,
        RESOLVED
    }

    public enum Priority {
        LOW,
        MEDIUM,
        HIGH
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class CustomerInfo {
        private String id;
        private String fullName;
        private String email;
        private String avatar;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class StaffInfo {
        private String id;
        private String fullName;
        private String email;
    }
}
