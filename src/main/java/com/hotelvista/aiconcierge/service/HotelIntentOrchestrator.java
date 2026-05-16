package com.hotelvista.aiconcierge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotelvista.aiconcierge.dto.aichat.AiChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
public class HotelIntentOrchestrator {

    @Autowired
    private OllamaService ollamaService;

    @Autowired
    private HotelTools hotelTools;

    @Autowired
    private ObjectMapper objectMapper;

    private static final List<String> VALID_INTENTS = List.of(
            "room_inquiry", "availability_check", "amenity_inquiry",
            "policy_inquiry", "service_inquiry",
            "booking_request",
            "booking_room_select",
            "booking_stay_type_select",
            "booking_date_select",
            "booking_service_select",
            "booking_confirm",
            "booking_cancel",
            "invoice_inquiry", "complaint", "general"
    );

    /**
     * Xử lý tin nhắn
     */
    public String[] processMessage(String userId, String userMessage, List<String> historyMessages) {
        try {
            String detectedIntent = detectIntent(userMessage, historyMessages);
            log.info("Detected intent: {} for userId: {}", detectedIntent, userId);

            String toolData = executeTool(detectedIntent, userMessage, userId);
            String finalResponse = formatResponse(userMessage, toolData, detectedIntent, historyMessages);

            return new String[]{detectedIntent, finalResponse};
        } finally {
            currentParams.remove();
        }
    }

    /**
     * Xử lý và trả về AiChatResponse đầy đủ với rich UI
     */
    public AiChatResponse processMessageFull(String userId, String userMessage, List<String> historyMessages) {
        try {
            String detectedIntent = detectIntent(userMessage, historyMessages);
            log.info("Detected intent: {} for userId: {}", detectedIntent, userId);

            String toolData = executeTool(detectedIntent, userMessage, userId);
            String finalResponse = formatResponse(userMessage, toolData, detectedIntent, historyMessages);

            AiChatResponse response = new AiChatResponse(finalResponse, false);
            response.setIntent(detectedIntent);

            enrichResponseWithUI(response, detectedIntent, userMessage, userId, toolData);

            return response;
        } finally {
            currentParams.remove();
        }
    }

