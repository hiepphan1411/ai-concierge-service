package com.hotelvista.aiconcierge.dto.aichat;

public class AiChatRequest {
    private String userId;
    private String message;

    public AiChatRequest() {}

    public AiChatRequest(String userId, String message) {
        this.userId = userId;
        this.message = message;
    }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
