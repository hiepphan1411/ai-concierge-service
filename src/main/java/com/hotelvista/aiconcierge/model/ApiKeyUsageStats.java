package com.hotelvista.aiconcierge.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Entity để track usage stats của mỗi API key
 * Lưu trữ trên H2 database
 */
@Entity
@Table(name = "api_key_usage_stats")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class ApiKeyUsageStats {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String keyHash; 
    @Column(nullable = false)
    private String apiKeyMasked;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private KeyStatus status;

    // Rate Limiting configs
    @Column(nullable = false)
    private Integer requestsPerMinute = 20; 

    @Column(nullable = false)
    private Integer peakRequestsPerMinute = 5; 

    @Column(nullable = false)
    private Integer requestCountCurrentMinute = 0; 

    @Column(nullable = false)
    private Integer peakCountCurrentMinute = 0; 

    @Column(nullable = false)
    private Long lastResetTime = System.currentTimeMillis(); 

    // Failure tracking
    @Column(nullable = false)
    private Integer consecutiveFailures = 0; 

    @Column
    private LocalDateTime lastFailureTime; 

    @Column
    private String lastFailureMessage; 

    // Success tracking
    @Column(nullable = false)
    private Long totalSuccessfulRequests = 0L;

    @Column(nullable = false)
    private Long totalFailedRequests = 0L;

    @Column
    private LocalDateTime lastSuccessTime; 

    // Metadata
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    /**
     * Status của API key
     */
    public enum KeyStatus {
        ACTIVE, // Bình thường
        INACTIVE, // Bị vô hiệu hóa
        RATE_LIMITED, // Hết token
        FAILED
    }


    public void resetCounterIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastResetTime > 60000) { // 1 phút
            requestCountCurrentMinute = 0;
            peakCountCurrentMinute = 0;
            lastResetTime = now;
        }
    }

    public boolean canMakeRequest() {
        resetCounterIfNeeded();

        if (status == KeyStatus.INACTIVE) {
            return false;
        }

        if (status == KeyStatus.RATE_LIMITED) {
            return false;
        }

        if (status == KeyStatus.FAILED) {
            return false;
        }

        return requestCountCurrentMinute < requestsPerMinute;
    }


    public void incrementRequestCount() {
        resetCounterIfNeeded();
        requestCountCurrentMinute++;
        updatedAt = LocalDateTime.now();
    }


    public boolean isRateLimited() {
        resetCounterIfNeeded();
        return requestCountCurrentMinute >= requestsPerMinute;
    }


    public void recordSuccess() {
        totalSuccessfulRequests++;
        lastSuccessTime = LocalDateTime.now();
        consecutiveFailures = 0;
        updatedAt = LocalDateTime.now();

        // Nếu trước đó là RATE_LIMITED hoặc FAILED, chuyển lại ACTIVE
        if (status == KeyStatus.RATE_LIMITED || status == KeyStatus.FAILED) {
            status = KeyStatus.ACTIVE;
        }
    }

    /**
     * Mark request thất bại
     */
    public void recordFailure(String failureMessage) {
        totalFailedRequests++;
        lastFailureTime = LocalDateTime.now();
        lastFailureMessage = failureMessage;
        consecutiveFailures++;
        updatedAt = LocalDateTime.now();

        // Nếu thất bại quá 3 lần liên tiếp, mark là FAILED
        if (consecutiveFailures >= 3) {
            status = KeyStatus.FAILED;
        }
    }
}
