package com.hotelvista.aiconcierge.repository;

import com.hotelvista.aiconcierge.model.AiAuditLog;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AiAuditLogRepository extends MongoRepository<AiAuditLog, String> {

    List<AiAuditLog> findByUserIdOrderByTimestampDesc(String userId);

    List<AiAuditLog> findByUserIdAndTimestampBetween(
            String userId, LocalDateTime from, LocalDateTime to);

    List<AiAuditLog> findBySuccessFalseOrderByTimestampDesc();
}
