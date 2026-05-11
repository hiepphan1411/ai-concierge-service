package com.hotelvista.aiconcierge.repository;

import com.hotelvista.aiconcierge.model.AiConversationHistory;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AiConversationHistoryRepository extends MongoRepository<AiConversationHistory, String> {

    Optional<AiConversationHistory> findByUserIdAndSessionId(String userId, String sessionId);

    Optional<AiConversationHistory> findTopByUserIdOrderByLastUpdatedDesc(String userId);

    void deleteByUserId(String userId);

    List<AiConversationHistory> findByUserIdOrderByLastUpdatedDesc(String userId);

    void deleteByUserIdAndSessionId(String userId, String sessionId);
}
