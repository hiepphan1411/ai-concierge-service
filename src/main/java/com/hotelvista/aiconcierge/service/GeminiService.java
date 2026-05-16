package com.hotelvista.aiconcierge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotelvista.aiconcierge.config.GeminiApiKeyConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class GeminiService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final GeminiApiKeyConfig apiKeyConfig;
    private final ApiKeyManagementService apiKeyManagementService;

    public GeminiService(RestTemplate restTemplate, ObjectMapper objectMapper, 
                       GeminiApiKeyConfig apiKeyConfig, ApiKeyManagementService apiKeyManagementService) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.apiKeyConfig = apiKeyConfig;
        this.apiKeyManagementService = apiKeyManagementService;
        // Khởi tạo danh sách API keys
        this.apiKeyConfig.init();
    }

    /**
     * Tạo response từ prompt, tự động load-balance và retry với các API keys khác nhau
     */
    public String generateResponse(String prompt) {
        int maxAttempts = apiKeyConfig.getApiKeyCount();
        
        if (maxAttempts == 0) {
            log.error("No API keys available");
            return "Sorry, AI service is not properly configured. Please try again later.";
        }

        List<String> failedReasons = new java.util.ArrayList<>();

        // Thử từng API key tối đa maxAttempts lần
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {

                String apiKey = apiKeyManagementService.getNextAvailableApiKey();
                log.debug("Attempt {}/{} - selected key for request", attempt + 1, maxAttempts);

                // Gọi Gemini API với key này
                String response = callGeminiWithKey(apiKey, prompt);
                
                if (response != null) {
                    // Request thành công - ghi nhận
                    apiKeyManagementService.recordSuccess(apiKey);
                    log.info("Request succeeded on attempt {}", attempt + 1);
                    return response;
                }

                // Response là null = key bị rate limit hoặc quota exceeded
                apiKeyManagementService.recordFailure(apiKey, "Rate limit or quota exceeded");
                failedReasons.add("Attempt " + (attempt + 1) + ": Rate limited");
                log.warn("API key rate limited, trying next key...");
                
            } catch (Exception e) {
                log.warn("API call failed on attempt {}: {}", attempt + 1, e.getMessage());
                failedReasons.add("Attempt " + (attempt + 1) + ": " + e.getMessage());

            }
        }

        // Tất cả attempts đều thất bại
        log.error("All {} API key attempts failed", maxAttempts);
        for (String reason : failedReasons) {
            log.error("  - {}", reason);
        }
        
        return "Xin lỗi! Hệ thống đang gặp sự cố. Chúng tôi sẽ sớm quay lại -> Nói thẳng ra hết token, hỏi nhiều quá";
    }

    /**
     * Gọi Gemini API với key cụ thể
     * Trả về response string nếu OK, null nếu rate limited, ném exception nếu error
     */
    private String callGeminiWithKey(String apiKey, String prompt) {
        try {
            String url = apiKeyConfig.getApiUrl() + "?key=" + apiKey;

            // Build request body theo Gemini API format
            Map<String, Object> requestBody = new HashMap<>();

            Map<String, Object> content = new HashMap<>();
            Map<String, String> part = new HashMap<>();
            part.put("text", prompt);
            content.put("parts", List.of(part));
            requestBody.put("contents", List.of(content));

            // Generation config
            Map<String, Object> generationConfig = new HashMap<>();
            generationConfig.put("temperature", 0.7);
            generationConfig.put("topK", 40);
            generationConfig.put("topP", 0.95);
            generationConfig.put("maxOutputTokens", 1024);
            requestBody.put("generationConfig", generationConfig);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    String.class
            );

            if (response.getStatusCode() == HttpStatus.OK) {
                String parsedResponse = parseGeminiResponse(response.getBody());
                log.debug("Gemini API returned successfully");
                return parsedResponse;
                
            } else if (response.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                log.warn("Rate limit exceeded (429)");
                return null; // Signal to try next key
                
            } else {
                log.error("Gemini API returned error: {}", response.getStatusCode());
                String errorMsg = "API returned: " + response.getStatusCode();
                throw new RuntimeException(errorMsg);
            }

        } catch (RestClientException e) {
            log.warn("REST client exception: {}", e.getMessage());
            throw new RuntimeException("Connection failed: " + e.getMessage(), e);
            
        } catch (Exception e) {
            log.warn("Error calling Gemini API: {}", e.getMessage());
            throw new RuntimeException("API call error: " + e.getMessage(), e);
        }
    }

    /**
     * Parse JSON response từ Gemini API lấy text content
     */
    private String parseGeminiResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode candidates = root.path("candidates");

            if (candidates.isArray() && candidates.size() > 0) {
                JsonNode firstCandidate = candidates.get(0);
                JsonNode content = firstCandidate.path("content");
                JsonNode parts = content.path("parts");

                StringBuilder fullText = new StringBuilder();

                for (JsonNode part : parts) {
                    String text = part.path("text").asText();
                    fullText.append(text);
                }

                return fullText.toString();
            }

            log.error("Unexpected response format from Gemini API");
            return "Sorry, I received an unexpected response. Please try again.";

        } catch (Exception e) {
            log.error("Error parsing Gemini response: {}", e.getMessage(), e);
            return "Sorry, I couldn't process the response. Please try again.";
        }
    }
}

