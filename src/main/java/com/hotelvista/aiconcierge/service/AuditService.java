package com.hotelvista.aiconcierge.service;

import com.hotelvista.aiconcierge.model.AiAuditLog;
import com.hotelvista.aiconcierge.repository.AiAuditLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

// Ghi audit log bất đồng bộ (@Async)
@Slf4j
@Service
public class AuditService {

    @Autowired
    private AiAuditLogRepository auditLogRepository;

    @Async
    public void logChatSuccess(String userId, String userMessage, String aiResponse,
                               String detectedIntent, long processingTimeMs, String ipAddress) {
        try {
            AiAuditLog auditLog = AiAuditLog.builder()
                    .userId(userId)
                    .action("chat")
                    .detectedIntent(detectedIntent)
                    .userMessage(truncate(userMessage, 500))
                    .aiResponse(truncate(aiResponse, 1000))
                    .processingTimeMs(processingTimeMs)
                    .ipAddress(ipAddress)
                    .success(true)
                    .timestamp(LocalDateTime.now())
                    .build();

            auditLogRepository.save(auditLog);
        } catch (Exception e) {
            log.error("Failed to save audit log for user {}: {}", userId, e.getMessage());
        }
    }


    @Async
    public void logChatFailure(String userId, String userMessage,
                               String errorReason, String ipAddress) {
        try {
            AiAuditLog auditLog = AiAuditLog.builder()
                    .userId(userId)
                    .action("chat")
                    .userMessage(truncate(userMessage, 500))
                    .errorReason(truncate(errorReason, 300))
                    .ipAddress(ipAddress)
                    .success(false)
                    .timestamp(LocalDateTime.now())
                    .build();

            auditLogRepository.save(auditLog);
        } catch (Exception e) {
            log.error("Failed to save failure audit log for user {}: {}", userId, e.getMessage());
        }
    }

    @Async
    public void logAction(String userId, String action, boolean success, String ipAddress) {
        try {
            AiAuditLog auditLog = AiAuditLog.builder()
                    .userId(userId)
                    .action(action)
                    .success(success)
                    .ipAddress(ipAddress)
                    .timestamp(LocalDateTime.now())
                    .build();

            auditLogRepository.save(auditLog);
        } catch (Exception e) {
            log.error("Failed to save action audit log: {}", e.getMessage());
        }
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return null;
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...[truncated]";
    }
}
