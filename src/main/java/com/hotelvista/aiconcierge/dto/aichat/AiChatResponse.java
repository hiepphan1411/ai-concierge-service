package com.hotelvista.aiconcierge.dto.aichat;

public class AiChatResponse {
    private String content;
    private boolean showRoomCards;

    public AiChatResponse() {}

    public AiChatResponse(String content, boolean showRoomCards) {
        this.content = content;
        this.showRoomCards = showRoomCards;
    }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public boolean isShowRoomCards() { return showRoomCards; }
    public void setShowRoomCards(boolean showRoomCards) { this.showRoomCards = showRoomCards; }
}
