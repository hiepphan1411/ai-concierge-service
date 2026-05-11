package com.hotelvista.aiconcierge.dto.chat;

import com.hotelvista.aiconcierge.model.ChatSession;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ChatSessionDTO {
    private String id;
    private String sessionId;
    private CustomerDTO customer;
    private ChatSession.ChatStatus status;
    private StaffDTO assignedStaff;
    private String lastMessage;
    private LocalDateTime lastMessageTime;
    private int unreadCount;
    private ChatSession.Priority priority;
    private String aiHandoffReason;
    private LocalDateTime createdAt;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class CustomerDTO {
        private String id;
        private String fullName;
        private String email;
        private String avatar;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class StaffDTO {
        private String id;
        private String fullName;
    }
}
