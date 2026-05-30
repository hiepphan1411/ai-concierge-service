package com.hotelvista.aiconcierge.service;

import com.hotelvista.aiconcierge.dto.aichat.AiChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BookingFlowServiceTest {

    private BookingFlowService bookingFlowService;
    private HotelTools hotelTools;

    @BeforeEach
    void setUp() {
        hotelTools = mock(HotelTools.class);
        bookingFlowService = new BookingFlowService(new ConversationMemory(), hotelTools);

        when(hotelTools.getAvailableRoomsRaw(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(Map.of(
                        "roomId", "201",
                        "roomNumber", "201",
                        "roomTypeID", "RT-STD",
                        "typeName", "Phong 201 - Standard Twin",
                        "roomTypeName", "Standard Twin",
                        "maxOccupancy", 2,
                        "area", 24,
                        "basePrice", 650000,
                        "status", "AVAILABLE"
                )));
        when(hotelTools.getServicesRaw()).thenReturn(List.of(
                Map.of("serviceID", "SVC-001", "serviceName", "Buffet sang", "price", 150000)
        ));
    }

    @Test
    void dailyBookingDoesNotSkipRoomOrServiceSteps() {
        String userId = "u1";

        AiChatResponse start = bookingFlowService.process(userId, "Toi muon dat phong");
        assertThat(start.getUiType()).isEqualTo("BOOKING_TYPE_SELECT");

        AiChatResponse type = bookingFlowService.process(userId, "Theo ngay");
        assertThat(type.getUiType()).isEqualTo("DATE_PICKER");

        AiChatResponse dates = bookingFlowService.process(userId, "04/06/2026 den 06/06/2026 cho 2 khach");
        assertThat(dates.getUiType()).isEqualTo("ROOM_GRID");
        assertThat(dates.getBookingDraft().getSelectedRoomNumber()).isNull();

        AiChatResponse room = bookingFlowService.process(userId, "Toi chon phong 201");
        assertThat(room.getUiType()).isEqualTo("SERVICE_LIST");
        assertThat(room.getBookingDraft().getSelectedRoomNumber()).isEqualTo("201");

        AiChatResponse services = bookingFlowService.process(userId, "khong can dich vu");
        assertThat(services.getUiType()).isEqualTo("BOOKING_CONFIRM");
        assertThat(services.getBookingDraft().getSelectedServiceIds()).isEmpty();
    }

    @Test
    void hourlyBookingAsksForDurationWhenMissing() {
        String userId = "u2";

        bookingFlowService.process(userId, "Toi muon dat phong");
        bookingFlowService.process(userId, "Theo gio");
        AiChatResponse response = bookingFlowService.process(userId, "hom nay luc 18h cho 2 khach");

        assertThat(response.getUiType()).isEqualTo("DATE_PICKER");
        assertThat(response.getBookingDraft().getSelectedRoomNumber()).isNull();
    }

    @Test
    void confirmationBeforeRoomSelectionIsBlocked() {
        String userId = "u3";

        bookingFlowService.process(userId, "Toi muon dat phong theo ngay");
        AiChatResponse dates = bookingFlowService.process(userId, "04/06/2026 den 06/06/2026 cho 2 khach");
        assertThat(dates.getUiType()).isEqualTo("ROOM_GRID");

        AiChatResponse confirm = bookingFlowService.process(userId, "xac nhan");
        assertThat(confirm.getUiType()).isEqualTo("ROOM_GRID");
        assertThat(confirm.getBookingDraft().getSelectedRoomNumber()).isNull();
    }
}
