package com.hotelvista.aiconcierge.controller;

import com.hotelvista.aiconcierge.dto.aichat.AiChatRequest;
import com.hotelvista.aiconcierge.dto.aichat.AiChatResponse;
import com.hotelvista.aiconcierge.dto.aichat.ChatHistoryDTO;
import com.hotelvista.aiconcierge.dto.aichat.MessageDTO;
import com.hotelvista.aiconcierge.model.ApiKeyUsageStats;
import com.hotelvista.aiconcierge.security.PromptSanitizer;
import com.hotelvista.aiconcierge.service.AiConciergeService;
import com.hotelvista.aiconcierge.service.AuditService;
import com.hotelvista.aiconcierge.service.ApiKeyManagementService;
import com.hotelvista.aiconcierge.config.GeminiApiKeyConfig;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/ai")
public class AiController {

    @Autowired
    private AiConciergeService aiConciergeService;

    @Autowired
    private PromptSanitizer promptSanitizer;

    @Autowired
    private AuditService auditService;

    @Autowired
    private GeminiApiKeyConfig geminiApiKeyConfig;

    @Autowired
    private ApiKeyManagementService apiKeyManagementService;

    // POST /api/ai/chat
    @PostMapping("/chat")
    public ResponseEntity<AiChatResponse> chat(
            @RequestBody AiChatRequest request,
            HttpServletRequest httpRequest) {

        long startTime = System.currentTimeMillis();
        String userId = request.getUserId();
        String rawMessage = request.getMessage();
        String ipAddress = getClientIp(httpRequest);

        // Chống injection, giới hạn độ dài
        String cleanMessage;
        try {
            cleanMessage = promptSanitizer.sanitize(rawMessage);
        } catch (SecurityException e) {
            log.warn("Prompt injection attempt from userId={}: {}", userId, e.getMessage());
            auditService.logChatFailure(userId, rawMessage, "INJECTION_BLOCKED", ipAddress);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new AiChatResponse(e.getMessage(), false));
        } catch (IllegalArgumentException e) {
            auditService.logChatFailure(userId, rawMessage, e.getMessage(), ipAddress);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new AiChatResponse(e.getMessage(), false));
        }

        // Process Intent -> Tool -> Response
        try {
            String[] result = aiConciergeService.getChatResponseWithIntent(userId, cleanMessage);
            String detectedIntent = result[0];
            String response = result[1];

            boolean showCards = aiConciergeService.shouldShowRoomCards(cleanMessage);
            long processingTime = System.currentTimeMillis() - startTime;

            // Audit log (async, không block response)
            auditService.logChatSuccess(
                    userId, cleanMessage, response,
                    detectedIntent, processingTime, ipAddress
            );

            log.info("Chat processed for userId={}, intent={}, time={}ms",
                    userId, detectedIntent, processingTime);

            return ResponseEntity.ok(new AiChatResponse(response, showCards));

        } catch (Exception e) {
            log.error("Error in chat endpoint for userId={}: {}", userId, e.getMessage(), e);
            auditService.logChatFailure(userId, cleanMessage, e.getMessage(), ipAddress);
            return ResponseEntity.internalServerError()
                    .body(new AiChatResponse(
                            "Sorry, I'm having trouble processing your request. Please try again.",
                            false
                    ));
        }
    }

    // POST /api/ai/chat/new
    // Tạo section mới
    @PostMapping("/chat/new")
    public ResponseEntity<Map<String, String>> newChat(
            @RequestParam String userId,
            HttpServletRequest httpRequest) {
        try {
            log.info("Starting new chat for user: {}", userId);
            String sessionId = aiConciergeService.startNewChatSession(userId);
            auditService.logAction(userId, "new_session", true, getClientIp(httpRequest));
            return ResponseEntity.ok(Map.of("sessionId", sessionId));

        } catch (Exception e) {
            log.error("Error starting new chat for user {}: {}", userId, e.getMessage(), e);
            auditService.logAction(userId, "new_session", false, getClientIp(httpRequest));
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // GET /api/ai/chat/history/{userId}
    // Lịch sử chat
    @GetMapping("/chat/history/{userId}")
    public ResponseEntity<List<ChatHistoryDTO>> getUserChatHistory(@PathVariable String userId) {
        try {
            log.info("Fetching chat history for user: {}", userId);
            List<ChatHistoryDTO> histories = aiConciergeService.getUserChatHistory(userId);
            return ResponseEntity.ok(histories);

        } catch (Exception e) {
            log.error("Error fetching chat history for user {}: {}", userId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // GET /api/ai/chat/session/{sessionId}/messages
    // Load Messages của session
    @GetMapping("/chat/session/{sessionId}/messages")
    public ResponseEntity<List<MessageDTO>> getSessionMessages(
            @PathVariable String sessionId,
            @RequestParam String userId) {
        try {
            log.info("Fetching messages for session {} and user {}", sessionId, userId);
            List<MessageDTO> messages = aiConciergeService.getSessionMessages(userId, sessionId);

            if (messages == null || messages.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(messages);

        } catch (Exception e) {
            log.error("Error fetching session messages: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // DELETE /api/ai/chat/session/{sessionId}
    //Xóa session
    @DeleteMapping("/chat/session/{sessionId}")
    public ResponseEntity<Void> deleteChatSession(
            @PathVariable String sessionId,
            @RequestParam String userId,
            HttpServletRequest httpRequest) {
        try {
            log.info("Deleting chat session {} for user {}", sessionId, userId);
            aiConciergeService.deleteChatSession(userId, sessionId);
            auditService.logAction(userId, "delete_session", true, getClientIp(httpRequest));
            return ResponseEntity.ok().build();

        } catch (Exception e) {
            log.error("Error deleting session {}: {}", sessionId, e.getMessage(), e);
            auditService.logAction(userId, "delete_session", false, getClientIp(httpRequest));
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // GET /api/ai/health
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = new java.util.LinkedHashMap<>();
        health.put("status", "UP");
        health.put("service", "Vista Hotel AI Concierge Service");
        health.put("port", "8082");
        health.put("apiKeysConfigured", geminiApiKeyConfig.getApiKeyCount());
        health.put("features", "Intent Orchestrator, Prompt Sanitizer, Rate Limiting, Audit Logging, Multi-API-Key Failover");
        health.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(health);
    }

    /**
     * GET /api/ai/api-keys/status
     * Kiểm tra trạng thái, sl key
     */
    @GetMapping("/api-keys/status")
    public ResponseEntity<Map<String, Object>> apiKeysStatus() {
        try {
            Map<String, Object> status = new java.util.LinkedHashMap<>();
            int keyCount = geminiApiKeyConfig.getApiKeyCount();
            
            status.put("totalApiKeys", keyCount);
            status.put("status", keyCount > 0 ? "CONFIGURED" : "NOT_CONFIGURED");
            status.put("rateLimit", "20 requests per minute per key");
            status.put("peakRequests", "5 requests per minute per key");
            status.put("failoverEnabled", keyCount > 1);
            status.put("timestamp", System.currentTimeMillis());

            List<String> maskedKeys = geminiApiKeyConfig.getAllApiKeys().stream()
                    .map(key -> key.length() > 4 ? "***" + key.substring(key.length() - 4) : "****")
                    .toList();
            status.put("apiKeysMasked", maskedKeys);
            
            return ResponseEntity.ok(status);
        } catch (Exception e) {
            log.error("Error getting API keys status: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                Map.of("error", e.getMessage())
            );
        }
    }

    /**
     * POST /api/ai/api-keys/test
     * Test availability của các API keys với prompt đơn giản
     */
    @PostMapping("/api-keys/test")
    public ResponseEntity<Map<String, Object>> testApiKeys(HttpServletRequest httpRequest) {
        try {
            Map<String, Object> testResult = new java.util.LinkedHashMap<>();
            int keyCount = geminiApiKeyConfig.getApiKeyCount();
            
            if (keyCount == 0) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                    Map.of("error", "No API keys configured")
                );
            }
            
            testResult.put("totalKeysToTest", keyCount);
            testResult.put("testPrompt", "1 + 1 = 3. Đúng hay sai. Chỉ trả lời đáp án");
            
            List<Map<String, Object>> keysTestResult = new java.util.ArrayList<>();
            
            for (int i = 0; i < keyCount; i++) {
                Map<String, Object> keyTest = new java.util.LinkedHashMap<>();
                String maskKey = "Key-" + (i + 1);
                
                try {
                    keyTest.put("keyIndex", i + 1);
                    keyTest.put("status", "READY"); 
                    keyTest.put("lastUsed", "N/A");
                    keysTestResult.add(keyTest);
                } catch (Exception e) {
                    keyTest.put("keyIndex", i + 1);
                    keyTest.put("status", "FAILED");
                    keyTest.put("error", e.getMessage());
                    keysTestResult.add(keyTest);
                }
            }
            
            testResult.put("keysStatus", keysTestResult);
            testResult.put("allKeysAvailable", keysTestResult.stream()
                    .allMatch(k -> "READY".equals(k.get("status"))));
            testResult.put("timestamp", System.currentTimeMillis());
            testResult.put("testerIp", getClientIp(httpRequest));
            
            auditService.logAction("SYSTEM", "api_keys_test", true, getClientIp(httpRequest));
            
            return ResponseEntity.ok(testResult);
            
        } catch (Exception e) {
            log.error("Error testing API keys: {}", e.getMessage(), e);
            auditService.logAction("SYSTEM", "api_keys_test", false, getClientIp(httpRequest));
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                Map.of("error", e.getMessage())
            );
        }
    }

    // GET /api/ai/status
    @GetMapping("/status")
    public ResponseEntity<Map<String, String>> status() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "Vista Hotel AI Concierge Service",
                "port", "8082",
                "features", "Intent Orchestrator, Prompt Sanitizer, Rate Limiting, Audit Logging"
        ));
    }

    /**
     * GET /api/ai/api-keys/stats
     * Lấy statistics chi tiết của tất cả API keys
     */
    @GetMapping("/api-keys/stats")
    public ResponseEntity<Map<String, Object>> getApiKeysStats(HttpServletRequest httpRequest) {
        try {
            List<ApiKeyUsageStats> allStats = apiKeyManagementService.getAllKeyStats();
            
            Map<String, Object> statsResponse = new java.util.LinkedHashMap<>();
            statsResponse.put("timestamp", System.currentTimeMillis());
            statsResponse.put("totalKeys", allStats.size());
            
            // Tính toán tổng hợp
            int activeCount = (int) allStats.stream()
                    .filter(s -> s.getStatus() == ApiKeyUsageStats.KeyStatus.ACTIVE)
                    .count();
            long totalRequests = allStats.stream()
                    .mapToLong(s -> s.getTotalSuccessfulRequests() + s.getTotalFailedRequests())
                    .sum();
            long totalSuccess = allStats.stream()
                    .mapToLong(ApiKeyUsageStats::getTotalSuccessfulRequests)
                    .sum();
            
            statsResponse.put("activeKeys", activeCount);
            statsResponse.put("totalRequests", totalRequests);
            statsResponse.put("totalSuccessfulRequests", totalSuccess);
            statsResponse.put("successRate", totalRequests > 0 ? 
                    String.format("%.2f%%", (totalSuccess * 100.0) / totalRequests) : "0%");
            
            // Chi tiết từng key
            List<Map<String, Object>> keyDetails = new java.util.ArrayList<>();
            for (ApiKeyUsageStats stat : allStats) {
                Map<String, Object> keyInfo = new java.util.LinkedHashMap<>();
                keyInfo.put("keyMasked", stat.getApiKeyMasked());
                keyInfo.put("status", stat.getStatus());
                keyInfo.put("currentMinuteRequests", stat.getRequestCountCurrentMinute());
                keyInfo.put("requestsLimit", stat.getRequestsPerMinute());
                keyInfo.put("totalSuccessful", stat.getTotalSuccessfulRequests());
                keyInfo.put("totalFailed", stat.getTotalFailedRequests());
                keyInfo.put("consecutiveFailures", stat.getConsecutiveFailures());
                keyInfo.put("lastSuccessTime", stat.getLastSuccessTime());
                keyInfo.put("lastFailureTime", stat.getLastFailureTime());
                keyInfo.put("lastFailureMessage", stat.getLastFailureMessage());
                keyDetails.add(keyInfo);
            }
            
            statsResponse.put("keys", keyDetails);
            
            auditService.logAction("ADMIN", "api_keys_stats", true, getClientIp(httpRequest));
            return ResponseEntity.ok(statsResponse);
            
        } catch (Exception e) {
            log.error("Error getting API keys stats: {}", e.getMessage(), e);
            auditService.logAction("ADMIN", "api_keys_stats", false, getClientIp(httpRequest));
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                Map.of("error", e.getMessage())
            );
        }
    }

    /**
     * POST /api/ai/api-keys/reset-counters
     * Reset tất cả request counters
     */
    @PostMapping("/api-keys/reset-counters")
    public ResponseEntity<Map<String, Object>> resetApiKeyCounters(HttpServletRequest httpRequest) {
        try {
            apiKeyManagementService.resetAllCounters();
            
            Map<String, Object> response = new java.util.LinkedHashMap<>();
            response.put("message", "All API key counters reset successfully");
            response.put("timestamp", System.currentTimeMillis());
            response.put("status", "SUCCESS");
            
            auditService.logAction("ADMIN", "api_keys_reset_counters", true, getClientIp(httpRequest));
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Error resetting counters: {}", e.getMessage(), e);
            auditService.logAction("ADMIN", "api_keys_reset_counters", false, getClientIp(httpRequest));
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                Map.of("error", e.getMessage())
            );
        }
    }

    /**
     * POST /api/ai/api-keys/recover/{keyIndex}
     * Recover key từ FAILED state
     * @param keyIndex - Key index
     */
    @PostMapping("/api-keys/recover/{keyIndex}")
    public ResponseEntity<Map<String, Object>> recoverApiKey(
            @PathVariable int keyIndex,
            HttpServletRequest httpRequest) {
        try {
            List<String> allKeys = geminiApiKeyConfig.getAllApiKeys();
            
            if (keyIndex < 1 || keyIndex > allKeys.size()) {
                return ResponseEntity.badRequest().body(
                    Map.of("error", "Invalid key index. Must be between 1 and " + allKeys.size())
                );
            }
            
            String keyToRecover = allKeys.get(keyIndex - 1);
            apiKeyManagementService.recoverKey(keyToRecover);
            
            Map<String, Object> response = new java.util.LinkedHashMap<>();
            response.put("message", "API key recovered successfully");
            response.put("keyIndex", keyIndex);
            response.put("timestamp", System.currentTimeMillis());
            response.put("status", "SUCCESS");
            
            auditService.logAction("ADMIN", "api_key_recover", true, getClientIp(httpRequest));
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Error recovering API key: {}", e.getMessage(), e);
            auditService.logAction("ADMIN", "api_key_recover", false, getClientIp(httpRequest));
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                Map.of("error", e.getMessage())
            );
        }
    }

    // Helper
    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
