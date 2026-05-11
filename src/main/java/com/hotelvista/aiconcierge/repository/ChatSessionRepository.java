package com.hotelvista.aiconcierge.repository;

import com.hotelvista.aiconcierge.model.ChatSession;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChatSessionRepository extends MongoRepository<ChatSession, String> {

    List<ChatSession> findByCustomer_Id(String customerId);

    List<ChatSession> findByAssignedStaff_Id(String staffId);

    List<ChatSession> findByStatus(ChatSession.ChatStatus status);

    Optional<ChatSession> findBySessionId(String sessionId);

    List<ChatSession> findByStatusOrderByLastMessageTimeDesc(ChatSession.ChatStatus status);

    List<ChatSession> findByAssignedStaff_IdAndStatus(String staffId, ChatSession.ChatStatus status);
}
