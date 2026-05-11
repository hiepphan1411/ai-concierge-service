package com.hotelvista.aiconcierge.security;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PromptSanitizer {

    private static final int MAX_INPUT_LENGTH = 2000;

    // Các pattern nguy hiểm
    private static final List<String> INJECTION_PATTERNS = List.of(
            // English patterns
            "ignore all previous",
            "ignore previous instructions",
            "forget your instructions",
            "forget everything",
            "you are now",
            "act as",
            "pretend to be",
            "pretend you are",
            "disregard all",
            "override instructions",
            "your new instructions",
            "system prompt",
            "jailbreak",
            // Vietnamese patterns
            "bỏ qua tất cả",
            "bỏ qua hướng dẫn",
            "quên tất cả",
            "quên hướng dẫn",
            "bạn bây giờ là",
            "giả vờ là",
            "giả làm",
            "vượt qua hướng dẫn",
            // Database attack patterns
            "drop table",
            "select *",
            "union select",
            "execute sql",
            "show database",
            "show tables",
            // Script injection
            "<script",
            "javascript:",
            "onerror=",
            "onload="
    );

    /**
     * Làm sạch và kiểm tra an toàn input trước khi gửi AI
     *
     * @param input Tin nhắn từ user
     * @return Input đã trim, sẵn sàng dùng
     * @throws SecurityException         nếu phát hiện prompt injection
     * @throws IllegalArgumentException  nếu input quá dài hoặc null
     */
    public String sanitize(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("Message cannot be empty");
        }

        // Kiểm tra độ dài
        if (input.length() > MAX_INPUT_LENGTH) {
            throw new IllegalArgumentException(
                    "Message too long (max " + MAX_INPUT_LENGTH + " characters). " +
                    "Your message has " + input.length() + " characters."
            );
        }

        // Kiểm tra injection patterns
        String lowerInput = input.toLowerCase();
        for (String pattern : INJECTION_PATTERNS) {
            if (lowerInput.contains(pattern.toLowerCase())) {
                throw new SecurityException(
                        "Your message contains restricted content. Please rephrase your question."
                );
            }
        }

        return input.trim();
    }
}
