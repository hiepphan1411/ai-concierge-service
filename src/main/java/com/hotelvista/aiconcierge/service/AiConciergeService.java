package com.hotelvista.aiconcierge.service;

import com.hotelvista.aiconcierge.dto.aichat.AiChatResponse;
import com.hotelvista.aiconcierge.dto.aichat.ChatHistoryDTO;
import com.hotelvista.aiconcierge.dto.aichat.MessageDTO;
import com.hotelvista.aiconcierge.model.AiConversationHistory;
import com.hotelvista.aiconcierge.model.ChatMessage;
import com.hotelvista.aiconcierge.model.ChatSession;
import com.hotelvista.aiconcierge.repository.AiConversationHistoryRepository;
import com.hotelvista.aiconcierge.repository.ChatMessageRepository;
import com.hotelvista.aiconcierge.repository.ChatSessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;


@Slf4j
@Service
public class AiConciergeService {

    private final OllamaService ollamaService;
    private final ConversationMemory conversationMemory;
    private final HotelIntentOrchestrator hotelIntentOrchestrator;

    @Value("${gateway.service.url:http://localhost:8080}")
    private String gatewayServiceUrl;

    @Autowired
    private ChatSessionRepository chatSessionRepository;

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    @Autowired
    private AiConversationHistoryRepository conversationHistoryRepository;

    @Autowired
    public AiConciergeService(OllamaService ollamaService,
                              ConversationMemory conversationMemory,
                              HotelIntentOrchestrator hotelIntentOrchestrator) {
        this.ollamaService = ollamaService;
        this.conversationMemory = conversationMemory;
        this.hotelIntentOrchestrator = hotelIntentOrchestrator;
    }

    /**
     * Xử lý câu hỏi của user qua Intent Orchestrator
     */
    public String[] getChatResponseWithIntent(String userId, String message) {
        try {
            AiConversationHistory history = getOrCreateConversationHistory(userId);
            List<String> historyMessages = conversationMemory.get(userId);

            String[] result = hotelIntentOrchestrator.processMessage(userId, message, historyMessages);
            String detectedIntent = result[0];
            String response = result[1];

            conversationMemory.add(userId, "User: " + message);
            conversationMemory.add(userId, "Assistant: " + response);

            saveConversationToMongoDB(userId, history, message, response);
            saveToChatSession(userId, message, response);

            if (shouldHandoffToStaff(message, response)) {
                createStaffHandoffSession(userId, message, response);
            }

            return result;

        } catch (Exception e) {
            log.error("Error processing chat for user {}: {}", userId, e.getMessage(), e);
            return new String[]{
                "error",
                "Xin lỗi! Đã xảy ra sự cố, Vui lòng kiểm tra lại"
            };
        }
    }

    /**
     * Backward-compatible wrapper
     */
    public String getChatResponse(String userId, String message) {
        return getChatResponseWithIntent(userId, message)[1];
    }

    /**
     * Phiên bản đầy đủ
     */
    public AiChatResponse getChatResponseFull(String userId, String message) {
        try {
            AiConversationHistory history = getOrCreateConversationHistory(userId);
            List<String> historyMessages = conversationMemory.get(userId);

            AiChatResponse richResponse = hotelIntentOrchestrator.processMessageFull(userId, message, historyMessages);
            String detectedIntent = richResponse.getIntent() != null ? richResponse.getIntent() : "general";
            String response = richResponse.getContent();

            conversationMemory.add(userId, "User: " + message);
            conversationMemory.add(userId, "Assistant: " + response);

            saveConversationToMongoDB(userId, history, message, response);
            saveToChatSession(userId, message, response);

            if (shouldHandoffToStaff(message, response)) {
                createStaffHandoffSession(userId, message, response);
            }

            return richResponse;

        } catch (Exception e) {
            log.error("Error processing full chat for user {}: {}", userId, e.getMessage(), e);
            return new AiChatResponse(
                "Xin lỗi! Đã xảy ra sự cố, Vui lòng kiểm tra lại", false
            );
        }
    }

