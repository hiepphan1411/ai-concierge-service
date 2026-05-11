package com.hotelvista.aiconcierge.service;

import com.hotelvista.aiconcierge.config.GeminiApiKeyConfig;
import com.hotelvista.aiconcierge.model.ApiKeyUsageStats;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;


@Slf4j
@Service
public class ApiKeyManagementService {

    private static final String REDIS_KEY_PREFIX = "api_key:";
    private static final String REDIS_KEYS_LIST = "api_keys:list";

    @Autowired
    private RedisTemplate<String, ApiKeyUsageStats> apiKeyStatsRedisTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private GeminiApiKeyConfig geminiApiKeyConfig;

    /**
     * Khởi tạo API keys vào Redis khi ứng dụng start
     */
    public void initializeApiKeys() {
        List<String> apiKeys = geminiApiKeyConfig.getAllApiKeys();

        if (apiKeys.isEmpty()) {
            log.warn("No API keys configured!");
            return;
        }

        log.info("Initializing {} API keys into Redis", apiKeys.size());

        for (String key : apiKeys) {
            String keyHash = hashApiKey(key);
            String redisKey = REDIS_KEY_PREFIX + keyHash;

            // Kiểm tra key đã tồn tại chưa
            Boolean keyExists = apiKeyStatsRedisTemplate.hasKey(redisKey);
            if (keyExists != null && keyExists) {
                log.debug("API key already exists in Redis");
                continue;
            }

            // Tạo stats mới với config mặc định
            ApiKeyUsageStats stats = ApiKeyUsageStats.builder()
                    .keyHash(keyHash)
                    .apiKeyMasked(maskApiKey(key))
                    .status(ApiKeyUsageStats.KeyStatus.ACTIVE)
                    .requestsPerMinute(20)
                    .peakRequestsPerMinute(5)
                    .requestCountCurrentMinute(0)
                    .peakCountCurrentMinute(0)
                    .lastResetTime(System.currentTimeMillis())
                    .consecutiveFailures(0)
                    .totalSuccessfulRequests(0L)
                    .totalFailedRequests(0L)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();

            // Lưu vào Redis
            apiKeyStatsRedisTemplate.opsForValue().set(redisKey, stats);
            stringRedisTemplate.opsForSet().add(REDIS_KEYS_LIST, keyHash);
            
            log.info("Initialized API key: {}", maskApiKey(key));
        }
    }

    /**
     * Lấy API key tiếp theo với tải nhỏ nhất
     */
    public String getNextAvailableApiKey() {
        Set<String> keyHashes = stringRedisTemplate.opsForSet().members(REDIS_KEYS_LIST);

        // Lấy tất cả stats, sắp xếp theo tải
        List<ApiKeyUsageStats> allStats = keyHashes.stream()
                .map(hash -> {
                    String redisKey = REDIS_KEY_PREFIX + hash;
                    return apiKeyStatsRedisTemplate.opsForValue().get(redisKey);
                })
                .filter(Objects::nonNull)
                .sorted(Comparator
                        .comparing(ApiKeyUsageStats::getRequestCountCurrentMinute)
                        .thenComparing(ApiKeyUsageStats::getConsecutiveFailures))
                .collect(Collectors.toList());

        // Reset counter nếu cần
        for (ApiKeyUsageStats stat : allStats) {
            stat.resetCounterIfNeeded();
        }

        // Ưu tiên keys ACTIVE với ít request nhất
        for (ApiKeyUsageStats stat : allStats) {
            if (stat.getStatus() == ApiKeyUsageStats.KeyStatus.ACTIVE && stat.canMakeRequest()) {
                stat.incrementRequestCount();
                stat.setUpdatedAt(LocalDateTime.now());
                
                String redisKey = REDIS_KEY_PREFIX + stat.getKeyHash();
                apiKeyStatsRedisTemplate.opsForValue().set(redisKey, stat);
                
                log.debug("Selected key: {}, current requests: {}/{}", 
                    stat.getApiKeyMasked(), 
                    stat.getRequestCountCurrentMinute(), 
                    stat.getRequestsPerMinute());

                return findRawApiKeyByHash(stat.getKeyHash());
            }
        }

        // Nếu tất cả ACTIVE keys đều vượt giới hạn, thử lấy key bất kỳ
        log.warn("All active keys are rate limited, trying any available key");
        for (ApiKeyUsageStats stat : allStats) {
            if (stat.getStatus() != ApiKeyUsageStats.KeyStatus.INACTIVE) {
                stat.incrementRequestCount();
                stat.setUpdatedAt(LocalDateTime.now());
                
                String redisKey = REDIS_KEY_PREFIX + stat.getKeyHash();
                apiKeyStatsRedisTemplate.opsForValue().set(redisKey, stat);
                
                return findRawApiKeyByHash(stat.getKeyHash());
            }
        }

        throw new RuntimeException("No available API keys (all inactive or failed)");
    }

    /**
     * Ghi nhận request thành công
     */
    public void recordSuccess(String apiKey) {
        String keyHash = hashApiKey(apiKey);
        String redisKey = REDIS_KEY_PREFIX + keyHash;
        
        ApiKeyUsageStats stats = apiKeyStatsRedisTemplate.opsForValue().get(redisKey);
        if (stats != null) {
            stats.recordSuccess();
            stats.setUpdatedAt(LocalDateTime.now());
            apiKeyStatsRedisTemplate.opsForValue().set(redisKey, stats);
            log.debug("Recorded success for key: {}", stats.getApiKeyMasked());
        }
    }

