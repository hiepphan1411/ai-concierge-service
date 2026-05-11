-- H2 Database Schema for API Key Usage Stats
-- Tệp này sẽ được tự động thực thi khi application start

CREATE TABLE IF NOT EXISTS api_key_usage_stats (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    
    key_hash VARCHAR(255) UNIQUE NOT NULL,
    api_key_masked VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    
    -- Rate Limiting Configuration
    requests_per_minute INT NOT NULL DEFAULT 20,
    peak_requests_per_minute INT NOT NULL DEFAULT 5,
    
    -- Current Usage Tracking
    request_count_current_minute INT NOT NULL DEFAULT 0,
    peak_count_current_minute INT NOT NULL DEFAULT 0,
    last_reset_time BIGINT NOT NULL,
    
    -- Failure Tracking
    consecutive_failures INT NOT NULL DEFAULT 0,
    last_failure_time TIMESTAMP,
    last_failure_message VARCHAR(500),
    
    -- Success Tracking
    total_successful_requests BIGINT NOT NULL DEFAULT 0,
    total_failed_requests BIGINT NOT NULL DEFAULT 0,
    last_success_time TIMESTAMP,
    
    -- Metadata
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    INDEX idx_status (status),
    INDEX idx_request_count (request_count_current_minute),
    INDEX idx_key_hash (key_hash)
);

-- Trigger để tự động update updated_at
CREATE TRIGGER IF NOT EXISTS api_key_usage_stats_update_trigger
BEFORE UPDATE ON api_key_usage_stats
FOR EACH ROW
SET NEW.updated_at = CURRENT_TIMESTAMP;