    private String detectIntent(String userMessage, List<String> historyMessages) {
        String historyContext = buildHistoryContext(historyMessages);

        String intentPrompt = """
                You are an intent classifier for Vista Hotel's AI Concierge.
                Analyze the user's message IN THE CONTEXT of the previous conversation to determine the next intent.

                Booking Flow Progression:
                1. booking_request: user wants to start booking
                2. booking_room_select: user selected a room type
                3. booking_stay_type_select: user selected DAILY or HOURLY
                4. booking_date_select: user provided date/time
                5. booking_service_select: user selected or declined services
                6. booking_confirm: user confirms booking

                CRITICAL RULES:
                - If user wants to book but did NOT choose room type -> booking_request.
                - If user chooses a room type such as Deluxe Room, Standard Room, Suite Room -> booking_room_select.
                - If user chooses "theo ngày", "daily", "theo giờ", "hourly" -> booking_stay_type_select.
                - If user provides actual date/time such as "từ 16-20/5", "2026-05-11", "9 giờ sáng" -> booking_date_select.
                - If user selects or declines services -> booking_service_select.
                - If user confirms -> booking_confirm.
                - NEVER classify room selection as booking_stay_type_select.
                - NEVER classify DAILY/HOURLY selection as booking_date_select.
                - NEVER invent prices, room names, services, or booking details.

                Return ONLY a JSON object with this exact format (ensure bookingType is mapped from "theo ngày" to DAILY and "theo giờ" to HOURLY):
                {
                  "intent": "<intent_type>",
                  "params": {
                    "checkIn": "<date or null>",
                    "checkOut": "<date or null>",
                    "guests": <number or 0>,
                    "serviceType": "<airport_shuttle|spa|dining|laundry|tour|null>",
                    "bookingType": "<DAILY|HOURLY|null>",
                    "roomTypeId": "<id or null>",
                    "roomTypeName": "<room name or null>",
                    "customerId": "<id or null>"
                  }
                }
                Intent types:
                - room_inquiry:
                  User is asking about room types, prices, room details, room features, capacity, or room comparison.
                  Examples:
                  "Phòng Deluxe bao nhiêu tiền?"
                  "Có phòng nào view biển không?"
                
                - availability_check:
                  User is asking whether rooms are available for a specific date/time.
                  Examples:
                  "Ngày mai còn phòng không?"
                  "Tối nay còn phòng theo giờ không?"
                
                - amenity_inquiry:
                  User is asking about hotel facilities or amenities.
                  Examples:
                  "Khách sạn có hồ bơi không?"
                  "Có gym không?"
                
                - policy_inquiry:
                  User is asking about hotel policies such as check-in/check-out time, cancellation, smoking, pets, refund.
                  Examples:
                  "Mấy giờ nhận phòng?"
                  "Có được mang thú cưng không?"
                
                - service_inquiry:
                  User is asking about hotel services such as shuttle, spa, dining, laundry, tours.
                  Examples:
                  "Có đưa đón sân bay không?"
                  "Có dịch vụ giặt đồ không?"
                
                - booking_request:
                  User wants to START a booking/reservation but has NOT selected a room yet.
                  Examples:
                  "Tôi muốn đặt phòng"
                  "Cho tôi thuê phòng"
                
                - booking_room_select:
                  User selected or confirmed a specific room type.
                  Use this intent ONLY when the user chooses a room.
                  Examples:
                  "Tôi chọn phòng Deluxe"
                  "Đặt cho tôi phòng Standard"
                
                - booking_stay_type_select:
                  User selected booking type DAILY/theo ngày or HOURLY/theo giờ.
                  Use this ONLY after a room has already been selected.
                  Examples:
                  "Theo ngày"
                  "Đặt theo giờ"
                  "Hourly"
                
                - booking_date_select:
                  User provided check-in/check-out dates or booking hours.
                  Use this ONLY when the user provides actual date/time information.
                  Examples:
                  "Ngày mai"
                  "Từ 8h đến 10h"
                  "Check in ngày 20"
                  "Ở 2 tiếng"
                
                - booking_service_select:
                  User selects additional services or declines services.
                  Examples:
                  "Thêm spa"
                  "Không cần dịch vụ"
                
                - booking_confirm:
                  User confirms the booking.
                  Examples:
                  "Xác nhận"
                  "Đồng ý"
                  "Ok đặt đi"
                
                - booking_cancel:
                  User cancels the booking flow.
                  Examples:
                  "Hủy"
                  "Thôi không đặt nữa"
                
                - invoice_inquiry:
                  User is asking about invoices, booking history, payment, or bills.
                  Examples:
                  "Cho tôi xem hóa đơn"
                  "Lịch sử đặt phòng"
                
                - complaint:
                  User is expressing dissatisfaction, reporting issues, or making complaints.
                  Examples:
                  "Phòng quá bẩn"
                  "Dịch vụ tệ"
                
                - general:
                  Greetings, thanks, casual conversation, or anything unrelated.
                  Examples:
                  "Xin chào"
                  "Cảm ơn"

                Previous conversation context:
                """ + historyContext + """

                User message: \"""" + userMessage + """
                \"\"\"

                Return ONLY the JSON, no explanation, no markdown.
                """;

        try {
            String rawJson = ollamaService.generateResponse(intentPrompt);
            String cleanJson = rawJson.trim()
                    .replaceAll("(?s)```json\\s*", "")
                    .replaceAll("(?s)```\\s*", "")
                    .trim();

            JsonNode node = objectMapper.readTree(cleanJson);
            String intent = node.path("intent").asText("general").toLowerCase();

            if (!VALID_INTENTS.contains(intent)) {
                log.warn("Unknown intent '{}', defaulting to general", intent);
                return "general";
            }

            storeParams(node.path("params"));
            return intent;

        } catch (Exception e) {
            log.warn("Intent detection failed ({}), defaulting to general", e.getMessage());
            return "general";
        }
    }