    /**
     * Ghi nhận request thất bại
     */
    public void recordFailure(String apiKey, String failureMessage) {
        String keyHash = hashApiKey(apiKey);
        String redisKey = REDIS_KEY_PREFIX + keyHash;
        
        ApiKeyUsageStats stats = apiKeyStatsRedisTemplate.opsForValue().get(redisKey);
        if (stats != null) {
            stats.recordFailure(failureMessage);
            stats.setUpdatedAt(LocalDateTime.now());
            apiKeyStatsRedisTemplate.opsForValue().set(redisKey, stats);
            log.warn("Recorded failure for key: {} - {} (failures: {})", 
                stats.getApiKeyMasked(), failureMessage, stats.getConsecutiveFailures());
        }
    }

    /**
     * Lấy stats tất cả keys
     */
    public List<ApiKeyUsageStats> getAllKeyStats() {
        Set<String> keyHashes = stringRedisTemplate.opsForSet().members(REDIS_KEYS_LIST);

        if (keyHashes == null || keyHashes.isEmpty()) {
            return new ArrayList<>();
        }

        return keyHashes.stream()
                .map(hash -> {
                    String redisKey = REDIS_KEY_PREFIX + hash;
                    return apiKeyStatsRedisTemplate.opsForValue().get(redisKey);
                })
                .filter(Objects::nonNull)
                .sorted(Comparator
                        .comparing(ApiKeyUsageStats::getRequestCountCurrentMinute)
                        .thenComparing(ApiKeyUsageStats::getConsecutiveFailures))
                .collect(Collectors.toList());
    }

    /**
     * Lấy stats của key cụ thể
     */
    public Optional<ApiKeyUsageStats> getKeyStats(String apiKey) {
        String keyHash = hashApiKey(apiKey);
        String redisKey = REDIS_KEY_PREFIX + keyHash;
        ApiKeyUsageStats stats = apiKeyStatsRedisTemplate.opsForValue().get(redisKey);
        return Optional.ofNullable(stats);
    }

    /**
     * Bật/Tắt key
     */
    public void setKeyStatus(String apiKey, ApiKeyUsageStats.KeyStatus status) {
        String keyHash = hashApiKey(apiKey);
        String redisKey = REDIS_KEY_PREFIX + keyHash;
        
        ApiKeyUsageStats stats = apiKeyStatsRedisTemplate.opsForValue().get(redisKey);
        if (stats != null) {
            stats.setStatus(status);
            stats.setUpdatedAt(LocalDateTime.now());
            apiKeyStatsRedisTemplate.opsForValue().set(redisKey, stats);
            log.info("Set status of key {} to {}", stats.getApiKeyMasked(), status);
        }
    }

    /**
     * Reset tất cả counters
     */
    public void resetAllCounters() {
        Set<String> keyHashes = stringRedisTemplate.opsForSet().members(REDIS_KEYS_LIST);

        if (keyHashes != null) {
            for (String hash : keyHashes) {
                String redisKey = REDIS_KEY_PREFIX + hash;
                ApiKeyUsageStats stats = apiKeyStatsRedisTemplate.opsForValue().get(redisKey);
                
                if (stats != null) {
                    stats.setRequestCountCurrentMinute(0);
                    stats.setPeakCountCurrentMinute(0);
                    stats.setLastResetTime(System.currentTimeMillis());
                    stats.setUpdatedAt(LocalDateTime.now());
                    apiKeyStatsRedisTemplate.opsForValue().set(redisKey, stats);
                }
            }
        }
        log.info("Reset all API key counters");
    }

    /**
     * Recover key từ FAILED state
     */
    public void recoverKey(String apiKey) {
        String keyHash = hashApiKey(apiKey);
        String redisKey = REDIS_KEY_PREFIX + keyHash;
        
        ApiKeyUsageStats stats = apiKeyStatsRedisTemplate.opsForValue().get(redisKey);
        if (stats != null) {
            stats.setStatus(ApiKeyUsageStats.KeyStatus.ACTIVE);
            stats.setConsecutiveFailures(0);
            stats.setRequestCountCurrentMinute(0);
            stats.setLastResetTime(System.currentTimeMillis());
            stats.setUpdatedAt(LocalDateTime.now());
            apiKeyStatsRedisTemplate.opsForValue().set(redisKey, stats);
            log.info("Recovered key: {}", stats.getApiKeyMasked());
        }
    }

    /**
     * Hash API key để lưu trữ an toàn
     */
    private String hashApiKey(String apiKey) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(apiKey.getBytes());
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            log.error("Error hashing API key: {}", e.getMessage());
            throw new RuntimeException("Cannot hash API key", e);
        }
    }

    /**
     * Mask API key để hiển thị an toàn
     */
    private String maskApiKey(String apiKey) {
        if (apiKey.length() <= 4) {
            return "****";
        }
        return "***" + apiKey.substring(apiKey.length() - 4);
    }

    /**
     * Tìm raw API key từ hash
     */
    private String findRawApiKeyByHash(String keyHash) {
        for (String key : geminiApiKeyConfig.getAllApiKeys()) {
            if (hashApiKey(key).equals(keyHash)) {
                return key;
            }
        }
        throw new RuntimeException("Cannot find raw API key for hash: " + keyHash);
    }
}
