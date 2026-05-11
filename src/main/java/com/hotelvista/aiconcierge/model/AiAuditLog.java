package com.hotelvista.aiconcierge.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Document(collection = "ai_audit_logs")
public class AiAuditLog {

    @Id
    private String id;

    @Indexed
    private String userId;

    private String action;

    private String detectedIntent;

    private String userMessage;

    private String aiResponse;

    private Long processingTimeMs;

//    IP của người dùng
    private String ipAddress;

    private boolean success;

    private String errorReason;

    @Indexed
    private LocalDateTime timestamp;
}