    private String executeTool(String intent, String userMessage, String userId) {
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

                case "booking_request" -> hotelTools.getRoomTypes();

                case "booking_room_select" -> {
                    IntentParams params = currentParams.get();
                    yield "ROOM_SELECTED: " + 
                          (params.roomTypeName != null ? params.roomTypeName : params.roomTypeId);
                }

                case "booking_stay_type_select" -> {
                    IntentParams params = currentParams.get();
                    yield "BOOKING_TYPE_SELECTED: " + params.bookingType;
                }

                case "booking_date_select" -> hotelTools.getServicesInfo();

                case "booking_service_select" -> hotelTools.getServicesInfo();

                case "invoice_inquiry" -> hotelTools.getCustomerBookings(userId);

                case "complaint" -> """
                        THÔNG TIN BAN QUAN HỆ KHÁCH HÀNG:
                        Chúng tôi thành thật xin lỗi vì sự bất tiện bạn đã trải qua.
                        Đội ngũ quan hệ khách hàng của chúng tôi sẵn sàng 24/7 để giải quyết mọi thắc mắc.
                        - Lễ tân: số máy lẻ 0
                        - Quản lý quan hệ khách hàng: số máy lẻ 100
                        - Email: guestrelations@vistahotel.vn
                        """;

                default -> "";
            };
        } catch (Exception e) {
            log.error("Tool execution failed for intent '{}': {}", intent, e.getMessage());
            return "";
        }
    }

    private String formatResponse(String userMessage, String toolData,
                                  String intent, List<String> historyMessages) {
        String historyContext = buildHistoryContext(historyMessages);

        String rolePrompt = """
                Bạn là AI Concierge của khách sạn Vista - chuyên nghiệp, thân thiện và hữu ích.

                QUY TẮC:
                1. TRẢ LỜI ĐÚNG NGÔN NGỮ CỦA NGƯỜI DÙNG (Tiếng Việt hoặc Tiếng Anh). TUYỆT ĐỐI KHÔNG ĐƯỢC SỬ DỤNG TIẾNG TRUNG QUỐC. IF THE USER SPEAKS VIETNAMESE, YOU MUST REPLY IN VIETNAMESE.
                2. KHÔNG dùng markdown formatting (**, *, #) - chỉ dùng văn bản thuần
                3. Tối đa 200 từ - ngắn gọn và rõ ràng
                4. Luôn gợi ý bước tiếp theo hoặc hỏi thêm thông tin
                5. Đề cập giá bằng VND
                """;

        String finalPrompt;

        if ("booking_request".equals(intent)) {
            return "Bạn muốn đặt phòng nào? Vui lòng chọn một loại phòng bên dưới.";
        }

        if ("booking_room_select".equals(intent)) {
            return "Bạn muốn đặt phòng theo ngày hay theo giờ?";
        }

        if ("booking_stay_type_select".equals(intent)) {
            IntentParams params = currentParams.get();
            if ("HOURLY".equalsIgnoreCase(params.bookingType)) {
                return "Bạn vui lòng chọn ngày, giờ bắt đầu và số giờ thuê.";
            }
            return "Bạn vui lòng chọn ngày nhận phòng và ngày trả phòng.";
        }

        if ("booking_date_select".equals(intent)) {
            return "Bạn có muốn thêm dịch vụ nào cho đơn đặt phòng không?";
        }

        if ("booking_service_select".equals(intent)) {
            return "Đã ghi nhận lựa chọn dịch vụ. Bạn vui lòng xác nhận đặt phòng để chuyển sang thanh toán.";
        }

        if ("booking_confirm".equals(intent)) {
            return "Đơn đặt phòng đã sẵn sàng. Bạn có thể chuyển sang trang đặt phòng để hoàn tất thanh toán.";
        }

        if ("invoice_inquiry".equals(intent)) {
            finalPrompt = rolePrompt + """

                    DỮ LIỆU HÓA ĐƠN/ĐẶT PHÒNG:
                    """ + (toolData.isBlank() ? "Không tìm thấy đặt phòng nào." : toolData) + """

                    NHIỆM VỤ: Trình bày thông tin đặt phòng một cách rõ ràng và thân thiện.
                    Tin nhắn: """ + userMessage;

        } else if (toolData != null && !toolData.isBlank()) {
            finalPrompt = rolePrompt + """

                    LỊCH SỬ CUỘC HỘI THOẠI:
                    """ + historyContext + """

                    DỮ LIỆU KHÁCH SẠN CHO YÊU CẦU NÀY:
                    """ + toolData + """

                    TIN NHẮN NGƯỜI DÙNG: """ + userMessage + """

                    PHẢN HỒI CỦA BẠN (thân thiện, ngắn gọn, dựa trên dữ liệu):
                    """;
        } else {
            finalPrompt = rolePrompt + """

                    LỊCH SỬ CUỘC HỘI THOẠI:
                    """ + historyContext + """

                    TIN NHẮN NGƯỜI DÙNG: """ + userMessage + """

                    PHẢN HỒI CỦA BẠN:
                    """;
        }

        return ollamaService.generateResponse(finalPrompt);
    }

    //RICH UI ENRICHMENT

    private void enrichResponseWithUI(AiChatResponse response, String intent,
                                      String userMessage, String userId, String toolData) {
        switch (intent) {
            case "room_inquiry", "booking_request" -> {
                List<Map<String, Object>> rooms = hotelTools.getRoomTypesRaw();
                if (!rooms.isEmpty()) {
                    response.setUiType("ROOM_GRID");
                    response.setUiData(rooms);
                    response.setShowRoomCards(true);
                }
            }

            case "availability_check" -> {
                List<Map<String, Object>> rooms = hotelTools.getRoomTypesRaw();
                response.setUiType("ROOM_GRID");
                response.setUiData(rooms);
            }

            case "booking_room_select" -> {
                response.setUiType("BOOKING_TYPE_SELECT");
                response.setUiData(Map.of(
                    "options", List.of(
                        Map.of("value", "DAILY", "label", "Theo ngày", "description", "Check-in 14:00, Check-out 12:00"),
                        Map.of("value", "HOURLY", "label", "Theo giờ", "description", "Tối thiểu 1 giờ, linh hoạt")
                    )
                ));
            }

            case "booking_stay_type_select" -> {
                IntentParams params = currentParams.get();
                String bookingType = params != null && params.bookingType != null ? params.bookingType : "DAILY";
                response.setUiType("DATE_PICKER");
                response.setUiData(Map.of("bookingType", bookingType));
            }

            case "booking_date_select" -> {
                List<Map<String, Object>> services = hotelTools.getServicesRaw();
                response.setUiType("SERVICE_LIST");
                response.setUiData(services);
            }

            case "booking_service_select" -> {
                List<Map<String, Object>> services = hotelTools.getServicesRaw();
                response.setUiType("SERVICE_LIST");
                response.setUiData(services);
            }

            case "booking_confirm" -> {
                response.setUiType("BOOKING_CONFIRM");
                response.setUiData(Map.of(
                    "action", "REDIRECT_TO_BOOKING",
                    "bookingPageUrl", "/customer/bookingPage",
                    "message", "Chuyển đến trang đặt phòng để hoàn tất thanh toán"
                ));
            }

            case "invoice_inquiry" -> {
                List<Map<String, Object>> bookings = hotelTools.getCustomerBookingsRaw(userId);
                if (!bookings.isEmpty()) {
                    response.setUiType("INVOICE_VIEW");
                    response.setUiData(bookings);
                }
            }

            case "service_inquiry" -> {
                List<Map<String, Object>> services = hotelTools.getServicesRaw();
                response.setUiType("SERVICE_LIST");
                response.setUiData(services);
            }

            default -> {
                // No rich UI for general/other intents
            }
        }
    }

    private static final ThreadLocal<IntentParams> currentParams =
            ThreadLocal.withInitial(IntentParams::new);

    private void storeParams(JsonNode paramsNode) {
        IntentParams params = new IntentParams();
        if (!paramsNode.isMissingNode()) {
            params.checkIn     = nullIfNull(paramsNode.path("checkIn").asText(null));
            params.checkOut    = nullIfNull(paramsNode.path("checkOut").asText(null));
            params.guests      = paramsNode.path("guests").asInt(0);
            params.serviceType = nullIfNull(paramsNode.path("serviceType").asText(null));
            params.bookingType = nullIfNull(paramsNode.path("bookingType").asText(null));
            params.roomTypeId  = nullIfNull(paramsNode.path("roomTypeId").asText(null));
            params.roomTypeName = nullIfNull(paramsNode.path("roomTypeName").asText(null));
            params.customerId  = nullIfNull(paramsNode.path("customerId").asText(null));
        }
        currentParams.set(params);
    }

    private String nullIfNull(String value) {
        return "null".equalsIgnoreCase(value) || "".equals(value) ? null : value;
    }

    private String buildHistoryContext(List<String> historyMessages) {
        if (historyMessages == null || historyMessages.isEmpty()) return "(Không có tin nhắn trước)";
        int start = Math.max(0, historyMessages.size() - 6);
        return String.join("\n", historyMessages.subList(start, historyMessages.size()));
    }

    private static class IntentParams {
        String checkIn;
        String checkOut;
        int guests;
        String serviceType;
        String bookingType;
        String roomTypeId;
        String roomTypeName;
        String customerId;
    }
}
