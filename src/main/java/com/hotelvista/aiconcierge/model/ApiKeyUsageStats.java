package com.hotelvista.aiconcierge.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
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
    private Integer requestsPerMinute = 5;

    @Column(nullable = false)
    private Integer requestsPerDay = 20;

    @Column(nullable = false)
    private Integer peakRequestsPerMinute = 5; 

    @Column(nullable = false)
    private Integer requestCountCurrentMinute = 0; 

    @Column(nullable = false)
    private Integer peakCountCurrentMinute = 0;

    @Column(nullable = false)
    private Integer requestCountCurrentDay = 0;

    @Column(nullable = false)
    private LocalDate lastDailyResetDate = LocalDate.now();

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
        ensureQuotaDefaults();

        long now = System.currentTimeMillis();
        if (now - lastResetTime > 60000) { // 1 phút
            requestCountCurrentMinute = 0;
            peakCountCurrentMinute = 0;
            lastResetTime = now;
        }

        LocalDate today = LocalDate.now();
        if (!today.equals(lastDailyResetDate)) {
            requestCountCurrentDay = 0;
            lastDailyResetDate = today;
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

        return requestCountCurrentMinute < requestsPerMinute
                && requestCountCurrentDay < requestsPerDay;
    }


    public void incrementRequestCount() {
        resetCounterIfNeeded();
        requestCountCurrentMinute++;
        requestCountCurrentDay++;
        updatedAt = LocalDateTime.now();
    }


    public boolean isRateLimited() {
        resetCounterIfNeeded();
        return requestCountCurrentMinute >= requestsPerMinute
                || requestCountCurrentDay >= requestsPerDay;
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

    private void ensureQuotaDefaults() {
        if (requestsPerMinute == null) requestsPerMinute = 5;
        if (requestsPerDay == null) requestsPerDay = 20;
        if (peakRequestsPerMinute == null) peakRequestsPerMinute = 5;
        if (requestCountCurrentMinute == null) requestCountCurrentMinute = 0;
        if (peakCountCurrentMinute == null) peakCountCurrentMinute = 0;
        if (requestCountCurrentDay == null) requestCountCurrentDay = 0;
        if (lastDailyResetDate == null) lastDailyResetDate = LocalDate.now();
        if (lastResetTime == null) lastResetTime = System.currentTimeMillis();
        if (consecutiveFailures == null) consecutiveFailures = 0;
        if (totalSuccessfulRequests == null) totalSuccessfulRequests = 0L;
        if (totalFailedRequests == null) totalFailedRequests = 0L;
        if (updatedAt == null) updatedAt = LocalDateTime.now();
    }
}
