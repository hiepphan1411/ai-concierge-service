package com.hotelvista.aiconcierge.controller;

import com.hotelvista.aiconcierge.dto.chat.ChatMessageDTO;
import com.hotelvista.aiconcierge.dto.chat.ChatSessionDTO;
import com.hotelvista.aiconcierge.dto.chat.SendMessageRequest;
import com.hotelvista.aiconcierge.service.ChatSupportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/chat-support")
public class ChatSupportController {

    @Autowired
    private ChatSupportService chatSupportService;

    /**
     * Lấy danh sách chat đang được giao cho staff
     * GET /api/chat-support/staff/{staffId}/chats
     */
    @GetMapping("/staff/{staffId}/chats")
    public ResponseEntity<List<ChatSessionDTO>> getStaffChats(@PathVariable String staffId) {
        try {
            List<ChatSessionDTO> chats = chatSupportService.getStaffChats(staffId);
            return ResponseEntity.ok(chats);
        } catch (Exception e) {
            log.error("Error getting staff chats: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Lấy danh sách chat đang chờ được staff tiếp nhận
     * GET /api/chat-support/pending
     */
    @GetMapping("/pending")
    public ResponseEntity<List<ChatSessionDTO>> getPendingChats() {
        try {
            List<ChatSessionDTO> chats = chatSupportService.getAllPendingChats();
            return ResponseEntity.ok(chats);
        } catch (Exception e) {
            log.error("Error getting pending chats: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Lấy toàn bộ tin nhắn trong một phiên chat
     * GET /api/chat-support/chats/{sessionId}/messages
     */
    @GetMapping("/chats/{sessionId}/messages")
    public ResponseEntity<List<ChatMessageDTO>> getChatMessages(@PathVariable String sessionId) {
        try {
            List<ChatMessageDTO> messages = chatSupportService.getChatMessages(sessionId);
            return ResponseEntity.ok(messages);
        } catch (Exception e) {
            log.error("Error getting chat messages: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Staff gửi tin nhắn vào phiên chat.
     * POST /api/chat-support/chats/{sessionId}/messages
     */
    @PostMapping("/chats/{sessionId}/messages")
    public ResponseEntity<ChatMessageDTO> sendMessage(
            @PathVariable String sessionId,
            @RequestBody SendMessageRequest request) {
        try {
            ChatMessageDTO message = chatSupportService.sendStaffMessage(
                    sessionId,
                    request.getContent(),
                    request.getStaffId()
            );
            return ResponseEntity.ok(message);
        } catch (Exception e) {
            log.error("Error sending message: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Giao phiên chat cho một staff cụ thể
     * POST /api/chat-support/chats/{sessionId}/assign
     */
    @PostMapping("/chats/{sessionId}/assign")
    public ResponseEntity<Void> assignChat(
            @PathVariable String sessionId,
            @RequestBody Map<String, String> request) {
        try {
            String staffId = request.get("staffId");
            String staffName = request.get("staffName");
            chatSupportService.assignChatToStaff(sessionId, staffId, staffName);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Error assigning chat: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Đánh dấu phiên chat là đã giải quyết
     * PATCH /api/chat-support/chats/{sessionId}/resolve
     */
    @PatchMapping("/chats/{sessionId}/resolve")
    public ResponseEntity<Void> resolveChat(@PathVariable String sessionId) {
        try {
            chatSupportService.markChatAsResolved(sessionId);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Error resolving chat: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Lấy toàn bộ lịch sử chat của một khách hàng
     * GET /api/chat-support/customers/{customerId}/history
     */
    @GetMapping("/customers/{customerId}/history")
    public ResponseEntity<List<ChatMessageDTO>> getChatHistory(@PathVariable String customerId) {
        try {
            List<ChatMessageDTO> history = chatSupportService.getChatHistory(customerId);
            return ResponseEntity.ok(history);
        } catch (Exception e) {
            log.error("Error getting chat history: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
