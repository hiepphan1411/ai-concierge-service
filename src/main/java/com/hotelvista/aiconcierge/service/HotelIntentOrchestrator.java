package com.hotelvista.aiconcierge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class HotelIntentOrchestrator {

    @Autowired
    private GeminiService geminiService;

    @Autowired
    private HotelTools hotelTools;

    @Autowired
    private ObjectMapper objectMapper;

    /** Tên các intent hợp lệ */
    private static final List<String> VALID_INTENTS = List.of(
            "room_inquiry", "availability_check", "amenity_inquiry",
            "policy_inquiry", "service_inquiry", "booking_request",
            "complaint", "general"
    );

    /**
     * Xử lý tin nhắn của user theo pipeline Intent -> Tool -> Response.
     *
     * @param userId         ID người dùng
     * @param userMessage    Tin nhắn gốc
     * @param historyMessages Lịch sử hội thoại gần nhất (tối đa 6 entries)
     * @return [detectedIntent, finalResponse] - dùng mảng 2 phần tử
     */
    public String[] processMessage(String userId, String userMessage,
                                   List<String> historyMessages) {

        String detectedIntent = detectIntent(userMessage, historyMessages);
        log.info("Detected intent: {} for userId: {}", detectedIntent, userId);

        String toolData = executeTool(detectedIntent, userMessage);

        String finalResponse = formatResponse(userMessage, toolData, detectedIntent, historyMessages);

        return new String[]{ detectedIntent, finalResponse };
    }

    private String detectIntent(String userMessage, List<String> historyMessages) {
        String historyContext = buildHistoryContext(historyMessages);

        String intentPrompt = """
                You are an intent classifier for Vista Hotel's AI Concierge.
                Analyze the user's message and return ONLY a JSON object with this exact format:
                {
                  "intent": "<intent_type>",
                  "params": {
                    "checkIn": "<date or null>",
                    "checkOut": "<date or null>",
                    "guests": <number or 0>,
                    "serviceType": "<airport_shuttle|spa|dining|laundry|tour|null>"
                  }
                }

                Intent types:
                - room_inquiry: asking about room types, prices, room features
                - availability_check: checking if rooms are available for specific dates
                - amenity_inquiry: asking about hotel facilities and amenities
                - policy_inquiry: asking about check-in/out times, cancellation, pets, smoking
                - service_inquiry: asking about shuttle, spa, dining, laundry, tours
                - booking_request: wanting to make a reservation/booking
                - complaint: expressing dissatisfaction or reporting a problem
                - general: greetings, thank you, or anything else

                Previous conversation context:
                """ + historyContext + """

                User message: \"""" + userMessage + """
                \"\"\"

                Return ONLY the JSON, no explanation, no markdown.
                """;

        try {
            String rawJson = geminiService.generateResponse(intentPrompt);

            String cleanJson = rawJson.trim()
                    .replaceAll("(?s)```json\\s*", "")
                    .replaceAll("(?s)```\\s*", "")
                    .trim();

            JsonNode node = objectMapper.readTree(cleanJson);
            String intent = node.path("intent").asText("general").toLowerCase();

            // Validate intent hợp lệ
            if (!VALID_INTENTS.contains(intent)) {
                log.warn("Unknown intent '{}', defaulting to general", intent);
                return "general";
            }

            // Lưu params vào ThreadLocal để executeTool có thể đọc
            storeParams(node.path("params"));
            return intent;

        } catch (Exception e) {
            log.warn("Intent detection failed ({}), defaulting to general", e.getMessage());
            return "general";
        }
    }

    private String executeTool(String intent, String userMessage) {
        try {
            return switch (intent) {
                case "room_inquiry" -> hotelTools.getRoomTypes();

                case "availability_check" -> {
                    IntentParams params = currentParams.get();
                    yield hotelTools.checkRoomAvailability(
                            params.checkIn,
                            params.checkOut,
                            params.guests
                    );
                }

                case "amenity_inquiry" -> hotelTools.getAmenities();

                case "policy_inquiry" -> hotelTools.getHotelPolicies();

                case "service_inquiry" -> {
                    IntentParams params = currentParams.get();
                    String serviceType = params.serviceType != null ? params.serviceType : "general";
                    yield hotelTools.getServiceInfo(serviceType);
                }

                case "booking_request" -> {
                    // Lấy danh sách phòng + booking instructions
                    String rooms = hotelTools.getRoomTypes();
                    String policies = hotelTools.getHotelPolicies();
                    yield rooms + "\n\nBOOKING PROCESS:\n" +
                          "To make a reservation, our team needs:\n" +
                          "1. Check-in and check-out dates\n" +
                          "2. Number of guests\n" +
                          "3. Preferred room type\n" +
                          "4. Special requests (if any)\n\n" + policies;
                }

                case "complaint" -> """
                        GUEST RELATIONS INFORMATION:
                        We sincerely apologize for any inconvenience you've experienced.
                        Our guest relations team is available 24/7 to address your concerns.
                        - Front Desk: ext. 0
                        - Guest Relations Manager: ext. 100
                        - Email: guestrelations@vistahotel.vn
                        We take all feedback seriously and will work to resolve your issue promptly.
                        """;

                default -> // general - không cần tool data, Gemini trả lời trực tiếp
                        "";
            };
        } catch (Exception e) {
            log.error("Tool execution failed for intent '{}': {}", intent, e.getMessage());
            return "";
        } finally {
            currentParams.remove();
        }
    }

    private String formatResponse(String userMessage, String toolData,
                                  String intent, List<String> historyMessages) {
        String historyContext = buildHistoryContext(historyMessages);

        String finalPrompt;
        if (toolData != null && !toolData.isBlank()) {
            finalPrompt = """
                    You are Vista Hotel's AI Concierge - professional, warm, and helpful.

                    RULES:
                    1. Respond in the same language as the user (Vietnamese or English)
                    2. NEVER use markdown formatting (**, *, #) - use plain text only
                    3. Use CAPS for section headings if needed
                    4. Keep response concise: max 200 words
                    5. Always mention prices in VND (Vietnamese Dong)
                    6. Be proactive - offer to help with follow-up needs

                    CONVERSATION HISTORY:
                    """ + historyContext + """

                    HOTEL DATA FOR THIS QUERY:
                    """ + toolData + """

                    USER'S MESSAGE: """ + userMessage + """

                    YOUR RESPONSE (friendly, concise, based on the data above):
                    """;
        } else {

            finalPrompt = """
                    You are Vista Hotel's AI Concierge - professional, warm, and helpful.

                    RULES:
                    1. Respond in the same language as the user (Vietnamese or English)
                    2. NEVER use markdown formatting (**, *, #) - use plain text only
                    3. Keep response concise: max 150 words
                    4. If asked about rooms/bookings/services, offer to help
                    5. For complex requests, suggest connecting with our staff

                    CONVERSATION HISTORY:
                    """ + historyContext + """

                    USER'S MESSAGE: """ + userMessage + """

                    YOUR RESPONSE:
                    """;
        }

        return geminiService.generateResponse(finalPrompt);
    }

    private static final ThreadLocal<IntentParams> currentParams =
            ThreadLocal.withInitial(IntentParams::new);

    private void storeParams(JsonNode paramsNode) {
        IntentParams params = new IntentParams();
        if (!paramsNode.isMissingNode()) {
            params.checkIn  = nullIfNull(paramsNode.path("checkIn").asText(null));
            params.checkOut = nullIfNull(paramsNode.path("checkOut").asText(null));
            params.guests   = paramsNode.path("guests").asInt(0);
            params.serviceType = nullIfNull(paramsNode.path("serviceType").asText(null));
        }
        currentParams.set(params);
    }

    private String nullIfNull(String value) {
        return "null".equalsIgnoreCase(value) || "".equals(value) ? null : value;
    }

    private String buildHistoryContext(List<String> historyMessages) {
        if (historyMessages == null || historyMessages.isEmpty()) return "(No previous messages)";
        int start = Math.max(0, historyMessages.size() - 6);
        return String.join("\n", historyMessages.subList(start, historyMessages.size()));
    }

    private static class IntentParams {
        String checkIn;
        String checkOut;
        int guests;
        String serviceType;
    }
}
