package com.hotelvista.aiconcierge.service;

import com.hotelvista.aiconcierge.dto.aichat.AiChatResponse;
import com.hotelvista.aiconcierge.model.BookingState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class BookingFlowService {

    private final ConversationMemory conversationMemory;
    private final HotelTools hotelTools;

    public BookingFlowService(ConversationMemory conversationMemory, HotelTools hotelTools) {
        this.conversationMemory = conversationMemory;
        this.hotelTools = hotelTools;
    }

    public AiChatResponse process(String userId, String userMessage) {
        String lower = normalize(userMessage);
        AiChatResponse.BookingDraft draft = conversationMemory.getBookingDraft(userId);

        if (isCancel(lower)) {
            if (draft != null) {
                draft.setState(BookingState.BOOKING_CANCELLED.name());
                conversationMemory.saveBookingDraft(userId, draft);
            }
            conversationMemory.clearBookingDraft(userId);
            return response("booking_cancel",
                    "Dạ, em đã hủy quy trình đặt phòng hiện tại. Khi anh/chị cần đặt phòng lại, em sẽ hỗ trợ từ đầu và kiểm tra phòng trống đúng theo thời gian anh/chị chọn.",
                    null,
                    null,
                    draft);
        }

        boolean active = isActive(draft);
        if (!active && !isBookingIntent(lower)) {
            return null;
        }

        if (active && isOutOfBookingQuestion(lower)) {
            return null;
        }

        if (!active) {
            draft = conversationMemory.getOrCreateBookingDraft(userId);
            resetDraft(draft);
            draft.setState(BookingState.ASKING_BOOKING_TYPE.name());
        }

        if (isChangeBookingType(lower)) {
            clearTimeRoomAndServices(draft);
            draft.setBookingType(null);
            draft.setState(BookingState.ASKING_BOOKING_TYPE.name());
        }

        String detectedType = detectBookingType(lower);
        if (detectedType != null && !detectedType.equalsIgnoreCase(draft.getBookingType())) {
            clearTimeRoomAndServices(draft);
            draft.setBookingType(detectedType);
            draft.setCheckInTime("HOURLY".equals(detectedType) ? null : "14:00");
            draft.setCheckOutTime("HOURLY".equals(detectedType) ? null : "12:00");
            draft.setState("HOURLY".equals(detectedType)
                    ? BookingState.COLLECTING_HOURLY_TIME.name()
                    : BookingState.COLLECTING_DAILY_DATE.name());
        }

        Integer guests = extractGuests(lower);
        if (guests != null) {
            if (!Objects.equals(draft.getGuests(), guests)) {
                clearRoomAndServices(draft);
            }
            draft.setGuests(guests);
        } else if (draft.getGuests() == null) {
            draft.setGuests(1);
        }

        if (draft.getBookingType() == null) {
            conversationMemory.saveBookingDraft(userId, draft);
            return bookingTypeResponse(draft);
        }

        if (isAskingAvailableTime(lower)) {
            conversationMemory.saveBookingDraft(userId, draft);
            return dateTimeResponse(draft,
                    "Hiện tại em chưa có lịch trống tổng hợp theo từng ngày để đề xuất tự động. Anh/chị vui lòng chọn ngày nhận phòng và ngày trả phòng mong muốn, em sẽ kiểm tra ngay các phòng còn trống trong khoảng thời gian đó.");
        }

        if (isChangeTime(lower)) {
            clearTimeRoomAndServices(draft);
            draft.setState("HOURLY".equalsIgnoreCase(draft.getBookingType())
                    ? BookingState.COLLECTING_HOURLY_TIME.name()
                    : BookingState.COLLECTING_DAILY_DATE.name());
        }

        if ("HOURLY".equalsIgnoreCase(draft.getBookingType())) {
            applyHourlyTime(userMessage, lower, draft);
        } else {
            applyDailyDates(userMessage, lower, draft);
        }

        String timeError = validateTime(draft);
        if (timeError != null) {
            conversationMemory.saveBookingDraft(userId, draft);
            return dateTimeResponse(draft, timeError);
        }

        if (needsDateTime(draft)) {
            conversationMemory.saveBookingDraft(userId, draft);
            return dateTimeResponse(draft, null);
        }

        if (isChangeRoom(lower)) {
            clearRoomAndServices(draft);
            draft.setState(BookingState.SELECTING_ROOM.name());
        }

        List<Map<String, Object>> availableRooms = hotelTools.getAvailableRoomsRaw(
                draft.getBookingType(),
                draft.getCheckInDate(),
                draft.getCheckOutDate(),
                draft.getCheckInTime(),
                draft.getDurationHours(),
                draft.getGuests()
        );

        if (draft.getSelectedRoomNumber() == null) {
            Map<String, Object> selectedRoom = findSelectedRoom(lower, availableRooms);
            if (selectedRoom != null) {
                setSelectedRoom(draft, selectedRoom);
            }
        }

        if (draft.getSelectedRoomNumber() == null) {
            draft.setState(BookingState.SELECTING_ROOM.name());
            conversationMemory.saveBookingDraft(userId, draft);
            return availableRoomsResponse(draft, availableRooms);
        }

        if (!roomStillAvailable(draft, availableRooms)) {
            clearRoomAndServices(draft);
            draft.setState(BookingState.SELECTING_ROOM.name());
            conversationMemory.saveBookingDraft(userId, draft);
            return availableRoomsResponse(draft, availableRooms,
                    "Thời gian hoặc số khách vừa thay đổi nên em cần kiểm tra lại phòng trống. Phòng đã chọn trước đó có thể không còn phù hợp, anh/chị vui lòng chọn lại một phòng trong danh sách bên dưới.");
        }

        if (isConfirm(lower)) {
            List<String> missing = validateBookingDraft(draft);
            if (!missing.isEmpty()) {
                conversationMemory.saveBookingDraft(userId, draft);
                return missingInfoResponse(draft, missing);
            }
            draft.setState(BookingState.CONFIRMING_BOOKING.name());
            conversationMemory.saveBookingDraft(userId, draft);
            return reviewResponse(draft);
        }

        if (draft.getSelectedServiceIds() == null) {
            if (isServiceDeclined(lower)) {
                draft.setSelectedServiceIds(List.of());
                draft.setState(BookingState.REVIEWING_BOOKING.name());
                conversationMemory.saveBookingDraft(userId, draft);
                return reviewResponse(draft);
            }

            List<String> serviceIds = extractServiceIds(userMessage);
            if (!serviceIds.isEmpty()) {
                draft.setSelectedServiceIds(serviceIds);
                draft.setState(BookingState.REVIEWING_BOOKING.name());
                conversationMemory.saveBookingDraft(userId, draft);
                return reviewResponse(draft);
            }

            draft.setState(BookingState.ASKING_SERVICES.name());
            conversationMemory.saveBookingDraft(userId, draft);
            return serviceSelectionResponse(draft);
        }

        applyCustomerInfo(userMessage, draft);

        List<String> missing = validateBookingDraft(draft);
        if (!missing.isEmpty()) {
            conversationMemory.saveBookingDraft(userId, draft);
            return missingInfoResponse(draft, missing);
        }

        draft.setState(BookingState.CONFIRMING_BOOKING.name());
        conversationMemory.saveBookingDraft(userId, draft);
        return reviewResponse(draft);
    }

    public boolean hasActiveBooking(String userId) {
        return isActive(conversationMemory.getBookingDraft(userId));
    }

    private AiChatResponse bookingTypeResponse(AiChatResponse.BookingDraft draft) {
        draft.setState(BookingState.ASKING_BOOKING_TYPE.name());
        return response("booking_request",
                "Dạ, em sẽ hỗ trợ anh/chị đặt phòng theo từng bước. Trước tiên anh/chị muốn đặt theo ngày hay theo giờ? Sau khi có loại đặt phòng và thời gian nhận/trả phòng, em sẽ lấy danh sách phòng còn trống đúng khoảng thời gian đó để anh/chị chọn.",
                "BOOKING_TYPE_SELECT",
                Map.of("options", List.of(
                        Map.of("value", "DAILY", "label", "Theo ngày", "description", "Nhận phòng 14:00, trả phòng 12:00"),
                        Map.of("value", "HOURLY", "label", "Theo giờ", "description", "Linh hoạt trong ngày, tối thiểu 1 giờ")
                ), "state", draft.getState()),
                draft);
    }

    private AiChatResponse dateTimeResponse(AiChatResponse.BookingDraft draft, String error) {
        boolean hourly = "HOURLY".equalsIgnoreCase(draft.getBookingType());
        draft.setState(hourly ? BookingState.COLLECTING_HOURLY_TIME.name() : BookingState.COLLECTING_DAILY_DATE.name());
        String content = error != null ? error : hourly
                ? "Dạ, em đã ghi nhận anh/chị muốn đặt phòng theo giờ. Anh/chị vui lòng chọn ngày, giờ nhận phòng, số giờ thuê và số khách để em kiểm tra phòng trống phù hợp."
                : "Dạ, em đã ghi nhận anh/chị muốn đặt phòng theo ngày. Với hình thức này, nhận phòng mặc định là 14:00 và trả phòng là 12:00; anh/chị vui lòng chọn ngày nhận phòng, ngày trả phòng và số khách.";

        return response("booking_stay_type_select",
                content,
                "DATE_PICKER",
                Map.of(
                        "bookingType", draft.getBookingType(),
                        "guests", draft.getGuests() != null ? draft.getGuests() : 1,
                        "state", draft.getState()
                ),
                draft);
    }

    private AiChatResponse availableRoomsResponse(AiChatResponse.BookingDraft draft, List<Map<String, Object>> rooms) {
        return availableRoomsResponse(draft, rooms, null);
    }

    private AiChatResponse availableRoomsResponse(AiChatResponse.BookingDraft draft, List<Map<String, Object>> rooms, String overrideContent) {
        String content;
        if (overrideContent != null) {
            content = overrideContent;
        } else if (rooms.isEmpty()) {
            draft.setState("HOURLY".equalsIgnoreCase(draft.getBookingType())
                    ? BookingState.COLLECTING_HOURLY_TIME.name()
                    : BookingState.COLLECTING_DAILY_DATE.name());
            content = "Em chưa tìm thấy phòng trống phù hợp trong khoảng thời gian anh/chị vừa chọn. Lý do có thể là phòng đang được giữ/đã có booking trùng lịch, phòng đang bảo trì, hoặc số khách vượt quá sức chứa phòng còn lại. Anh/chị vui lòng chọn lại thời gian bên dưới để em kiểm tra danh sách phòng trống mới.";
            return response("booking_stay_type_select", content, "DATE_PICKER", Map.of(
                    "bookingType", draft.getBookingType(),
                    "guests", draft.getGuests() != null ? draft.getGuests() : 1,
                    "state", draft.getState(),
                    "reason", "NO_AVAILABLE_ROOMS"
            ), draft);
        } else {
            content = "Em đã kiểm tra phòng trống theo thời gian anh/chị chọn. Dưới đây là các phòng còn phù hợp; anh/chị vui lòng chọn một phòng để em tiếp tục hỏi dịch vụ đi kèm.";
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("state", draft.getState());
        data.put("rooms", rooms);
        data.put("actions", List.of("CHANGE_TIME", "CHANGE_BOOKING_TYPE", "CANCEL_BOOKING"));
        return response("booking_date_select", content, "ROOM_GRID", data, draft);
    }

    private AiChatResponse serviceSelectionResponse(AiChatResponse.BookingDraft draft) {
        draft.setState(BookingState.ASKING_SERVICES.name());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("state", draft.getState());
        data.put("services", hotelTools.getServicesRaw());
        data.put("allowSkip", true);
        return response("booking_room_select",
                "Anh/chị đã chọn phòng " + safe(draft.getSelectedRoomNumber(), draft.getRoomTypeName()) + ". Anh/chị có muốn thêm dịch vụ đi kèm không? Anh/chị có thể chọn nhiều dịch vụ, hoặc bỏ qua nếu chưa cần.",
                "SERVICE_LIST",
                data,
                draft);
    }

    private AiChatResponse reviewResponse(AiChatResponse.BookingDraft draft) {
        draft.setState(BookingState.CONFIRMING_BOOKING.name());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("state", draft.getState());
        data.put("action", "REDIRECT_TO_BOOKING");
        data.put("bookingPageUrl", "/customer/bookingPage");
        data.put("title", "Xác nhận thông tin đặt phòng");
        data.put("draft", draft);
        data.put("actions", List.of("CONFIRM_BOOKING", "EDIT_TIME", "CHANGE_ROOM", "EDIT_SERVICES", "CANCEL_BOOKING"));
        return response("booking_confirm",
                "Em đã có đủ thông tin cần thiết để anh/chị kiểm tra lần cuối. Anh/chị xem lại loại đặt phòng, thời gian, phòng và dịch vụ; nếu mọi thông tin đúng, bấm nút bên dưới để sang trang đặt phòng và thanh toán.",
                "BOOKING_CONFIRM",
                data,
                draft);
    }

    private AiChatResponse missingInfoResponse(AiChatResponse.BookingDraft draft, List<String> missing) {
        String content = "Em chưa thể xác nhận vì còn thiếu: " + describeMissingFields(missing) + ". Anh/chị vui lòng bổ sung phần còn thiếu để em tiếp tục.";
        if (missing.contains("bookingType")) return bookingTypeResponse(draft);
        if (missing.contains("checkInDate") || missing.contains("checkOutDate") || missing.contains("checkInTime") || missing.contains("durationHours")) {
            return dateTimeResponse(draft, content);
        }
        if (missing.contains("selectedRoom")) {
            return availableRoomsResponse(draft, hotelTools.getAvailableRoomsRaw(draft.getBookingType(), draft.getCheckInDate(), draft.getCheckOutDate(), draft.getCheckInTime(), draft.getDurationHours(), draft.getGuests()), content);
        }
        return response("booking_missing_info", content, null, null, draft);
    }

    private String describeMissingFields(List<String> missing) {
        return String.join(", ", missing.stream()
                .map(this::missingFieldLabel)
                .toList());
    }

    private String missingFieldLabel(String field) {
        return switch (field) {
            case "bookingType" -> "loại đặt phòng";
            case "checkInDate" -> "ngày nhận phòng";
            case "checkOutDate" -> "ngày trả phòng";
            case "checkInTime" -> "giờ nhận phòng";
            case "durationHours" -> "số giờ thuê";
            case "selectedRoom" -> "phòng muốn đặt";
            case "selectedServices" -> "lựa chọn dịch vụ";
            default -> field;
        };
    }

    private AiChatResponse response(String intent, String content, String uiType, Object uiData, AiChatResponse.BookingDraft draft) {
        AiChatResponse response = new AiChatResponse(content, false);
        response.setIntent(intent);
        response.setUiType(uiType);
        response.setUiData(uiData);
        response.setBookingDraft(draft);
        log.info("Booking flow response intent={}, state={}, uiType={}", intent, draft != null ? draft.getState() : null, uiType);
        return response;
    }

    private void resetDraft(AiChatResponse.BookingDraft draft) {
        draft.setRoomTypeId(null);
        draft.setRoomTypeName(null);
        draft.setSelectedRoomId(null);
        draft.setSelectedRoomNumber(null);
        draft.setSelectedRoom(null);
        draft.setBookingType(null);
        draft.setCheckInDate(null);
        draft.setCheckOutDate(null);
        draft.setCheckInTime(null);
        draft.setCheckOutTime(null);
        draft.setDurationHours(null);
        draft.setGuests(1);
        draft.setSelectedServiceIds(null);
        draft.setTotalAmount(null);
        draft.setSpecialRequests(null);
        draft.setConfirmed(false);
    }

    private void clearTimeRoomAndServices(AiChatResponse.BookingDraft draft) {
        draft.setCheckInDate(null);
        draft.setCheckOutDate(null);
        draft.setCheckInTime(null);
        draft.setCheckOutTime(null);
        draft.setDurationHours(null);
        clearRoomAndServices(draft);
    }

    private void clearRoomAndServices(AiChatResponse.BookingDraft draft) {
        draft.setSelectedRoomId(null);
        draft.setSelectedRoomNumber(null);
        draft.setSelectedRoom(null);
        draft.setRoomTypeId(null);
        draft.setRoomTypeName(null);
        draft.setSelectedServiceIds(null);
        draft.setConfirmed(false);
    }

    private void applyDailyDates(String message, String lower, AiChatResponse.BookingDraft draft) {
        List<LocalDate> dates = extractDates(message, lower);
        if (!dates.isEmpty()) {
            if (!Objects.equals(draft.getCheckInDate(), dates.get(0).toString())) clearRoomAndServices(draft);
            draft.setCheckInDate(dates.get(0).toString());
        }
        if (dates.size() >= 2) {
            if (!Objects.equals(draft.getCheckOutDate(), dates.get(1).toString())) clearRoomAndServices(draft);
            draft.setCheckOutDate(dates.get(1).toString());
        } else {
            Integer nights = extractNights(lower);
            if (nights != null && draft.getCheckInDate() != null) {
                draft.setCheckOutDate(LocalDate.parse(draft.getCheckInDate()).plusDays(nights).toString());
            }
        }
        draft.setCheckInTime("14:00");
        draft.setCheckOutTime("12:00");
    }

    private void applyHourlyTime(String message, String lower, AiChatResponse.BookingDraft draft) {
        List<LocalDate> dates = extractDates(message, lower);
        if (!dates.isEmpty()) {
            if (!Objects.equals(draft.getCheckInDate(), dates.get(0).toString())) clearRoomAndServices(draft);
            draft.setCheckInDate(dates.get(0).toString());
        }

        List<LocalTime> times = extractTimes(lower);
        if (!times.isEmpty()) {
            String newTime = times.get(0).toString();
            if (!Objects.equals(draft.getCheckInTime(), newTime)) clearRoomAndServices(draft);
            draft.setCheckInTime(newTime);
        }

        Integer duration = extractDurationHours(lower);
        if (duration == null && times.size() >= 2) {
            int hours = times.get(1).getHour() - times.get(0).getHour();
            int minutes = times.get(1).getMinute() - times.get(0).getMinute();
            duration = hours + (minutes > 0 ? 1 : 0);
            draft.setCheckOutTime(times.get(1).toString());
        }
        if (duration != null) {
            if (!Objects.equals(draft.getDurationHours(), duration)) clearRoomAndServices(draft);
            draft.setDurationHours(duration);
        }
    }

    private String validateTime(AiChatResponse.BookingDraft draft) {
        if (draft.getGuests() != null && draft.getGuests() < 1) {
            return "Số khách chưa hợp lệ. Anh/chị vui lòng chọn ít nhất 1 khách.";
        }

        LocalDate checkIn = parseDate(draft.getCheckInDate());
        if (checkIn != null && checkIn.isBefore(LocalDate.now())) {
            return "Ngày nhận phòng đang nằm trong quá khứ. Anh/chị vui lòng chọn ngày từ hôm nay trở đi.";
        }

        if ("HOURLY".equalsIgnoreCase(draft.getBookingType())) {
            if (draft.getDurationHours() != null && draft.getDurationHours() < 1) {
                return "Thời gian thuê tối thiểu là 1 giờ. Anh/chị vui lòng chọn lại số giờ thuê hoặc giờ trả phòng.";
            }
            return null;
        }

        LocalDate checkOut = parseDate(draft.getCheckOutDate());
        if (checkIn != null && checkOut != null && !checkOut.isAfter(checkIn)) {
            return "Ngày trả phòng phải sau ngày nhận phòng. Anh/chị vui lòng chọn lại khoảng thời gian hợp lệ.";
        }
        return null;
    }

    private boolean needsDateTime(AiChatResponse.BookingDraft draft) {
        if (draft.getCheckInDate() == null) return true;
        if ("HOURLY".equalsIgnoreCase(draft.getBookingType())) {
            return draft.getCheckInTime() == null || draft.getDurationHours() == null;
        }
        return draft.getCheckOutDate() == null;
    }

    private List<String> validateBookingDraft(AiChatResponse.BookingDraft draft) {
        List<String> missing = new ArrayList<>();
        if (draft.getBookingType() == null) missing.add("bookingType");
        if (draft.getCheckInDate() == null) missing.add("checkInDate");
        if ("HOURLY".equalsIgnoreCase(draft.getBookingType())) {
            if (draft.getCheckInTime() == null) missing.add("checkInTime");
            if (draft.getDurationHours() == null || draft.getDurationHours() < 1) missing.add("durationHours");
        } else {
            if (draft.getCheckOutDate() == null) missing.add("checkOutDate");
        }
        if (draft.getSelectedRoomNumber() == null) missing.add("selectedRoom");
        if (draft.getSelectedServiceIds() == null) missing.add("selectedServices");
        return missing;
    }

    private Map<String, Object> findSelectedRoom(String lower, List<Map<String, Object>> rooms) {
        for (Map<String, Object> room : rooms) {
            String roomNumber = normalize(Objects.toString(room.get("roomNumber"), ""));
            String roomId = normalize(Objects.toString(room.get("roomId"), ""));
            String typeId = normalize(Objects.toString(room.get("roomTypeID"), ""));
            String typeName = normalize(Objects.toString(room.get("typeName"), ""));
            if (!roomNumber.isBlank() && containsToken(lower, roomNumber)) return room;
            if (!roomId.isBlank() && containsToken(lower, roomId)) return room;
            if (!typeId.isBlank() && lower.contains(typeId)) return room;
            if (!typeName.isBlank() && lower.contains(typeName)) return room;
        }
        return null;
    }

    private boolean roomStillAvailable(AiChatResponse.BookingDraft draft, List<Map<String, Object>> rooms) {
        return rooms.stream().anyMatch(room -> Objects.equals(
                Objects.toString(room.get("roomNumber"), ""),
                Objects.toString(draft.getSelectedRoomNumber(), "")
        ));
    }

    private void setSelectedRoom(AiChatResponse.BookingDraft draft, Map<String, Object> room) {
        draft.setSelectedRoomId(Objects.toString(room.get("roomId"), null));
        draft.setSelectedRoomNumber(Objects.toString(room.get("roomNumber"), null));
        draft.setSelectedRoom(room);
        draft.setRoomTypeId(Objects.toString(room.get("roomTypeID"), null));
        draft.setRoomTypeName(Objects.toString(room.getOrDefault("roomTypeName", room.get("typeName")), null));
        draft.setSelectedServiceIds(null);
        draft.setState(BookingState.ASKING_SERVICES.name());
    }

    private List<LocalDate> extractDates(String message, String lower) {
        List<LocalDate> dates = new ArrayList<>();
        Matcher iso = Pattern.compile("\\b\\d{4}-\\d{1,2}-\\d{1,2}\\b").matcher(message);
        while (iso.find()) addDate(dates, iso.group(), DateTimeFormatter.ofPattern("yyyy-M-d"), true);

        Matcher slashWithYear = Pattern.compile("\\b\\d{1,2}/\\d{1,2}/\\d{4}\\b").matcher(message);
        while (slashWithYear.find()) addDate(dates, slashWithYear.group(), DateTimeFormatter.ofPattern("d/M/yyyy"), true);

        Matcher slashNoYear = Pattern.compile("\\b\\d{1,2}/\\d{1,2}\\b").matcher(message);
        while (slashNoYear.find()) addDate(dates, slashNoYear.group() + "/" + LocalDate.now().getYear(), DateTimeFormatter.ofPattern("d/M/yyyy"), false);

        if (lower.contains("hom nay") || lower.contains("toi nay")) addRelativeDate(dates, 0);
        if (lower.contains("ngay mai") || lower.contains("mai")) addRelativeDate(dates, 1);
        if (lower.contains("ngay kia")) addRelativeDate(dates, 2);
        return dates;
    }

    private void addDate(List<LocalDate> dates, String raw, DateTimeFormatter formatter, boolean explicitYear) {
        try {
            LocalDate date = LocalDate.parse(raw, formatter);
            if (!explicitYear && date.isBefore(LocalDate.now())) {
                date = date.plusYears(1);
            }
            if (!dates.contains(date)) dates.add(date);
        } catch (Exception ignored) {
        }
    }

    private void addRelativeDate(List<LocalDate> dates, int days) {
        LocalDate date = LocalDate.now().plusDays(days);
        if (!dates.contains(date)) dates.add(date);
    }

    private List<LocalTime> extractTimes(String lower) {
        List<LocalTime> times = new ArrayList<>();
        Matcher colon = Pattern.compile("\\b(\\d{1,2}):(\\d{2})\\b").matcher(lower);
        while (colon.find()) addTime(times, colon.group(1), colon.group(2));

        Matcher hour = Pattern.compile("\\b(\\d{1,2})\\s*h\\b|\\b(\\d{1,2})\\s*gio\\b").matcher(lower);
        while (hour.find()) {
            String value = hour.group(1) != null ? hour.group(1) : hour.group(2);
            addTime(times, value, "00");
        }
        return times;
    }

    private void addTime(List<LocalTime> times, String hour, String minute) {
        try {
            LocalTime time = LocalTime.of(Integer.parseInt(hour), Integer.parseInt(minute));
            if (!times.contains(time)) times.add(time);
        } catch (Exception ignored) {
        }
    }

    private Integer extractGuests(String lower) {
        Matcher matcher = Pattern.compile("(\\d+)\\s*(khach|nguoi|guest)").matcher(lower);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    private Integer extractNights(String lower) {
        Matcher matcher = Pattern.compile("(\\d+)\\s*(dem|night)").matcher(lower);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    private Integer extractDurationHours(String lower) {
        Matcher matcher = Pattern.compile("(?:thue|trong)\\s*(\\d+)\\s*(gio|tieng|hour)").matcher(lower);
        if (matcher.find()) return Integer.parseInt(matcher.group(1));
        Matcher plain = Pattern.compile("(\\d+)\\s*(tieng|hour)").matcher(lower);
        return plain.find() ? Integer.parseInt(plain.group(1)) : null;
    }

    private List<String> extractServiceIds(String message) {
        String lower = normalize(message);
        List<String> ids = new ArrayList<>();
        Matcher matcher = Pattern.compile("(?i)SVC[-_ ]?\\d+").matcher(message);
        while (matcher.find()) {
            String id = matcher.group().toUpperCase(Locale.ROOT).replace("_", "-").replace(" ", "-");
            if (!ids.contains(id)) ids.add(id);
        }
        if (!ids.isEmpty()) {
            return ids;
        }
        for (Map<String, Object> service : hotelTools.getServicesRaw()) {
            String id = Objects.toString(service.get("serviceID"), "");
            String name = normalize(Objects.toString(service.get("serviceName"), ""));
            if (!id.isBlank() && !name.isBlank() && lower.contains(name) && !ids.contains(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    private void applyCustomerInfo(String message, AiChatResponse.BookingDraft draft) {
        Matcher phone = Pattern.compile("\\b(0\\d{9,10}|\\+84\\d{9,10})\\b").matcher(message);
        if (phone.find()) draft.setCustomerPhone(phone.group(1));
        Matcher email = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}").matcher(message);
        if (email.find()) draft.setCustomerEmail(email.group());
    }

    private boolean isActive(AiChatResponse.BookingDraft draft) {
        if (draft == null) return false;
        if (Boolean.TRUE.equals(draft.getConfirmed())) return false;
        BookingState state = stateOf(draft);
        return state != BookingState.IDLE && state != BookingState.BOOKING_COMPLETED && state != BookingState.BOOKING_CANCELLED;
    }

    private BookingState stateOf(AiChatResponse.BookingDraft draft) {
        try {
            return draft.getState() == null ? BookingState.IDLE : BookingState.valueOf(draft.getState());
        } catch (Exception e) {
            return BookingState.IDLE;
        }
    }

    private String detectBookingType(String lower) {
        if (lower.contains("theo gio") || lower.contains("hourly") || lower.contains("thue gio") || lower.contains("dat gio")) return "HOURLY";
        if (lower.contains("theo ngay") || lower.contains("daily") || lower.contains("qua dem")) return "DAILY";
        return null;
    }

    private boolean isBookingIntent(String lower) {
        return lower.contains("dat phong") || lower.contains("thue phong") || lower.contains("giu phong") || lower.contains("book") || lower.contains("reservation");
    }

    private boolean isCancel(String lower) {
        return lower.contains("huy dat") || lower.contains("huy quy trinh") || lower.contains("thoi khong dat") || lower.equals("huy");
    }

    private boolean isConfirm(String lower) {
        return lower.contains("xac nhan") || lower.contains("dong y") || lower.contains("hoan tat") || lower.contains("dat phong ngay");
    }

    private boolean isServiceDeclined(String lower) {
        return lower.contains("khong dat dich vu") || lower.contains("khong can dich vu") || lower.contains("bo qua dich vu") || lower.contains("khong them dich vu");
    }

    private boolean isChangeBookingType(String lower) {
        return lower.contains("doi sang dat theo") || lower.contains("doi loai dat") || lower.contains("quay lai chon loai");
    }

    private boolean isChangeTime(String lower) {
        return lower.contains("doi ngay") || lower.contains("doi gio") || lower.contains("sua thoi gian") || lower.contains("chinh sua thoi gian");
    }

    private boolean isChangeRoom(String lower) {
        return lower.contains("doi phong") || lower.contains("chon phong khac");
    }

    private boolean isAskingAvailableTime(String lower) {
        return lower.contains("thoi gian trong")
                || lower.contains("ngay nao trong")
                || lower.contains("luc nao trong")
                || lower.contains("con trong ngay nao")
                || lower.contains("trong vao thoi gian nao");
    }

    private boolean isOutOfBookingQuestion(String lower) {
        return lower.contains("ho boi") || lower.contains("gym") || lower.contains("nha hang") || lower.contains("chinh sach") || lower.contains("may gio nhan phong");
    }

    private boolean containsToken(String text, String token) {
        return Pattern.compile("(^|\\D)" + Pattern.quote(token) + "(\\D|$)").matcher(text).find();
    }

    private LocalDate parseDate(String value) {
        try {
            return value == null ? null : LocalDate.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    private String safe(String primary, String fallback) {
        return primary != null && !primary.isBlank() ? primary : Objects.toString(fallback, "đã chọn");
    }

    private String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .trim();
    }
}
