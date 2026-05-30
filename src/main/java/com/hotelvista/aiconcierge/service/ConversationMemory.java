package com.hotelvista.aiconcierge.service;

import com.hotelvista.aiconcierge.dto.aichat.AiChatResponse;
import com.hotelvista.aiconcierge.model.BookingState;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


@Component
public class ConversationMemory {

    private final Map<String, List<String>> conversations = new ConcurrentHashMap<>();
    private final Map<String, AiChatResponse.BookingDraft> bookingDrafts = new ConcurrentHashMap<>();
    private static final int MAX_HISTORY = 20;

    public List<String> get(String userId) {
        return conversations.getOrDefault(userId, new ArrayList<>());
    }

    public void add(String userId, String message) {
        List<String> history = conversations.computeIfAbsent(userId, k -> new ArrayList<>());
        history.add(message);

        // Chỉ giữ lại MAX_HISTORY tin nhắn cuối cùng
        if (history.size() > MAX_HISTORY) {
            history.remove(0);
        }
    }

    public void clear(String userId) {
        conversations.remove(userId);
        bookingDrafts.remove(userId);
    }

    public AiChatResponse.BookingDraft getBookingDraft(String userId) {
        return bookingDrafts.get(userId);
    }

    public AiChatResponse.BookingDraft getOrCreateBookingDraft(String userId) {
        return bookingDrafts.computeIfAbsent(userId, key -> {
            AiChatResponse.BookingDraft draft = new AiChatResponse.BookingDraft();
            draft.setConfirmed(false);
            draft.setState(BookingState.IDLE.name());
            return draft;
        });
    }

    public void saveBookingDraft(String userId, AiChatResponse.BookingDraft draft) {
        bookingDrafts.put(userId, draft);
    }

    public void clearBookingDraft(String userId) {
        bookingDrafts.remove(userId);
    }
}