    /**
     * Bắt đầu một phiên chat AI
     */
    public String startNewChatSession(String userId) {
        try {
            String sessionId = UUID.randomUUID().toString();

            AiConversationHistory newHistory = new AiConversationHistory();
            newHistory.setUserId(userId);
            newHistory.setSessionId(sessionId);
            newHistory.setHistory(new ArrayList<>());
            newHistory.setCreatedAt(LocalDateTime.now());
            newHistory.setLastUpdated(LocalDateTime.now());
            newHistory.addEntry("assistant",
                    "Hello! I'm Vista's AI Concierge. I can help you with room information, " +
                    "availability, hotel amenities, services, and more. How may I assist you today? ");

            conversationHistoryRepository.save(newHistory);
            conversationMemory.clear(userId);

            log.info("Created new AI chat session {} for user {}", sessionId, userId);
            return sessionId;

        } catch (Exception e) {
            log.error("Error starting new chat session: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to start new chat session", e);
        }
    }

    /**
     * Lấy danh sách tất cả các phiên chat AI của user
     */
    public List<ChatHistoryDTO> getUserChatHistory(String userId) {
        try {
            List<AiConversationHistory> histories =
                    conversationHistoryRepository.findByUserIdOrderByLastUpdatedDesc(userId);

            return histories.stream()
                    .map(history -> {
                        String title = generateChatTitle(history);
                        String lastMsg = getLastMessage(history);
                        return new ChatHistoryDTO(
                                history.getSessionId(),
                                title,
                                lastMsg,
                                history.getLastUpdated(),
                                history.getHistory().size()
                        );
                    })
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Error getting chat history for user {}: {}", userId, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Lấy tất cả tin nhắn trong một phiên chat AI
     */
    public List<MessageDTO> getSessionMessages(String userId, String sessionId) {
        try {
            Optional<AiConversationHistory> historyOpt =
                    conversationHistoryRepository.findByUserIdAndSessionId(userId, sessionId);

            if (historyOpt.isEmpty()) {
                log.warn("No session found for user {} and session {}", userId, sessionId);
                return Collections.emptyList();
            }

            return historyOpt.get().getHistory().stream()
                    .map(entry -> {
                        boolean showCards = shouldShowRoomCards(entry.getContent());
                        return new MessageDTO(
                                entry.getRole(),
                                entry.getContent(),
                                entry.getTimestamp(),
                                showCards
                        );
                    })
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Error getting session messages: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Xoá một phiên chat AI
     */
    public void deleteChatSession(String userId, String sessionId) {
        try {
            conversationHistoryRepository.deleteByUserIdAndSessionId(userId, sessionId);
            log.info("Deleted chat session {} for user {}", sessionId, userId);
        } catch (Exception e) {
            log.error("Error deleting session {}: {}", sessionId, e.getMessage(), e);
            throw new RuntimeException("Failed to delete chat session", e);
        }
    }

    /**
     * Kiểm tra xem message có liên quan đến phòng để hiển thị room card không
     */
    public boolean shouldShowRoomCards(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase();
        return lower.contains("room") ||
                lower.contains("book") ||
                lower.contains("available") ||
                lower.contains("suite") ||
                lower.contains("accommodation") ||
                lower.contains("phòng") ||
                lower.contains("đặt phòng");
    }

    /**
     * Xoá toàn bộ lịch sử chat AI của một user
     */
    public void clearConversationHistory(String userId) {
        try {
            conversationMemory.clear(userId);
            List<AiConversationHistory> histories =
                    conversationHistoryRepository.findByUserIdOrderByLastUpdatedDesc(userId);
            histories.forEach(h ->
                    conversationHistoryRepository.deleteByUserIdAndSessionId(userId, h.getSessionId())
            );
            log.info("Cleared all conversation history for user: {}", userId);
        } catch (Exception e) {
            log.error("Error clearing conversation history: {}", e.getMessage(), e);
        }
    }

    // PRIVATE - MongoDB Persistence

    private AiConversationHistory getOrCreateConversationHistory(String userId) {
        return conversationHistoryRepository
                .findTopByUserIdOrderByLastUpdatedDesc(userId)
                .orElseGet(() -> {
                    String sessionId = UUID.randomUUID().toString();
                    AiConversationHistory newHistory = new AiConversationHistory();
                    newHistory.setUserId(userId);
                    newHistory.setSessionId(sessionId);
                    newHistory.setHistory(new ArrayList<>());
                    newHistory.setCreatedAt(LocalDateTime.now());
                    newHistory.setLastUpdated(LocalDateTime.now());
                    log.info("Auto-created new session {} for user {}", sessionId, userId);
                    return newHistory;
                });
    }

    private void saveConversationToMongoDB(String userId, AiConversationHistory history,
                                           String userMessage, String aiResponse) {
        try {
            history.addEntry("user", userMessage);
            history.addEntry("assistant", aiResponse);
            history.setLastUpdated(LocalDateTime.now());
            conversationHistoryRepository.save(history);
        } catch (Exception e) {
            log.error("Error saving conversation to MongoDB: {}", e.getMessage(), e);
        }
    }

    private void saveToChatSession(String userId, String userMessage, String aiResponse) {
        try {
            ChatSession session = chatSessionRepository.findByCustomer_Id(userId)
                    .stream()
                    .filter(s -> s.getStatus() != ChatSession.ChatStatus.RESOLVED)
                    .findFirst()
                    .orElseGet(() -> createNewChatSession(userId));

            ChatMessage userMsg = new ChatMessage();
            userMsg.setSessionId(session.getSessionId());
            userMsg.setSenderId(userId);
            userMsg.setSenderType(ChatMessage.SenderType.CUSTOMER);
            userMsg.setContent(userMessage);
            userMsg.setTimestamp(LocalDateTime.now());
            userMsg.setRead(true);
            chatMessageRepository.save(userMsg);

            ChatMessage aiMsg = new ChatMessage();
            aiMsg.setSessionId(session.getSessionId());
            aiMsg.setSenderId("AI");
            aiMsg.setSenderType(ChatMessage.SenderType.AI);
            aiMsg.setContent(aiResponse);
            aiMsg.setTimestamp(LocalDateTime.now());
            aiMsg.setRead(false);
            aiMsg.setShowRoomCards(shouldShowRoomCards(userMessage));
            chatMessageRepository.save(aiMsg);

            session.setLastMessage(aiResponse);
            session.setLastMessageTime(LocalDateTime.now());
            session.setUpdatedAt(LocalDateTime.now());

            if (session.getConversationContext() == null) {
                session.setConversationContext(new ArrayList<>());
            }
            session.getConversationContext().add(userMessage);
            if (session.getConversationContext().size() > 20) {
                session.getConversationContext().remove(0);
            }

            chatSessionRepository.save(session);

        } catch (Exception e) {
            log.error("Error saving chat to session: {}", e.getMessage(), e);
        }
    }


    // PRIVATE - Chat Session Creation

    private ChatSession createNewChatSession(String userId) {
        ChatSession session = new ChatSession();
        session.setSessionId(UUID.randomUUID().toString());

        ChatSession.CustomerInfo customer = new ChatSession.CustomerInfo();
        customer.setId(userId);
        customer.setFullName("Phan Phước Hiệp");     // TODO: Lấy từ backend service
        customer.setEmail("phanphuochiep2004@gmail.com"); // TODO: Lấy từ backend service
        customer.setAvatar(null);
        session.setCustomer(customer);

        session.setStatus(ChatSession.ChatStatus.WAITING);
        session.setPriority(ChatSession.Priority.MEDIUM);
        session.setUnreadCount(0);
        session.setCreatedAt(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());
        session.setConversationContext(new ArrayList<>());

        return chatSessionRepository.save(session);
    }

    // PRIVATE - Staff Handoff Logic
    private boolean shouldHandoffToStaff(String userMessage, String aiResponse) {
        String lowerMessage = userMessage.toLowerCase();
        String lowerResponse = aiResponse.toLowerCase();

        boolean hasComplexRequest = lowerMessage.contains("accessibility") ||
                lowerMessage.contains("wheelchair") ||
                lowerMessage.contains("special needs") ||
                lowerMessage.contains("disability") ||
                lowerMessage.contains("complaint") ||
                lowerMessage.contains("problem") ||
                lowerMessage.contains("issue") ||
                lowerMessage.contains("manager") ||
                lowerMessage.contains("speak to someone") ||
                lowerMessage.contains("talk to staff") ||
                lowerMessage.contains("human") ||
                lowerMessage.contains("khiếu nại") ||
                lowerMessage.contains("phàn nàn") ||
                lowerMessage.contains("gặp nhân viên");

        boolean aiSuggestsHandoff = lowerResponse.contains("connect you with") ||
                lowerResponse.contains("staff member") ||
                lowerResponse.contains("team can assist") ||
                lowerResponse.contains("front desk");

        return hasComplexRequest || aiSuggestsHandoff;
    }

    private void createStaffHandoffSession(String userId, String userMessage, String aiResponse) {
        try {
            ChatSession session = chatSessionRepository.findByCustomer_Id(userId)
                    .stream()
                    .filter(s -> s.getStatus() == ChatSession.ChatStatus.WAITING)
                    .findFirst()
                    .orElseGet(() -> createNewChatSession(userId));

            session.setStatus(ChatSession.ChatStatus.WAITING);
            session.setPriority(ChatSession.Priority.HIGH);
            session.setAiHandoffReason(determineHandoffReason(userMessage));
            session.setUnreadCount(session.getUnreadCount() + 1);
            session.setUpdatedAt(LocalDateTime.now());
            chatSessionRepository.save(session);

            ChatMessage handoffMsg = new ChatMessage();
            handoffMsg.setSessionId(session.getSessionId());
            handoffMsg.setSenderId("SYSTEM");
            handoffMsg.setSenderType(ChatMessage.SenderType.AI);
            handoffMsg.setContent(
                    "This conversation has been flagged for staff assistance. " +
                    "A team member will be with you shortly."
            );
            handoffMsg.setTimestamp(LocalDateTime.now());
            handoffMsg.setRead(false);
            chatMessageRepository.save(handoffMsg);

            log.info("Created staff handoff for user: {} - Reason: {}",
                    userId, session.getAiHandoffReason());

        } catch (Exception e) {
            log.error("Error creating staff handoff: {}", e.getMessage(), e);
        }
    }

    private String determineHandoffReason(String userMessage) {
        String lower = userMessage.toLowerCase();
        if (lower.contains("accessibility") || lower.contains("wheelchair"))
            return "Accessibility requirements";
        if (lower.contains("cancel") || lower.contains("hủy"))
            return "Cancellation inquiry";
        if (lower.contains("complaint") || lower.contains("problem") ||
                lower.contains("khiếu nại") || lower.contains("phàn nàn"))
            return "Customer concern";
        if (lower.contains("special") || lower.contains("custom") || lower.contains("đặc biệt"))
            return "Special request";
        if (lower.contains("manager") || lower.contains("supervisor"))
            return "Management request";
        return "Complex inquiry requiring staff assistance";
    }

    // PRIVATE - Helpers
    private String generateChatTitle(AiConversationHistory history) {
        if (history.getHistory() == null || history.getHistory().isEmpty()) {
            return "New conversation";
        }
        return history.getHistory().stream()
                .filter(msg -> "user".equals(msg.getRole()))
                .findFirst()
                .map(msg -> {
                    String content = msg.getContent();
                    if (content != null && content.length() > 40) {
                        return content.substring(0, 40) + "...";
                    }
                    return content != null ? content : "New conversation";
                })
                .orElse("New conversation");
    }

    private String getLastMessage(AiConversationHistory history) {
        if (history.getHistory() == null || history.getHistory().isEmpty()) return "";
        AiConversationHistory.ConversationEntry lastMsg =
                history.getHistory().get(history.getHistory().size() - 1);
        return lastMsg.getContent() != null ? lastMsg.getContent() : "";
    }
}
