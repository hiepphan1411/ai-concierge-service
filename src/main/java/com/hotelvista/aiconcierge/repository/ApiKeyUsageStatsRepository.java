package com.hotelvista.aiconcierge.repository;

import com.hotelvista.aiconcierge.model.ApiKeyUsageStats;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ApiKeyUsageStatsRepository extends JpaRepository<ApiKeyUsageStats, Long> {

    Optional<ApiKeyUsageStats> findByKeyHash(String keyHash);

    List<ApiKeyUsageStats> findByStatus(ApiKeyUsageStats.KeyStatus status);

    @Query("SELECT a FROM ApiKeyUsageStats a WHERE a.status = 'ACTIVE' ORDER BY a.requestCountCurrentMinute ASC")
    List<ApiKeyUsageStats> findActiveKeysOrderByRequestCount();

    @Query("SELECT a FROM ApiKeyUsageStats a ORDER BY a.requestCountCurrentMinute ASC, a.consecutiveFailures ASC")
    List<ApiKeyUsageStats> findAllOrderedByLoad();
}
