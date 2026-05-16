package com.hotelvista.aiconcierge.dto.aichat;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class AiChatResponse {
    private String content;
    private boolean showRoomCards;


    private String uiType; // loại UI component cần render
    private Object uiData; // data cho UI component
    private String intent;
    private BookingDraft bookingDraft;
    public AiChatResponse() {}

    public AiChatResponse(String content, boolean showRoomCards) {
        this.content = content;
        this.showRoomCards = showRoomCards;
    }

    // Builder style setters
    public AiChatResponse withUiType(String uiType) {
        this.uiType = uiType;
        return this;
    }

    public AiChatResponse withUiData(Object uiData) {
        this.uiData = uiData;
        return this;
    }

    public AiChatResponse withIntent(String intent) {
        this.intent = intent;
        return this;
    }

    public AiChatResponse withBookingDraft(BookingDraft draft) {
        this.bookingDraft = draft;
        return this;
    }

    // Getters/Setters
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public boolean isShowRoomCards() { return showRoomCards; }
    public void setShowRoomCards(boolean showRoomCards) { this.showRoomCards = showRoomCards; }

    public String getUiType() { return uiType; }
    public void setUiType(String uiType) { this.uiType = uiType; }

    public Object getUiData() { return uiData; }
    public void setUiData(Object uiData) { this.uiData = uiData; }

    public String getIntent() { return intent; }
    public void setIntent(String intent) { this.intent = intent; }

    public BookingDraft getBookingDraft() { return bookingDraft; }
    public void setBookingDraft(BookingDraft bookingDraft) { this.bookingDraft = bookingDraft; }


    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class BookingDraft {
        private String roomTypeId;
        private String roomTypeName;
        private String bookingType;    // "DAILY" | "HOURLY"
        private String checkInDate;
        private String checkOutDate;
        private Integer durationHours;
        private Integer guests;
        private List<String> selectedServiceIds;
        private Double totalAmount;
        private String specialRequests;
        private Boolean confirmed;

        // Getters & Setters
        public String getRoomTypeId() { return roomTypeId; }
        public void setRoomTypeId(String roomTypeId) { this.roomTypeId = roomTypeId; }

        public String getRoomTypeName() { return roomTypeName; }
        public void setRoomTypeName(String roomTypeName) { this.roomTypeName = roomTypeName; }

        public String getBookingType() { return bookingType; }
        public void setBookingType(String bookingType) { this.bookingType = bookingType; }

        public String getCheckInDate() { return checkInDate; }
        public void setCheckInDate(String checkInDate) { this.checkInDate = checkInDate; }

        public String getCheckOutDate() { return checkOutDate; }
        public void setCheckOutDate(String checkOutDate) { this.checkOutDate = checkOutDate; }

        public Integer getDurationHours() { return durationHours; }
        public void setDurationHours(Integer durationHours) { this.durationHours = durationHours; }

        public Integer getGuests() { return guests; }
        public void setGuests(Integer guests) { this.guests = guests; }

        public List<String> getSelectedServiceIds() { return selectedServiceIds; }
        public void setSelectedServiceIds(List<String> selectedServiceIds) { this.selectedServiceIds = selectedServiceIds; }

        public Double getTotalAmount() { return totalAmount; }
        public void setTotalAmount(Double totalAmount) { this.totalAmount = totalAmount; }

        public String getSpecialRequests() { return specialRequests; }
        public void setSpecialRequests(String specialRequests) { this.specialRequests = specialRequests; }

        public Boolean getConfirmed() { return confirmed; }
        public void setConfirmed(Boolean confirmed) { this.confirmed = confirmed; }
    }
}
