package com.hotelvista.aiconcierge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotelvista.aiconcierge.dto.aichat.AiChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class HotelIntentOrchestrator {

    @Autowired
    private GeminiService geminiService;

    @Autowired
    private HotelTools hotelTools;

    @Autowired
    private ConversationMemory conversationMemory;

    @Autowired
    private BookingFlowService bookingFlowService;

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
            AiChatResponse flowResponse = bookingFlowService.process(userId, userMessage);
            if (flowResponse != null) {
                return new String[]{flowResponse.getIntent(), flowResponse.getContent()};
            }

            String detectedIntent = detectIntent(userMessage, historyMessages);
            log.info("Detected intent: {} for userId: {}", detectedIntent, userId);

            AiChatResponse guardedBookingResponse = guardBookingIntent(userId, userMessage, detectedIntent);
            if (guardedBookingResponse != null) {
                return new String[]{guardedBookingResponse.getIntent(), guardedBookingResponse.getContent()};
            }

            String toolData = executeTool(detectedIntent, userMessage, userId);
            String finalResponse = formatResponse(userMessage, toolData, detectedIntent, historyMessages);
            if (bookingFlowService.hasActiveBooking(userId) && !detectedIntent.startsWith("booking_")) {
                finalResponse = finalResponse + "\n\nNếu anh/chị muốn, mình có thể tiếp tục bước đặt phòng đang thực hiện.";
            }

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
            AiChatResponse flowResponse = bookingFlowService.process(userId, userMessage);
            if (flowResponse != null) {
                log.info("Handled booking flow intent: {} for userId: {}", flowResponse.getIntent(), userId);
                return flowResponse;
            }

            String detectedIntent = detectIntent(userMessage, historyMessages);
            log.info("Detected intent: {} for userId: {}", detectedIntent, userId);

            AiChatResponse guardedBookingResponse = guardBookingIntent(userId, userMessage, detectedIntent);
            if (guardedBookingResponse != null) {
                return guardedBookingResponse;
            }

            String toolData = executeTool(detectedIntent, userMessage, userId);
            String finalResponse = formatResponse(userMessage, toolData, detectedIntent, historyMessages);
            if (bookingFlowService.hasActiveBooking(userId) && !detectedIntent.startsWith("booking_")) {
                finalResponse = finalResponse + "\n\nNếu anh/chị muốn, mình có thể tiếp tục bước đặt phòng đang thực hiện.";
            }

            AiChatResponse response = new AiChatResponse(finalResponse, false);
            response.setIntent(detectedIntent);

            enrichResponseWithUI(response, detectedIntent, userMessage, userId, toolData);

            return response;
        } finally {
            currentParams.remove();
        }
    }

    private AiChatResponse guardBookingIntent(String userId, String userMessage, String detectedIntent) {
        if (detectedIntent == null || !detectedIntent.startsWith("booking_")) {
            return null;
        }

        AiChatResponse response = bookingFlowService.process(userId, "dat phong " + userMessage);
        if (response != null) {
            log.info("Guarded booking fallback intent {} with state-machine response {}", detectedIntent, response.getIntent());
            return response;
        }

        AiChatResponse fallback = new AiChatResponse(
                "Em sẽ xử lý yêu cầu đặt phòng theo từng bước để tránh thiếu thông tin. Trước tiên anh/chị vui lòng cho em biết mình muốn đặt theo ngày hay theo giờ.",
                false
        );
        fallback.setIntent("booking_request");
        fallback.setUiType("BOOKING_TYPE_SELECT");
        fallback.setUiData(Map.of(
                "options", List.of(
                        Map.of("value", "DAILY", "label", "Theo ngày", "description", "Nhận phòng 14:00, trả phòng 12:00"),
                        Map.of("value", "HOURLY", "label", "Theo giờ", "description", "Linh hoạt trong ngày, tối thiểu 1 giờ")
                )
        ));
        return fallback;
    }

    private AiChatResponse processBookingFlow(String userId, String userMessage) {
        String lowerMessage = normalize(userMessage);
        AiChatResponse.BookingDraft draft = conversationMemory.getBookingDraft(userId);

        if (isBookingCancel(lowerMessage)) {
            conversationMemory.clearBookingDraft(userId);
            return simpleResponse("booking_cancel",
                    "Em đã hủy quy trình đặt phòng hiện tại. Nếu muốn, em có thể tư vấn lại từ đầu theo nhu cầu của quý khách.");
        }

        boolean activeBookingFlow = draft != null && !Boolean.TRUE.equals(draft.getConfirmed());
        if (!activeBookingFlow && !isBookingIntent(lowerMessage)) {
            return null;
        }

        if (draft == null) {
            draft = conversationMemory.getOrCreateBookingDraft(userId);
        }

        String bookingType = detectBookingType(lowerMessage);
        if (bookingType != null) {
            draft.setBookingType(bookingType);
        }

        if (draft.getBookingType() == null) {
            conversationMemory.saveBookingDraft(userId, draft);
            return bookingTypeSelectResponse(draft);
        }

        DateSelection dateSelection = parseDateSelection(userMessage, draft.getBookingType());
        if (dateSelection.checkInDate() != null) {
            draft.setCheckInDate(dateSelection.checkInDate());
        }
        if (dateSelection.checkOutDate() != null) {
            draft.setCheckOutDate(dateSelection.checkOutDate());
        }
        if (dateSelection.durationHours() != null) {
            draft.setDurationHours(dateSelection.durationHours());
        }
        if (dateSelection.guests() != null) {
            draft.setGuests(dateSelection.guests());
        } else if (draft.getGuests() == null) {
            draft.setGuests(1);
        }

        String validationError = validateBookingTime(draft);
        if (validationError != null) {
            conversationMemory.saveBookingDraft(userId, draft);
            AiChatResponse response = datePickerResponse(draft);
            response.setContent(validationError);
            return response;
        }

        if (needsDateSelection(draft)) {
            conversationMemory.saveBookingDraft(userId, draft);
            return datePickerResponse(draft);
        }

        List<Map<String, Object>> availableRooms = hotelTools.getAvailableRoomTypesRaw(
                draft.getBookingType(),
                draft.getCheckInDate(),
                draft.getCheckOutDate(),
                draft.getDurationHours(),
                draft.getGuests()
        );

        Map<String, Object> selectedRoom = findSelectedRoom(userMessage, availableRooms);
        if (selectedRoom != null) {
            draft.setRoomTypeId(Objects.toString(selectedRoom.get("roomTypeID"), null));
            draft.setRoomTypeName(Objects.toString(selectedRoom.get("typeName"), null));
        }

        if (draft.getRoomTypeId() == null) {
            conversationMemory.saveBookingDraft(userId, draft);
            return availableRoomsResponse(draft, availableRooms);
        }

        if (isServiceDeclined(lowerMessage)) {
            draft.setSelectedServiceIds(List.of());
        } else if (isBookingConfirm(lowerMessage)) {
            draft.setSelectedServiceIds(List.of());
        } else {
            List<String> serviceIds = extractServiceIds(userMessage);
            if (!serviceIds.isEmpty()) {
                draft.setSelectedServiceIds(serviceIds);
            }
        }

        if (draft.getSelectedServiceIds() == null) {
            conversationMemory.saveBookingDraft(userId, draft);
            return serviceListResponse(draft);
        }

        if (isBookingConfirm(lowerMessage) || draft.getSelectedServiceIds() != null) {
            draft.setConfirmed(true);
            conversationMemory.saveBookingDraft(userId, draft);
            return bookingConfirmResponse(draft);
        }

        conversationMemory.saveBookingDraft(userId, draft);
        return null;
    }

    private AiChatResponse bookingTypeSelectResponse(AiChatResponse.BookingDraft draft) {
        AiChatResponse response = new AiChatResponse(
                "Dạ được ạ. Trước tiên quý khách muốn đặt phòng theo ngày hay theo giờ? Em sẽ dựa trên lựa chọn này để kiểm tra phòng trống chính xác.",
                false
        );
        response.setIntent("booking_request");
        response.setUiType("BOOKING_TYPE_SELECT");
        response.setUiData(Map.of(
                "options", List.of(
                        Map.of("value", "DAILY", "label", "Theo ngày", "description", "Phù hợp nghỉ qua đêm, check-in 14:00 và check-out 12:00"),
                        Map.of("value", "HOURLY", "label", "Theo giờ", "description", "Linh hoạt trong ngày, tối thiểu 1 giờ")
                )
        ));
        response.setBookingDraft(draft);
        return response;
    }

    private AiChatResponse datePickerResponse(AiChatResponse.BookingDraft draft) {
        boolean hourly = "HOURLY".equalsIgnoreCase(draft.getBookingType());
        AiChatResponse response = new AiChatResponse(
                hourly
                        ? "Quý khách chọn đặt theo giờ. Vui lòng chọn ngày, giờ nhận phòng, số giờ thuê và số khách để em kiểm tra phòng trống phù hợp."
                        : "Quý khách chọn đặt theo ngày. Vui lòng chọn ngày nhận phòng, ngày trả phòng và số khách để em lọc đúng các phòng còn trống.",
                false
        );
        response.setIntent("booking_stay_type_select");
        response.setUiType("DATE_PICKER");
        response.setUiData(Map.of(
                "bookingType", draft.getBookingType(),
                "guests", draft.getGuests() != null ? draft.getGuests() : 1
        ));
        response.setBookingDraft(draft);
        return response;
    }

    private AiChatResponse availableRoomsResponse(AiChatResponse.BookingDraft draft, List<Map<String, Object>> availableRooms) {
        AiChatResponse response = new AiChatResponse(
                availableRooms.isEmpty()
                        ? "Em rất tiếc, trong khoảng thời gian này chưa tìm thấy loại phòng phù hợp với số khách. Quý khách có thể đổi ngày hoặc giảm số khách để em kiểm tra lại."
                        : "Em đã kiểm tra lịch phòng trống theo thời gian quý khách chọn. Đây là những loại phòng còn phù hợp, quý khách chọn phòng muốn đặt nhé.",
                !availableRooms.isEmpty()
        );
        response.setIntent("booking_date_select");
        response.setUiType("ROOM_GRID");
        response.setUiData(availableRooms);
        response.setBookingDraft(draft);
        return response;
    }

    private AiChatResponse serviceListResponse(AiChatResponse.BookingDraft draft) {
        AiChatResponse response = new AiChatResponse(
                "Phòng " + safeText(draft.getRoomTypeName(), "quý khách chọn") + " đang phù hợp với lịch lưu trú này. Quý khách có muốn thêm dịch vụ đi kèm không? Có thể chọn nhiều dịch vụ hoặc bấm tiếp tục để bỏ qua.",
                false
        );
        response.setIntent("booking_room_select");
        response.setUiType("SERVICE_LIST");
        response.setUiData(hotelTools.getServicesRaw());
        response.setBookingDraft(draft);
        return response;
    }

    private AiChatResponse bookingConfirmResponse(AiChatResponse.BookingDraft draft) {
        AiChatResponse response = new AiChatResponse(
                "Tuyệt vời, em đã ghi nhận đủ thông tin đặt phòng. Quý khách có thể chuyển sang trang đặt phòng để kiểm tra lại chi tiết và hoàn tất thanh toán.",
                false
        );
        response.setIntent("booking_confirm");
        response.setUiType("BOOKING_CONFIRM");
        response.setUiData(Map.of(
                "action", "REDIRECT_TO_BOOKING",
                "bookingPageUrl", "/customer/bookingPage",
                "message", "Chuyển đến trang đặt phòng để hoàn tất thanh toán"
        ));
        response.setBookingDraft(draft);
        return response;
    }

    private AiChatResponse simpleResponse(String intent, String content) {
        AiChatResponse response = new AiChatResponse(content, false);
        response.setIntent(intent);
        return response;
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
            String rawJson = geminiService.generateResponse(intentPrompt);
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

                case "booking_request" -> "";

                case "booking_room_select" -> {
                    IntentParams params = currentParams.get();
                    yield "ROOM_SELECTED: " + 
                          (params.roomTypeName != null ? params.roomTypeName : params.roomTypeId);
                }

                case "booking_stay_type_select" -> {
                    IntentParams params = currentParams.get();
                    yield "BOOKING_TYPE_SELECTED: " + params.bookingType;
                }

                case "booking_date_select" -> "";

                case "booking_service_select" -> "";

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
            return "Ban muon dat phong theo ngay hay theo gio?";
        }

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
            return "Em cần kiểm tra phòng trống theo thời gian anh/chị chọn trước khi hỏi dịch vụ. Anh/chị vui lòng chọn đầy đủ loại đặt phòng, thời gian và số khách.";
        }

        if ("booking_service_select".equals(intent)) {
            return "Em chỉ ghi nhận dịch vụ sau khi anh/chị đã chọn phòng còn trống. Nếu anh/chị đang đặt phòng, em sẽ đưa mình về đúng bước tiếp theo.";
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

        return geminiService.generateResponse(finalPrompt);
    }

    //RICH UI ENRICHMENT

    private void enrichResponseWithUI(AiChatResponse response, String intent,
                                      String userMessage, String userId, String toolData) {
        switch (intent) {
            case "room_inquiry" -> {
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

            case "booking_request", "booking_room_select" -> {
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
                response.setUiType("DATE_PICKER");
                response.setUiData(Map.of("bookingType", "DAILY"));
            }

            case "booking_service_select" -> {
                response.setUiType("BOOKING_CONFIRM");
                response.setUiData(Map.of(
                    "action", "REDIRECT_TO_BOOKING",
                    "bookingPageUrl", "/customer/bookingPage",
                    "message", "Chuyen den trang dat phong de hoan tat thanh toan"
                ));
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

    private boolean needsDateSelection(AiChatResponse.BookingDraft draft) {
        if (draft.getCheckInDate() == null) return true;
        if ("HOURLY".equalsIgnoreCase(draft.getBookingType())) {
            return draft.getDurationHours() == null || draft.getDurationHours() <= 0;
        }
        return draft.getCheckOutDate() == null;
    }

    private String validateBookingTime(AiChatResponse.BookingDraft draft) {
        if (draft.getGuests() != null && draft.getGuests() < 1) {
            return "Số khách chưa hợp lệ. Quý khách vui lòng chọn ít nhất 1 khách để em kiểm tra phòng phù hợp.";
        }

        LocalDate checkIn = parseIsoDate(draft.getCheckInDate());
        if (checkIn == null) {
            return null;
        }

        if (checkIn.isBefore(LocalDate.now())) {
            return "Ngày nhận phòng đang nằm trong quá khứ. Quý khách vui lòng chọn lại ngày nhận phòng từ hôm nay trở đi.";
        }

        if ("HOURLY".equalsIgnoreCase(draft.getBookingType())) {
            if (draft.getDurationHours() != null && draft.getDurationHours() < 1) {
                return "Số giờ thuê chưa hợp lệ. Quý khách vui lòng chọn tối thiểu 1 giờ.";
            }
            return null;
        }

        LocalDate checkOut = parseIsoDate(draft.getCheckOutDate());
        if (checkOut == null) {
            return null;
        }

        if (!checkOut.isAfter(checkIn)) {
            return "Ngày trả phòng phải sau ngày nhận phòng. Quý khách vui lòng chọn lại ngày nhận và ngày trả phòng hợp lệ.";
        }

        return null;
    }

    private String detectBookingType(String lowerMessage) {
        if (lowerMessage.contains("theo gio") || lowerMessage.contains("hourly") || lowerMessage.contains("dat gio")) {
            return "HOURLY";
        }
        if (lowerMessage.contains("theo ngay") || lowerMessage.contains("daily") || lowerMessage.contains("qua dem")) {
            return "DAILY";
        }
        return null;
    }

    private boolean isBookingIntent(String lowerMessage) {
        return lowerMessage.contains("dat phong")
                || lowerMessage.contains("thue phong")
                || lowerMessage.contains("book")
                || lowerMessage.contains("booking")
                || lowerMessage.contains("reservation")
                || lowerMessage.contains("giu phong");
    }

    private boolean isBookingCancel(String lowerMessage) {
        return lowerMessage.contains("huy dat")
                || lowerMessage.contains("huy quy trinh")
                || lowerMessage.contains("thoi khong dat")
                || lowerMessage.equals("huy");
    }

    private boolean isBookingConfirm(String lowerMessage) {
        return lowerMessage.contains("xac nhan")
                || lowerMessage.contains("dong y")
                || lowerMessage.contains("dat phong ngay")
                || lowerMessage.contains("hoan tat")
                || lowerMessage.contains("hien nut xac nhan")
                || lowerMessage.contains("nut xac nhan")
                || lowerMessage.contains("chuyen sang booking");
    }

    private boolean isServiceDeclined(String lowerMessage) {
        return lowerMessage.contains("khong dat dich vu")
                || lowerMessage.contains("khong can dich vu")
                || lowerMessage.contains("bo qua dich vu")
                || lowerMessage.contains("khong them dich vu")
                || lowerMessage.contains("khong su dung dich vu");
    }

    private List<String> extractServiceIds(String message) {
        List<String> serviceIds = new ArrayList<>();
        Matcher matcher = Pattern.compile("(?i)SVC[-_ ]?\\d+").matcher(message);
        while (matcher.find()) {
            String id = matcher.group().toUpperCase().replace("_", "-").replace(" ", "-");
            if (!serviceIds.contains(id)) {
                serviceIds.add(id);
            }
        }
        return serviceIds;
    }

    private Map<String, Object> findSelectedRoom(String message, List<Map<String, Object>> rooms) {
        String normalizedMessage = normalize(message);
        for (Map<String, Object> room : rooms) {
            String id = normalize(Objects.toString(room.get("roomTypeID"), ""));
            String name = normalize(Objects.toString(room.get("typeName"), ""));
            if (!id.isBlank() && normalizedMessage.contains(id)) {
                return room;
            }
            if (!name.isBlank() && normalizedMessage.contains(name)) {
                return room;
            }
        }
        return null;
    }

    private DateSelection parseDateSelection(String message, String bookingType) {
        List<String> dateValues = extractIsoDates(message);
        Integer guests = extractGuests(message);
        Integer durationHours = extractDurationHours(message);

        if ("HOURLY".equalsIgnoreCase(bookingType)) {
            return new DateSelection(
                    dateValues.isEmpty() ? null : dateValues.get(0),
                    null,
                    durationHours,
                    guests
            );
        }

        return new DateSelection(
                dateValues.isEmpty() ? null : dateValues.get(0),
                dateValues.size() < 2 ? null : dateValues.get(1),
                null,
                guests
        );
    }

    private List<String> extractIsoDates(String message) {
        List<String> dates = new ArrayList<>();

        Matcher isoMatcher = Pattern.compile("\\b\\d{4}-\\d{1,2}-\\d{1,2}\\b").matcher(message);
        while (isoMatcher.find()) {
            addDateIfValid(dates, isoMatcher.group(), DateTimeFormatter.ofPattern("yyyy-M-d"));
        }

        Matcher slashMatcher = Pattern.compile("\\b\\d{1,2}/\\d{1,2}/\\d{4}\\b").matcher(message);
        while (slashMatcher.find()) {
            addDateIfValid(dates, slashMatcher.group(), DateTimeFormatter.ofPattern("d/M/yyyy"));
        }

        return dates;
    }

    private void addDateIfValid(List<String> dates, String rawDate, DateTimeFormatter formatter) {
        try {
            String iso = LocalDate.parse(rawDate, formatter).toString();
            if (!dates.contains(iso)) {
                dates.add(iso);
            }
        } catch (Exception ignored) {
        }
    }

    private LocalDate parseIsoDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    private Integer extractGuests(String message) {
        Matcher matcher = Pattern.compile("(\\d+)\\s*(khach|nguoi|guest)", Pattern.CASE_INSENSITIVE).matcher(normalize(message));
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return null;
    }

    private Integer extractDurationHours(String message) {
        Matcher matcher = Pattern.compile("(\\d+)\\s*(gio|hour)", Pattern.CASE_INSENSITIVE).matcher(normalize(message));
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return null;
    }

    private String safeText(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private String normalize(String value) {
        if (value == null) return "";
        String normalized = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return normalized.toLowerCase(Locale.ROOT).trim();
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

    private record DateSelection(String checkInDate, String checkOutDate, Integer durationHours, Integer guests) {
    }
}
