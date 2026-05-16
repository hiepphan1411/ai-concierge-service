package com.hotelvista.aiconcierge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class OllamaService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${ollama.api.url:http://localhost:11434/api/chat}")
    private String ollamaApiUrl;

    @Value("${ollama.model:qwen2.5:7b}")
    private String ollamaModel;

    public OllamaService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Gọi Ollama local model và trả về response text
     *
     * @param prompt Nội dung prompt gửi đến model
     * @return Response text từ model
     */
    public String generateResponse(String prompt) {
        try {
            Map<String, Object> requestBody = buildRequestBody(prompt);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            log.debug("Calling Ollama API at {} with model {}", ollamaApiUrl, ollamaModel);

            ResponseEntity<String> response = restTemplate.exchange(
                    ollamaApiUrl,
                    HttpMethod.POST,
                    entity,
                    String.class
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                String content = parseOllamaResponse(response.getBody());
                log.debug("Ollama response received successfully");
                return content;
            }

            log.error("Ollama API returned status: {}", response.getStatusCode());
            return fallbackResponse();

        } catch (Exception e) {
            log.error("Error calling Ollama API: {}", e.getMessage(), e);
            return fallbackResponse();
        }
    }

    /**
     * Build request body theo format của Ollama /api/chat
     */
    private Map<String, Object> buildRequestBody(String prompt) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", ollamaModel);
        requestBody.put("stream", false);

        List<Map<String, String>> messages = new ArrayList<>();
        Map<String, String> userMessage = new HashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", prompt);
        messages.add(userMessage);

        requestBody.put("messages", messages);
        return requestBody;
    }

    /**
     * Parse JSON response từ Ollama
     */
    private String parseOllamaResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode message = root.path("message");
            if (!message.isMissingNode()) {
                return message.path("content").asText("");
            }
            log.error("Unexpected Ollama response format: {}", responseBody);
            return fallbackResponse();
        } catch (Exception e) {
            log.error("Error parsing Ollama response: {}", e.getMessage(), e);
            return fallbackResponse();
        }
    }

    private String fallbackResponse() {
        return "Xin lỗi, hệ thống AI đang gặp sự cố. Vui lòng thử lại sau hoặc liên hệ lễ tân để được hỗ trợ.";
    }
}
