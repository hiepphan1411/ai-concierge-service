package com.hotelvista.aiconcierge.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

// Quản lý API keys
@Slf4j
@Component
@Data
public class GeminiApiKeyConfig {

    @Value("${gemini.api.key:}")
    private String primaryApiKey;

    @Value("${gemini.api.keys:}")
    private String apiKeysString;

    @Value("${gemini.api.url:https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent}")
    private String apiUrl;

    private List<String> apiKeys;
    private AtomicInteger currentKeyIndex = new AtomicInteger(0);

    public void init() {
        if (apiKeysString != null && !apiKeysString.trim().isEmpty()) {

            apiKeys = Arrays.stream(apiKeysString.split(","))
                    .map(String::trim)
                    .filter(key -> !key.isEmpty())
                    .toList();
            log.info("Loaded {} Gemini API keys from GEMINI_API_KEYS", apiKeys.size());
        } else if (primaryApiKey != null && !primaryApiKey.trim().isEmpty()) {

            apiKeys = List.of(primaryApiKey);
            log.info("Loaded 1 Gemini API key from GEMINI_API_KEY");
        } else {
            apiKeys = List.of();
            log.warn("No Gemini API keys configured!");
        }
    }

    public String getNextApiKey() {
        if (apiKeys.isEmpty()) {
            throw new RuntimeException("No Gemini API keys available");
        }
        int index = currentKeyIndex.getAndIncrement() % apiKeys.size();
        return apiKeys.get(index);
    }

    public String getApiKey(int index) {
        if (apiKeys.isEmpty()) {
            throw new RuntimeException("No Gemini API keys available");
        }
        return apiKeys.get(index % apiKeys.size());
    }

    public int getApiKeyCount() {
        return apiKeys.size();
    }

    public List<String> getAllApiKeys() {
        return apiKeys;
    }
}
